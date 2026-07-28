package io.github.iamjosephmj.bridge.dispatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.exec.*
import io.github.iamjosephmj.bridge.store.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeJobServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "js-${System.nanoTime()}.db", Executor { it.run() })
    private val registry = WorkerRegistry()
    private val runner = WorkRunner(journal, registry, FakeBlackBox(),
        FakeCostMeter(CostSnapshot.ZERO), FakeClock(10L))

    private fun enqueue(id: String, worker: String) {
        journal.append(WorkEvent.Enqueued(id, 1L, worker, 1, 2))
    }

    @Test fun `drains queue, completes successful items, no reschedule`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue("w1", "ok"); enqueue("w2", "ok")
        val queue = ArrayDeque(listOf(
            WorkItemPayload("w1", 1) to 1, WorkItemPayload("w2", 1) to 1))
        val completed = mutableListOf<String>()
        val wantsReschedule = WorkQueueDrainer(runner, this).drain(
            dequeue = { queue.removeFirstOrNull() },
            complete = { completed += it.workId },
            isStopped = { false })
        assertThat(completed).containsExactly("w1", "w2")
        assertThat(wantsReschedule).isFalse()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `RETRY outcome leaves item uncompleted and requests reschedule`() = runTest {
        registry.register("flaky") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Retry } }
        enqueue("w1", "flaky")
        val queue = ArrayDeque(listOf(WorkItemPayload("w1", 1) to 1))
        val completed = mutableListOf<String>()
        val wantsReschedule = WorkQueueDrainer(runner, this).drain(
            dequeue = { queue.removeFirstOrNull() },
            complete = { completed += it.workId },
            isStopped = { false })
        assertThat(completed).isEmpty()
        assertThat(wantsReschedule).isTrue()
    }

    @Test fun `isStopped true before any dequeue drains nothing`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue("w1", "ok")
        val queue = ArrayDeque(listOf(WorkItemPayload("w1", 1) to 1))
        val completed = mutableListOf<String>()
        val wantsReschedule = WorkQueueDrainer(runner, this).drain(
            dequeue = { queue.removeFirstOrNull() },
            complete = { completed += it.workId },
            isStopped = { true })
        assertThat(completed).isEmpty()
        assertThat(wantsReschedule).isFalse()
        assertThat(queue).hasSize(1)
    }

    @Test fun `empty queue drains immediately with no reschedule`() = runTest {
        val completed = mutableListOf<String>()
        val wantsReschedule = WorkQueueDrainer(runner, this).drain(
            dequeue = { null },
            complete = { completed += it.workId },
            isStopped = { false })
        assertThat(completed).isEmpty()
        assertThat(wantsReschedule).isFalse()
    }

    @Test fun `worker cancellation propagates out of drain`() = runTest {
        registry.register("cancelled") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext): RunResult {
                throw CancellationException("stopped")
            } } }
        enqueue("w1", "cancelled")
        val queue = ArrayDeque(listOf(WorkItemPayload("w1", 1) to 1))
        val completed = mutableListOf<String>()
        try {
            WorkQueueDrainer(runner, this).drain(
                dequeue = { queue.removeFirstOrNull() },
                complete = { completed += it.workId },
                isStopped = { false })
            throw AssertionError("expected CancellationException")
        } catch (e: CancellationException) {
            // expected: normal shutdown signal, not a crash
        }
        assertThat(completed).isEmpty()
    }

    @Test fun `OneToOneDispatcher completes successfully with no reschedule`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue("w1", "ok")
        val wantsReschedule = OneToOneDispatcher(runner).run("w1", 1, isStopped = { false })
        assertThat(wantsReschedule).isFalse()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `OneToOneDispatcher requests reschedule on RETRY`() = runTest {
        registry.register("flaky") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Retry } }
        enqueue("w1", "flaky")
        val wantsReschedule = OneToOneDispatcher(runner).run("w1", 1, isStopped = { false })
        assertThat(wantsReschedule).isTrue()
    }

    @Test fun `OneToOneDispatcher returns null when stopped, caller must not call jobFinished`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue("w1", "ok")
        val wantsReschedule = OneToOneDispatcher(runner).run("w1", 1, isStopped = { true })
        assertThat(wantsReschedule).isNull()
    }

    @Test fun `OneToOneDispatcher propagates worker cancellation`() = runTest {
        registry.register("cancelled") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext): RunResult {
                throw CancellationException("stopped")
            } } }
        enqueue("w1", "cancelled")
        try {
            OneToOneDispatcher(runner).run("w1", 1, isStopped = { false })
            throw AssertionError("expected CancellationException")
        } catch (e: CancellationException) {
            // expected: normal shutdown signal, not a crash
        }
    }
}
