package io.github.iamjosephmj.bridge.sim

import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.diagnostics.Diagnosis
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test

class DurableScenarioTest {

    /**
     * The M5 signature demo: a suspend function surviving process death and deep Doze
     * mid-delay(2.hours). Completed steps never re-execute. (Death mid-run is covered at
     * the unit level — sim runs execute atomically within a tick.)
     */
    @Test fun `signature - durable block survives death and doze mid-delay`() = simulate {
        var uploads = 0
        var creates = 0
        durable("publish-post") { ctx ->
            ctx.step("upload") { uploads++; "media-7" }
            ctx.delay(2.h)
            val media = ctx.step("create") { creates++; "post-with-media-7" }
            check(media == "post-with-media-7")
        }
        doze(fromMs = 1.h, untilMs = 3.h, deep = true)
        val work = startDurable("publish-post")

        advanceTo(30.min)
        restartProcess()                    // death mid-delay: journal + alarm survive

        val verdict = work.verdictAt(90.min)
        assertThat(verdict.diagnosis).isInstanceOf(Diagnosis.DurableParked::class.java)
        assertThat((verdict.diagnosis as Diagnosis.DurableParked).why).contains("delay")
        assertThat(uploads).isEqualTo(1)    // step never re-executed across death

        // Timer elapses ~2h but deep Doze holds dispatch until 3h; then it finishes.
        assertThat(work.completedWithin(2.h + 30.min)).isFalse()
        assertThat(work.completedWithin(3.h + 15.min)).isTrue()
        assertThat(uploads).isEqualTo(1)
        assertThat(creates).isEqualTo(1)
        // Replay left exactly one journaled record per step.
        assertThat(device.journal.events("publish-post")
            .filterIsInstance<WorkEvent.StepCompleted>()
            .count { it.name == "upload" }).isEqualTo(1)
    }

    @Test fun `await parks on a signal predicate and completes when it flips`() = simulate {
        var sent = 0
        durable("sender") { ctx ->
            ctx.await("validated-net") {
                it.values[SignalKind.NETWORK_VALIDATED] == SignalValue.Flag(true)
            }
            ctx.step("send") { sent++ }
        }
        signal(SignalKind.NETWORK_VALIDATED, SignalValue.Flag(false), 0L)
        signal(SignalKind.NETWORK_VALIDATED, SignalValue.Flag(true), 2.h)
        val work = startDurable("sender")

        val verdict = work.verdictAt(1.h)
        assertThat(verdict.diagnosis).isInstanceOf(Diagnosis.DurableParked::class.java)
        assertThat((verdict.diagnosis as Diagnosis.DurableParked).why).contains("await")
        assertThat(sent).isEqualTo(0)

        assertThat(work.completedWithin(2.h + 5.min)).isTrue()
        assertThat(sent).isEqualTo(1)
    }
}
