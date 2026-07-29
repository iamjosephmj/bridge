package io.github.iamjosephmj.bridge.sim

import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.diagnostics.Diagnosis
import org.junit.Test

class ConstraintScenarioTest {

    @Test fun `batteryNotLow work waits out a low battery`() = simulate {
        worker("upload") { OkWorker() }
        batteryLow(on = true)
        batteryLow(on = false, fromMs = 2.h)
        val work = enqueue(workRequest("sync", "upload") { batteryNotLow() })
        assertThat(work.verdictAt(1.h).diagnosis)
            .isEqualTo(Diagnosis.AwaitingConstraint("battery-not-low"))
        assertThat(work.completedWithin(90.min)).isFalse()
        assertThat(work.completedWithin(2.h + 5.min)).isTrue()
    }

    @Test fun `deviceIdle work runs ONLY inside doze - the inversion`() = simulate {
        worker("compact") { OkWorker() }
        doze(fromMs = 3.h, untilMs = 5.h, deep = true)
        val work = enqueue(workRequest("db-compact", "compact") { deviceIdle() })
        // Device awake: idle-work must NOT run.
        assertThat(work.completedWithin(2.h)).isFalse()
        assertThat(work.verdictAt(2.h).diagnosis)
            .isEqualTo(Diagnosis.AwaitingConstraint("device-idle"))
        // Doze arrives: now it runs.
        assertThat(work.completedWithin(3.h + 15.min)).isTrue()
    }

    @Test fun `content-trigger work waits for the scripted change`() = simulate {
        worker("index") { OkWorker() }
        contentChanged("content://media/photos", atMs = 2.h)
        val work = enqueue(workRequest("photo-index", "index") {
            contentTrigger("content://media/photos")
        })
        assertThat(work.verdictAt(1.h).diagnosis)
            .isEqualTo(Diagnosis.AwaitingConstraint("content-change"))
        assertThat(work.completedWithin(90.min)).isFalse()
        assertThat(work.completedWithin(2.h + 5.min)).isTrue()
    }

    @Test fun `content change before enqueue does not release the trigger`() = simulate {
        worker("index") { OkWorker() }
        contentChanged("content://media/photos", atMs = 0L)
        advanceTo(1.h)
        val work = enqueue(workRequest("photo-index", "index") {
            contentTrigger("content://media/photos")
        })
        assertThat(work.completedWithin(3.h)).isFalse()
    }

    @Test fun `constraint-free work runs with no network`() = simulate {
        worker("local") { OkWorker() }
        signal(io.github.iamjosephmj.bridge.signals.SignalKind.NETWORK_VALIDATED,
            io.github.iamjosephmj.bridge.signals.SignalValue.Flag(false), 0L)
        val work = enqueue(workRequest("cleanup", "local"))
        assertThat(work.completedWithin(10.min)).isTrue()
        // ...while network()-constrained work waits.
        val netWork = enqueue(workRequest("push", "local") { network() })
        assertThat(netWork.completedWithin(30.min)).isFalse()
    }
}
