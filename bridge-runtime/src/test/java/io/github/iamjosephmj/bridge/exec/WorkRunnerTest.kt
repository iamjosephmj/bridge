package io.github.iamjosephmj.bridge.exec

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.store.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkRunnerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "r-${System.nanoTime()}.db", Executor { it.run() })
    private val registry = WorkerRegistry()
    private val blackBox = FakeBlackBox()
    private val clock = FakeClock(100L)
    private fun runner(meter: CostMeter = FakeCostMeter(CostSnapshot.ZERO)) =
        WorkRunner(journal, registry, blackBox, meter, clock)

    private fun enqueue(id: String = "w1", worker: String = "ok",
                        chunks: Int = 0, maxAttempts: Int = 3) {
        journal.append(WorkEvent.Enqueued(id, 1L, worker, generation = 1,
            importance = 2, chunkCount = chunks, maxAttempts = maxAttempts))
    }

    @Test fun `success journals Finished with measured cost`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue()
        val meter = FakeCostMeter(
            CostSnapshot(100, 10, 0, 0), CostSnapshot(400, 60, 5000, 0))
        val outcome = runner(meter).run("w1", 1, deliveryCount = 1) { false }
        assertThat(outcome).isEqualTo(RunOutcome.COMPLETED)
        val fin = journal.events("w1").filterIsInstance<WorkEvent.Finished>().single()
        assertThat(fin.success).isTrue()
        assertThat(fin.cpuUserMs).isEqualTo(300)
        assertThat(fin.txBytes).isEqualTo(5000)
        assertThat(blackBox.last).isNull()   // cleared after run
    }

    @Test fun `stale generation is dropped without journaling`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue()
        val outcome = runner().run("w1", generation = 99, deliveryCount = 1) { false }
        assertThat(outcome).isEqualTo(RunOutcome.COMPLETED)
        assertThat(journal.events("w1").filterIsInstance<WorkEvent.Started>()).isEmpty()
    }

    @Test fun `retry below maxAttempts yields RETRY, at maxAttempts fails permanently`() = runTest {
        registry.register("flaky") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Retry } }
        enqueue(worker = "flaky", maxAttempts = 2)
        assertThat(runner().run("w1", 1, deliveryCount = 1) { false })
            .isEqualTo(RunOutcome.RETRY)
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(runner().run("w1", 1, deliveryCount = 2) { false })
            .isEqualTo(RunOutcome.FAILED)
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.FAILED)
    }

    @Test fun `worker exception counts as retryable`() = runTest {
        registry.register("boom") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext): RunResult = error("boom") } }
        enqueue(worker = "boom")
        assertThat(runner().run("w1", 1, deliveryCount = 1) { false })
            .isEqualTo(RunOutcome.RETRY)
    }

    @Test fun `chunked worker resumes from nextChunk and completes`() = runTest {
        val ran = mutableListOf<Int>()
        registry.register("chunky") { object : ChunkedWorker {
            override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
                ran += chunkIndex; return RunResult.Success
            } } }
        enqueue(worker = "chunky", chunks = 5)
        journal.append(WorkEvent.Started("w1", 2L, 1, 1))
        journal.append(WorkEvent.ChunkCompleted("w1", 3L, 0))
        journal.append(WorkEvent.ChunkCompleted("w1", 4L, 1))
        journal.append(WorkEvent.Stopped("w1", 5L, 10))
        val outcome = runner().run("w1", 1, deliveryCount = 2) { false }
        assertThat(outcome).isEqualTo(RunOutcome.COMPLETED)
        assertThat(ran).containsExactly(2, 3, 4).inOrder()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `stop signal between chunks yields RETRY preserving progress`() = runTest {
        var calls = 0
        registry.register("chunky") { object : ChunkedWorker {
            override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
                calls++; return RunResult.Success
            } } }
        enqueue(worker = "chunky", chunks = 10)
        val outcome = runner().run("w1", 1, deliveryCount = 1) { calls >= 3 }
        assertThat(outcome).isEqualTo(RunOutcome.RETRY)
        assertThat(journal.state("w1")!!.nextChunk).isEqualTo(3)
    }
}
