package io.github.iamjosephmj.bridge.dispatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DispatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "d-${System.nanoTime()}.db", Executor { it.run() })
    private val gateway = FakeJobGateway()
    private val clock = FakeClock(1000L)
    private val dispatcher = Dispatcher(journal, gateway, clock)

    private fun enqueue(id: String, unmetered: Boolean = false, charging: Boolean = false) {
        journal.append(WorkEvent.Enqueued(id, clock.now(), workerName = "w", generation = 1,
            importance = 2, requiresCharging = charging, requiresUnmetered = unmetered))
    }

    @Test fun `dispatchAll hands every enqueued work to the gateway and journals it`() {
        enqueue("w1"); enqueue("w2", unmetered = true, charging = true)
        dispatcher.dispatchAll()
        assertThat(gateway.enqueued.map { it.second.workId }).containsExactly("w1", "w2")
        assertThat(gateway.enqueued.first { it.second.workId == "w2" }.first)
            .isEqualTo(HostJobClass.UNMETERED_CHARGING)
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.DISPATCHED)
    }

    @Test fun `already dispatched work is not re-enqueued`() {
        enqueue("w1")
        dispatcher.dispatchAll()
        dispatcher.dispatchAll()
        assertThat(gateway.enqueued).hasSize(1)
    }

    @Test fun `gateway failure leaves work ENQUEUED for retry`() {
        enqueue("w1")
        gateway.failNext = true
        dispatcher.dispatchAll()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.ENQUEUED)
    }

    @Test fun `read-after-write visibility with single-thread executor`() {
        // Test that appending work immediately followed by dispatch sees the write
        val singleThreadJournal = Journal(context, "d-single-${System.nanoTime()}.db", Executors.newSingleThreadExecutor())
        val singleThreadDispatcher = Dispatcher(singleThreadJournal, gateway, clock)
        singleThreadJournal.append(WorkEvent.Enqueued("w1", clock.now(), workerName = "w", generation = 1,
            importance = 2, requiresCharging = false, requiresUnmetered = false))
        // Immediate dispatch should see the written event (synchronous write + read-after-write visibility)
        singleThreadDispatcher.dispatch("w1")
        assertThat(gateway.enqueued).hasSize(1)
        assertThat(singleThreadJournal.state("w1")!!.runState).isEqualTo(RunState.DISPATCHED)
    }
}
