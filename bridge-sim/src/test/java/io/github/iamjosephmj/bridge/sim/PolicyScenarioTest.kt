package io.github.iamjosephmj.bridge.sim

import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.Importance
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.diagnostics.Diagnosis
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test

class PolicyScenarioTest {

    @Test fun `(a) unchunked long estimate holds, chunked twin admits, recovery releases`() = simulate {
        worker("upload") { OkWorker() }
        worker("uploadChunked") { OkChunkedWorker() }
        bucket(Buckets.WORKING_SET)
        bucket(Buckets.ACTIVE, atMs = 6.h)
        // 900MB at ~1MB/s ≈ 15m estimate > ~10m bucket window → held
        enqueue(workRequest("estimated", "upload") { estimatedBytes(900_000_000L) })
        advanceTo(5.min)
        val verdict = device.verdict("estimated")!!
        assertThat(verdict.diagnosis).isInstanceOf(Diagnosis.HeldByPolicy::class.java)
        assertThat((verdict.diagnosis as Diagnosis.HeldByPolicy).why).contains("exceeds")
        // chunked twin admits immediately despite the same size
        val chunked = enqueue(workRequest("chunky", "uploadChunked") { chunks(40, 900_000_000L) })
        assertThat(chunked.completedWithin(3.h)).isTrue()   // WORKING_SET floor 2h + slack
        // bucket recovers at 6h → the held estimate admits and completes
        advanceTo(6.h + 5.min)
        assertThat(device.journal.state("estimated")!!.runState.name).isEqualTo("SUCCEEDED")
    }

    @Test fun `(c) LOW sheds in FREQUENT and recovers with the bucket`() = simulate {
        worker("upload") { OkWorker() }
        bucket(Buckets.FREQUENT)
        bucket(Buckets.ACTIVE, atMs = 10.h)
        val low = enqueue(workRequest("telemetry", "upload") { importance(Importance.LOW) })
        val normal = enqueue(workRequest("sync", "upload"))
        advanceTo(5.min)
        val verdict = low.verdictAt(10.min)
        assertThat(verdict.diagnosis).isInstanceOf(Diagnosis.HeldByPolicy::class.java)
        assertThat((verdict.diagnosis as Diagnosis.HeldByPolicy).why).contains("quota")
        // DEFAULT work is not shed — it waits only for the FREQUENT floor (8h)
        assertThat(normal.completedWithin(9.h)).isTrue()
        assertThat(low.completedWithin(9.h)).isFalse()      // still shed at FREQUENT
        assertThat(low.completedWithin(10.h + 5.min)).isTrue()  // bucket recovered
    }

    @Test fun `(d) deadline escalates to EXPEDITED and beats a RARE bucket`() = simulate {
        worker("upload") { OkWorker() }
        bucket(Buckets.RARE)   // floor 24h — without escalation this work would wait a day
        val work = enqueue(workRequest("invoice", "upload") { mustCompleteBy(2.h) })
        assertThat(work.completedWithin(2.h)).isTrue()
        val decisions = device.journal.events("invoice")
            .filterIsInstance<WorkEvent.PolicyDecision>().map { it.decision }
        assertThat(decisions).contains("admit:EXPEDITED")
    }

    @Test fun `(d2) fully blocked deadline work arms the while-idle alarm`() = simulate {
        worker("upload") { OkWorker() }
        bgRestricted(on = true)   // blocks even expedited dispatch in the sim gate order
        enqueue(workRequest("invoice", "upload") { mustCompleteBy(2.h) })
        advanceTo(2.h)
        assertThat(device.alarms.scheduled.size + /* consumed alarms also count */
            device.journal.events("invoice").count { it is WorkEvent.PolicyDecision &&
                it.why.contains("escalate:ALARM") }).isAtLeast(1)
    }

    @Test fun `(e) burst drain - three doze-held items finish in the first window`() = simulate {
        worker("upload") { OkWorker() }
        doze(fromMs = 0L, untilMs = 10.h, deep = true, maintenanceEveryMs = 3.h)
        val a = enqueue(workRequest("a", "upload"))
        val b = enqueue(workRequest("b", "upload"))
        val c = enqueue(workRequest("c", "upload"))
        // First maintenance window opens at 3h; all three must drain inside it.
        assertThat(a.completedWithin(3.h + 15.min)).isTrue()
        assertThat(b.completedWithin(3.h + 15.min)).isTrue()
        assertThat(c.completedWithin(3.h + 15.min)).isTrue()
    }

    @Test fun `(f) thermal hold surfaces in the verdict and releases`() = simulate {
        worker("upload") { OkWorker() }
        thermal(status = 3)              // SEVERE
        thermal(status = 0, fromMs = 2.h)
        val work = enqueue(workRequest("sync", "upload"))
        val verdict = work.verdictAt(30.min)
        assertThat(verdict.diagnosis).isInstanceOf(Diagnosis.HeldByPolicy::class.java)
        assertThat((verdict.diagnosis as Diagnosis.HeldByPolicy).why).contains("thermal")
        assertThat(work.completedWithin(2.h + 5.min)).isTrue()
    }

    @Test fun `(g) MEDIUM pressure defers LOW work, spares DEFAULT, releases when it clears`() = simulate {
        worker("upload") { OkWorker() }
        threadPressure(runnable = 12)            // MEDIUM: 8 cores < 12 ≤ 16
        threadPressure(runnable = 2, fromMs = 1.h)
        val low = enqueue(workRequest("backup", "upload") { importance(Importance.LOW) })
        val normal = enqueue(workRequest("sync", "upload"))
        val verdict = low.verdictAt(5.min)
        assertThat(verdict.diagnosis).isInstanceOf(Diagnosis.HeldByPolicy::class.java)
        assertThat((verdict.diagnosis as Diagnosis.HeldByPolicy).why).contains("MEDIUM")
        assertThat(normal.completedWithin(30.min)).isTrue()   // DEFAULT unaffected at MEDIUM
        assertThat(low.completedWithin(1.h + 10.min)).isTrue()
    }

    @Test fun `(g2) HIGH pressure also defers DEFAULT work but not HIGH importance`() = simulate {
        worker("upload") { OkWorker() }
        threadPressure(runnable = 20)            // HIGH: > 8 cores × 2
        threadPressure(runnable = 2, fromMs = 1.h)
        val normal = enqueue(workRequest("sync", "upload"))
        val urgent = enqueue(workRequest("send-message", "upload") { importance(Importance.HIGH) })
        val verdict = normal.verdictAt(5.min)
        assertThat(verdict.diagnosis).isInstanceOf(Diagnosis.HeldByPolicy::class.java)
        assertThat((verdict.diagnosis as Diagnosis.HeldByPolicy).why).contains("HIGH")
        assertThat(urgent.completedWithin(30.min)).isTrue()   // HIGH importance never waits
        assertThat(normal.completedWithin(1.h + 10.min)).isTrue()
    }
}
