package io.github.iamjosephmj.bridge.dispatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.exec.DeathAttributor
import io.github.iamjosephmj.bridge.exec.ProcessDeath
import io.github.iamjosephmj.bridge.exec.ProcessDeathSource
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconcilerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "rc-${System.nanoTime()}.db", Executor { it.run() })
    private val clock = FakeClock(1000L)
    private val gateway = FakeJobGateway()
    private val dispatcher = Dispatcher(journal, gateway, clock)
    private val deathAttributor = DeathAttributor(journal,
        object : ProcessDeathSource { override fun recentDeaths(): List<ProcessDeath> = emptyList() }, clock)

    private class FakeForceStopDetector(context: Context, private val result: Boolean) :
        ForceStopDetector(context) {
        override fun wasForceStoppedOrFirstRun(): Boolean = result
    }

    private fun reconciler(forceStopped: Boolean) = Reconciler(
        journal, dispatcher, deathAttributor,
        FakeForceStopDetector(context, forceStopped), gateway, clock)

    private fun seedWork() {
        // Two DISPATCHED items: still DISPATCHED (not yet touched by death attribution) at the
        // point the force-stop branch reads liveWork(), so both should be batched into one
        // Stopped(FORCE_STOP) appendAll() call.
        journal.append(WorkEvent.Enqueued("dispatched1", clock.now(), "w", generation = 1, importance = 2))
        journal.append(WorkEvent.Dispatched("dispatched1", clock.now(), "host", generation = 1))
        journal.append(WorkEvent.Enqueued("dispatched2", clock.now(), "w", generation = 1, importance = 2))
        journal.append(WorkEvent.Dispatched("dispatched2", clock.now(), "host", generation = 1))
        // RUNNING: attributeDeaths() (which always runs first in reconcile()) settles this to
        // ENQUEUED with a generic Stopped(-1) *before* the force-stop branch's liveWork() query
        // runs, so it is not part of the force-stop batch itself, but must still be re-dispatched.
        journal.append(WorkEvent.Enqueued("running1", clock.now(), "w", generation = 1, importance = 2))
        journal.append(WorkEvent.Dispatched("running1", clock.now(), "host", generation = 1))
        journal.append(WorkEvent.Started("running1", clock.now(), attempt = 1, generation = 1))
        // SUCCEEDED (terminal, must be untouched)
        journal.append(WorkEvent.Enqueued("done1", clock.now(), "w", generation = 1, importance = 2))
        journal.append(WorkEvent.Dispatched("done1", clock.now(), "host", generation = 1))
        journal.append(WorkEvent.Started("done1", clock.now(), attempt = 1, generation = 1))
        journal.append(WorkEvent.Finished("done1", clock.now(), success = true))
    }

    @Test fun `force-stop cancels gateway, batches Stopped for live work, and re-dispatches`() {
        seedWork()
        reconciler(forceStopped = true).reconcile()

        assertThat(gateway.cancelledAll).isTrue()

        val dispatched1 = journal.state("dispatched1")!!
        assertThat(dispatched1.runState).isEqualTo(RunState.DISPATCHED)
        assertThat(dispatched1.lastStopReason).isEqualTo(STOP_REASON_FORCE_STOP)

        val dispatched2 = journal.state("dispatched2")!!
        assertThat(dispatched2.runState).isEqualTo(RunState.DISPATCHED)
        assertThat(dispatched2.lastStopReason).isEqualTo(STOP_REASON_FORCE_STOP)

        // Settled by death attribution (process assumed dead), not by the force-stop batch,
        // but still live afterwards and therefore re-dispatched below.
        val running = journal.state("running1")!!
        assertThat(running.runState).isEqualTo(RunState.DISPATCHED)
        assertThat(running.lastStopReason).isEqualTo(-1)

        val done = journal.state("done1")!!
        assertThat(done.runState).isEqualTo(RunState.SUCCEEDED)
        assertThat(done.lastStopReason).isNull()

        // dispatchAll() re-dispatched every formerly-live item (each folded back to ENQUEUED,
        // then picked up by dispatchAll) through the gateway.
        assertThat(gateway.enqueued.map { it.second.workId })
            .containsExactly("dispatched1", "dispatched2", "running1")
    }

    @Test fun `no force-stop leaves dispatched work untouched and appends no force-stop Stopped events`() {
        seedWork()
        reconciler(forceStopped = false).reconcile()

        assertThat(gateway.cancelledAll).isFalse()
        // Dispatched work is untouched by the force-stop branch (which never fires here).
        assertThat(journal.state("dispatched1")!!.lastStopReason).isNull()
        assertThat(journal.state("dispatched2")!!.lastStopReason).isNull()
        assertThat(journal.events("dispatched1").none { it is WorkEvent.Stopped }).isTrue()
        assertThat(journal.events("dispatched2").none { it is WorkEvent.Stopped }).isTrue()
        // No Stopped event anywhere carries the force-stop reason.
        assertThat(journal.events("running1").none {
            it is WorkEvent.Stopped && it.stopReason == STOP_REASON_FORCE_STOP
        }).isTrue()
    }
}
