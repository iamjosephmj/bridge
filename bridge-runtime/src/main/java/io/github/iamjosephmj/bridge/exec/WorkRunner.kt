package io.github.iamjosephmj.bridge.exec

import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.ChunkedWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.WorkerRegistry
import io.github.iamjosephmj.bridge.api.nextCycle
import io.github.iamjosephmj.bridge.store.EventJournal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.StopReason
import io.github.iamjosephmj.bridge.store.WorkEvent
import kotlinx.coroutines.CancellationException

enum class RunOutcome { COMPLETED, FAILED, RETRY }

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
        var state = journal.state(workId) ?: return RunOutcome.COMPLETED
        // Periodic re-fire from the platform: a terminal cycle rolls to a fresh generation.
        // (CANCELLED does not roll — cancellation ends the series.)
        if (state.periodicMs > 0 &&
            state.runState in setOf(RunState.SUCCEEDED, RunState.FAILED)) {
            journal.append(WorkEvent.Enqueued.nextCycle(state, clock.now()))
            state = journal.state(workId)!!
        }
        // Periodic work ignores the payload-generation guard: the platform job outlives cycles.
        if (state.generation != generation && state.periodicMs == 0L) return RunOutcome.COMPLETED
        if (state.runState !in setOf(RunState.ENQUEUED, RunState.DISPATCHED)) {
            return RunOutcome.COMPLETED
        }

        journal.append(WorkEvent.Started(workId, clock.now(), deliveryCount, generation))
        val before = costMeter.snapshot()
        // Input merge, overwrite semantics (WorkManager's OverwritingInputMerger):
        // enqueue-time input, then DAG prerequisite outputs in declaration order, then —
        // for chunked work resuming after death — outputs journaled by completed chunks
        // of this generation, so a resumed chain still sees its earlier links' results.
        val events = journal.events(workId)
        val lastEnq = events.indexOfLast { it is WorkEvent.Enqueued }
        val chunkCarry = events.drop(lastEnq + 1)
            .filterIsInstance<WorkEvent.ChunkCompleted>()
            .fold(emptyMap<String, String>()) { acc, e -> acc + e.output }
        val mergedInput = state.prereqs.fold(state.input) { acc, prereq ->
            acc + (journal.state(prereq)?.lastOutput ?: emptyMap())
        } + chunkCarry
        val ctx = RunContext(workId, deliveryCount, isStopped,
            input = io.github.iamjosephmj.bridge.api.BridgeData(mergedInput))
        val worker = try { registry.create(state.workerName) } catch (e: IllegalArgumentException) {
            journal.append(WorkEvent.Finished(workId, clock.now(), success = false,
                failureMessage = "${e.javaClass.simpleName}: ${e.message}"))
            return RunOutcome.FAILED
        }
        // Structure mismatch (chunks() with a plain worker, or a ChunkedWorker without
        // chunks()) would otherwise hit ChunkedWorker.run()'s UnsupportedOperationException
        // — or silently run chunked work un-chunked — and be swallowed into retry→FAILED
        // with no evidence. Hard-fail with a journaled reason, like the unknown-worker branch.
        val declaredChunked = state.chunkCount > 0
        if (declaredChunked != (worker is ChunkedWorker)) {
            journal.append(WorkEvent.Finished(workId, clock.now(), success = false,
                failureMessage = if (declaredChunked)
                    "structure mismatch: chunks(${state.chunkCount}) declared but worker " +
                        "'${state.workerName}' is not a ChunkedWorker"
                else
                    "structure mismatch: worker '${state.workerName}' is a ChunkedWorker " +
                        "but the request declared no chunks()"))
            return RunOutcome.FAILED
        }

        try {
            val result: RunResult = if (state.chunkCount > 0 && worker is ChunkedWorker) {
                when (val chunkResult = runChunked(worker, ctx, workId, state.nextChunk, state.chunkCount, isStopped)) {
                    is ChunkRunResult.SystemStopped -> {
                        journal.append(WorkEvent.Stopped(workId, clock.now(), StopReason.SYSTEM_STOP.code))
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
                is RunResult.Success -> finish(workId, before, success = true,
                    output = ctx.output().asMap).let { RunOutcome.COMPLETED }
                is RunResult.Failure -> finish(workId, before, success = false,
                    output = ctx.output().asMap).let { RunOutcome.FAILED }
                is RunResult.Retry -> retryOrFail(workId, deliveryCount, state.maxAttempts, before)
                is RunResult.Parked -> {
                    journal.append(WorkEvent.Stopped(workId, clock.now(), StopReason.PARKED.code))
                    RunOutcome.RETRY
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return retryOrFail(workId, deliveryCount, state.maxAttempts, before,
                failureMessage = "${e.javaClass.simpleName}: ${e.message}")
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
                    journal.append(WorkEvent.ChunkCompleted(workId, clock.now(), idx,
                        output = ctx.takeOutput().asMap))
                else -> return ChunkRunResult.WorkerResult(r)
            }
        }
        return ChunkRunResult.AllCompleted
    }

    private fun finish(workId: String, before: CostSnapshot, success: Boolean,
                       failureMessage: String? = null,
                       output: Map<String, String> = emptyMap()) {
        val cost = costMeter.snapshot() - before
        journal.append(WorkEvent.Finished(workId, clock.now(), success,
            cpuUserMs = cost.cpuUserMs, cpuSystemMs = cost.cpuSystemMs,
            txBytes = cost.txBytes, rxBytes = cost.rxBytes,
            failureMessage = failureMessage, output = output))
    }

    private fun retryOrFail(workId: String, deliveryCount: Int, maxAttempts: Int,
                            before: CostSnapshot, failureMessage: String? = null): RunOutcome =
        if (deliveryCount >= maxAttempts) {
            finish(workId, before, success = false, failureMessage = failureMessage)
            RunOutcome.FAILED
        } else {
            journal.append(WorkEvent.Stopped(workId, clock.now(), StopReason.RETRY.code))
            RunOutcome.RETRY
        }
}
