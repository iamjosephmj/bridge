package io.github.iamjosephmj.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.dispatch.FakeJobGateway
import io.github.iamjosephmj.bridge.signals.InMemoryTransitionStore
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeScopeJoinTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeJobGateway()
    private val clock = FakeClock(1000L)

    @After fun tearDown() = Bridge.reset()

    /** Listener count right after init — the DAG wake listener is a permanent resident. */
    private fun baseListeners() = Bridge.journalListenerCount()

    private fun init() = Bridge.initialize(context) {
        clock = this@BridgeScopeJoinTest.clock
        gateway = this@BridgeScopeJoinTest.gateway
        ioExecutor = Executor { it.run() }
        dbName = "scope-join-${System.nanoTime()}.db"
        signalSources = emptyList()
        transitionStore = InMemoryTransitionStore()
    }

    private fun runParked() = runBlocking {
        for ((_, payload) in gateway.enqueued.toList()) {
            val state = Bridge.state(payload.workId)
            if (state.runState == RunState.UNKNOWN) continue
            val attempts = Bridge.events(payload.workId)
                .count { it is WorkEvent.Started && it.generation == state.generation } + 1
            io.github.iamjosephmj.bridge.dispatch.BridgeServices.runner!!
                .run(payload.workId, payload.generation, attempts) { false }
        }
    }

    @Test fun `join suspends until completion and await returns the terminal state`() {
        init()
        val base = baseListeners()
        val handle = Bridge.scope().launch("finish-line") { step("noop") { 1 } }
        runBlocking {
            val result = async(Dispatchers.Default) { handle.await() }
            // The waiter registers its journal listener; the work has not run yet.
            withTimeout(5_000) { while (Bridge.journalListenerCount() == base) yield() }
            assertThat(result.isCompleted).isFalse()
            runParked()                               // completes the work → Finished appended
            assertThat(result.await()).isEqualTo(RunState.SUCCEEDED)
        }
        assertThat(Bridge.journalListenerCount()).isEqualTo(base)   // resume removed the listener
    }

    @Test fun `join on already-terminal work returns immediately`() {
        init()
        val base = baseListeners()
        val handle = Bridge.scope().launch("already-done") { step("noop") { 1 } }
        runParked()
        assertThat(handle.state()!!.runState).isEqualTo(RunState.SUCCEEDED)
        runBlocking {
            withTimeout(1_000) { handle.join() }
            assertThat(withTimeout(1_000) { handle.await() }).isEqualTo(RunState.SUCCEEDED)
        }
        assertThat(Bridge.journalListenerCount()).isEqualTo(base)   // fast path never registered one
    }

    @Test fun `cancelling a join removes the journal listener`() {
        init()
        val base = baseListeners()
        val handle = Bridge.scope().launch("never-runs") { step("noop") { 1 } }
        runBlocking {
            val waiter = launch(Dispatchers.Default) { handle.join() }
            withTimeout(5_000) { while (Bridge.journalListenerCount() == base) yield() }
            waiter.cancelAndJoin()
        }
        assertThat(Bridge.journalListenerCount()).isEqualTo(base)
    }

    @Test fun `cancelAll cancels a live durable and the scope's in-memory job`() {
        init()
        val scope = Bridge.scope()
        val handle = scope.launch("long-haul") {
            step("upload") { 1 }
            delay(2.hours)
            step("commit") { 2 }
        }
        runParked()                                   // runs step 1, parks on the timer — live
        assertThat(handle.state()!!.runState).isEqualTo(RunState.ENQUEUED)

        scope.cancelAll()
        assertThat(handle.state()!!.runState).isEqualTo(RunState.CANCELLED)
        assertThat(scope.coroutineContext[Job]!!.isCancelled).isTrue()
        // A join after cancellation resolves immediately with the terminal state.
        runBlocking {
            assertThat(withTimeout(1_000) { handle.await() }).isEqualTo(RunState.CANCELLED)
        }
    }
}
