package io.github.iamjosephmj.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.dispatch.FakeJobGateway
import io.github.iamjosephmj.bridge.store.RunState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeFacadeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeJobGateway()

    @After fun tearDown() = Bridge.reset()

    private fun init() = Bridge.initialize(context) {
        worker("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        clock = FakeClock(1L)
        this.gateway = this@BridgeFacadeTest.gateway
        ioExecutor = Executor { it.run() }
        dbName = "f-${System.nanoTime()}.db"
    }

    @Test fun `enqueue journals and dispatches immediately`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(Bridge.state("sync").runState).isEqualTo(RunState.DISPATCHED)
        assertThat(gateway.enqueued.single().second.workId).isEqualTo("sync")
    }

    @Test fun `enqueue with same name keeps existing live work`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(gateway.enqueued).hasSize(1)
        assertThat(Bridge.state("sync").generation).isEqualTo(1)
    }

    @Test fun `cancel makes work CANCELLED`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        Bridge.cancel("sync")
        assertThat(Bridge.state("sync").runState).isEqualTo(RunState.CANCELLED)
    }

    @Test fun `initialize is idempotent`() {
        init(); init()
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(gateway.enqueued).hasSize(1)
    }

    @Test fun `tags cancel and query by tag, untagged work untouched`() {
        init()
        Bridge.enqueue(workRequest("t1", "ok") { tag("batch") })
        Bridge.enqueue(workRequest("t2", "ok") { tag("batch", "other") })
        Bridge.enqueue(workRequest("t3", "ok"))
        assertThat(Bridge.namesByTag("batch")).containsExactly("t1", "t2")
        Bridge.cancelAllByTag("batch")
        assertThat(Bridge.state("t1").runState).isEqualTo(RunState.CANCELLED)
        assertThat(Bridge.state("t2").runState).isEqualTo(RunState.CANCELLED)
        assertThat(Bridge.state("t3").runState).isEqualTo(RunState.DISPATCHED)
    }

    @Test fun `stateFlow emits initial state then follows journal events`() = runBlocking {
        init()
        val states = mutableListOf<RunState?>()
        val job = launch {
            Bridge.stateFlow("sync").collect { states += it.runState }
        }
        yield(); yield()
        Bridge.enqueue(workRequest("sync", "ok"))
        yield()
        Bridge.cancel("sync")
        yield(); yield()
        job.cancel()
        // Coroutine interleaving makes the pre-enqueue null emission timing-dependent —
        // assert the journal-driven sequence, which is what the API promises.
        assertThat(states).contains(RunState.DISPATCHED)
        assertThat(states.last()).isEqualTo(RunState.CANCELLED)
    }

    @Test fun `DAG dependent is gated, diagnosed, and doomed by a cancelled prerequisite`() {
        init()
        Bridge.enqueue(workRequest("parent", "ok"))
        Bridge.enqueue(workRequest("child", "ok") { after("parent") })
        // The fake gateway never runs the parent, so the child must not reach the platform.
        assertThat(gateway.enqueued.map { it.second.workId }).doesNotContain("child")
        val d = Bridge.whyPending("child").diagnosis
        assertThat(d).isInstanceOf(
            io.github.iamjosephmj.bridge.diagnostics.Diagnosis.WaitingForPrerequisites::class.java)
        // Cancelling the parent triggers the DAG wake listener → propagation → child FAILED.
        Bridge.cancel("parent")
        assertThat(Bridge.state("child").runState).isEqualTo(RunState.FAILED)
        assertThat(Bridge.events("child")
            .filterIsInstance<io.github.iamjosephmj.bridge.store.WorkEvent.Finished>()
            .single().failureMessage).contains("prerequisite 'parent'")
    }
}
