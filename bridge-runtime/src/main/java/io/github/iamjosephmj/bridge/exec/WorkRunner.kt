package io.github.iamjosephmj.bridge.exec

import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.ChunkedWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.WorkerRegistry
import io.github.iamjosephmj.bridge.store.EventJournal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import kotlinx.coroutines.CancellationException

enum class RunOutcome { COMPLETED, FAILED, RETRY }

private const val STOP_REASON_RETRY = 0
private const val STOP_REASON_SYSTEM_STOP = 1
/** Durable timer/await park — excluded from crash counting and attempt limits. */
const val STOP_REASON_PARKED = 2

/** Internal signal from chunked worker: distinguishes system stop from worker results. */
private sealed class ChunkRunResult {
    data class WorkerResult(val result: RunResult) : ChunkRunResult()
    object SystemStopped : ChunkRunResult()
    object AllCompleted : ChunkRunResult()
}

class WorkRunner(
    private val journal: EventJournal,
    private val registry: WorkerRegistry,
    private val blackBox: BlackBox,
    private val costMeter: CostMeter,
    private val clock: BridgeClock,
) {
    suspend fun run(workId: String, generation: Int, deliveryCount: Int,
                    isStopped: () -> Boolean): RunOutcome {
        val state = journal.state(workId) ?: return RunOutcome.COMPLETED
        if (state.generation != generation) return RunOutcome.COMPLETED
        if (state.runState !in setOf(RunState.ENQUEUED, RunState.DISPATCHED)) {
            return RunOutcome.COMPLETED
        }

        journal.append(WorkEvent.Started(workId, clock.now(), deliveryCount, generation))
        val before = costMeter.snapshot()
        val ctx = RunContext(workId, deliveryCount, isStopped)
        val worker = try { registry.create(state.workerName) } catch (e: IllegalArgumentException) {
            journal.append(WorkEvent.Finished(workId, clock.now(), success = false))
            return RunOutcome.FAILED
        }

        try {
            val result: RunResult = if (state.chunkCount > 0 && worker is ChunkedWorker) {
                when (val chunkResult = runChunked(worker, ctx, workId, state.nextChunk, state.chunkCount, isStopped)) {
                    is ChunkRunResult.SystemStopped -> {
                        journal.append(WorkEvent.Stopped(workId, clock.now(), STOP_REASON_SYSTEM_STOP))
                        return RunOutcome.RETRY
                    }
                    is ChunkRunResult.WorkerResult -> chunkResult.result
                    ChunkRunResult.AllCompleted -> RunResult.Success
                }
            } else {
                blackBox.stamp(workId, "run", deliveryCount)
                worker.run(ctx)
            }
            return when (result) {
                is RunResult.Success -> finish(workId, before, success = true)
                    .let { RunOutcome.COMPLETED }
                is RunResult.Failure -> finish(workId, before, success = false)
                    .let { RunOutcome.FAILED }
                is RunResult.Retry -> retryOrFail(workId, deliveryCount, state.maxAttempts, before)
                is RunResult.Parked -> {
                    journal.append(WorkEvent.Stopped(workId, clock.now(), STOP_REASON_PARKED))
                    RunOutcome.RETRY
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return retryOrFail(workId, deliveryCount, state.maxAttempts, before)
        } finally {
            blackBox.clear()
        }
    }

    /** Returns AllCompleted when all chunks done, SystemStopped when stop signal fires, WorkerResult per chunk result. */
    private suspend fun runChunked(worker: ChunkedWorker, ctx: RunContext, workId: String,
                                   fromChunk: Int, chunkCount: Int,
                                   isStopped: () -> Boolean): ChunkRunResult {
        for (idx in fromChunk until chunkCount) {
            if (isStopped()) return ChunkRunResult.SystemStopped
            blackBox.stamp(workId, "chunk:$idx", ctx.attempt)
            when (val r = worker.runChunk(ctx, idx)) {
                is RunResult.Success ->
                    journal.append(WorkEvent.ChunkCompleted(workId, clock.now(), idx))
                else -> return ChunkRunResult.WorkerResult(r)
            }
        }
        return ChunkRunResult.AllCompleted
    }

    private fun finish(workId: String, before: CostSnapshot, success: Boolean) {
        val cost = costMeter.snapshot() - before
        journal.append(WorkEvent.Finished(workId, clock.now(), success,
            cpuUserMs = cost.cpuUserMs, cpuSystemMs = cost.cpuSystemMs,
            txBytes = cost.txBytes, rxBytes = cost.rxBytes))
    }

    private fun retryOrFail(workId: String, deliveryCount: Int, maxAttempts: Int,
                            before: CostSnapshot): RunOutcome =
        if (deliveryCount >= maxAttempts) {
            finish(workId, before, success = false); RunOutcome.FAILED
        } else {
            journal.append(WorkEvent.Stopped(workId, clock.now(), STOP_REASON_RETRY))
            RunOutcome.RETRY
        }
}
