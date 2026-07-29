package io.github.iamjosephmj.bridge.sim

import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.diagnostics.Basis
import io.github.iamjosephmj.bridge.diagnostics.Diagnosis
import org.junit.Test

internal class OkWorker : BridgeWorker {
    override suspend fun run(ctx: RunContext) = RunResult.Success
}

class ScenarioTest {

    @Test fun `doze with maintenance windows - deferred then completes`() = simulate {
        worker("upload") { OkWorker() }
        doze(fromMs = 1.h, untilMs = 5.h, deep = true, maintenanceEveryMs = 2.h)
        val work = enqueue(workRequest("sync", "upload"))
        // Gates are open until doze starts at 1h, so the work runs on an early tick.
        assertThat(work.completedWithin(30.min)).isTrue()
    }

    @Test fun `work enqueued during deep doze waits for a maintenance window`() = simulate {
        worker("upload") { OkWorker() }
        doze(fromMs = 0L, untilMs = 5.h, deep = true, maintenanceEveryMs = 2.h)
        val work = enqueue(workRequest("sync", "upload"))
        val verdict = work.verdictAt(1.h)
        assertThat(verdict.diagnosis).isEqualTo(Diagnosis.DeferredByDoze(true))
        assertThat(verdict.basis).isEqualTo(Basis.INFERRED)
        // First maintenance window opens at 2h — work must complete inside it.
        assertThat(work.completedWithin(2.h + 15.min)).isTrue()
    }

    @Test fun `bucket ladder - RARE defers 24h`() = simulate {
        worker("upload") { OkWorker() }
        bucket(Buckets.RARE)
        val work = enqueue(workRequest("sync", "upload"))
        val verdict = work.verdictAt(3.h)
        assertThat(verdict.diagnosis).isEqualTo(Diagnosis.DeferredByStandbyBucket(Buckets.RARE))
        assertThat(work.completedWithin(23.h)).isFalse()
        assertThat(work.completedWithin(25.h)).isTrue()
    }

    @Test fun `data saver blocks unmetered work until it lifts`() = simulate {
        worker("upload") { OkWorker() }
        dataSaver(on = true)
        dataSaver(on = false, fromMs = 4.h)
        val work = enqueue(workRequest("sync", "upload") { unmetered() })
        assertThat(work.verdictAt(2.h).diagnosis).isEqualTo(Diagnosis.DataSaverBlocked)
        assertThat(work.completedWithin(3.h)).isFalse()
        assertThat(work.completedWithin(4.h + 5.min)).isTrue()
    }

    @Test fun `crash backoff explains throttling`() = simulate {
        var attempts = 0
        worker("flaky") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext): RunResult {
                attempts++
                return if (attempts <= 2) throw IllegalStateException("boom") else RunResult.Success
            }
        } }
        val work = enqueue(workRequest("sync", "flaky") { maxAttempts(5) })
        advanceTo(5.min)    // first attempt crashes
        advanceTo(40.min)   // backoff elapses, second attempt crashes
        val verdict = work.verdictAt(45.min)
        assertThat(verdict.diagnosis).isEqualTo(Diagnosis.ThrottledAfterCrashes(2))
        // Third attempt succeeds once its backoff elapses.
        assertThat(work.completedWithin(2.h)).isTrue()
    }

    @Test fun `stall mirror - RARE bucket verdict matches the device demo`() = simulate {
        worker("upload") { OkWorker() }
        bucket(Buckets.RARE)
        val work = enqueue(workRequest("photo-backup", "upload"))
        val verdict = work.verdictAt(4.h + 12.min)
        // The exact assertion the bench stall scenario prints from a real demoted device:
        assertThat(verdict.diagnosis).isEqualTo(Diagnosis.DeferredByStandbyBucket(Buckets.RARE))
        assertThat(verdict.basis).isEqualTo(Basis.INFERRED)   // sim has no platform reasons API
        assertThat(verdict.render(now = 4.h + 12.min))
            .contains("DeferredByStandbyBucket(RARE) [INFERRED]")
    }

    @Test fun `charging constraint gates until power connects`() = simulate {
        worker("upload") { OkWorker() }
        charging(on = false)
        charging(on = true, fromMs = 3.h)
        val work = enqueue(workRequest("sync", "upload") { charging() })
        assertThat(work.verdictAt(1.h).diagnosis)
            .isEqualTo(Diagnosis.AwaitingConstraint("charging"))
        assertThat(work.completedWithin(2.h)).isFalse()
        assertThat(work.completedWithin(3.h + 5.min)).isTrue()
    }
}
