package io.github.iamjosephmj.bridge.api

sealed interface RunResult {
    data object Success : RunResult
    data object Failure : RunResult
    data object Retry : RunResult

    /**
     * M5: the work is waiting (durable timer/await), not failing — parks never burn
     * maxAttempts and never count as crashes. [wakeAtMs] hints the alarm tier; 0 means
     * "wake on the next signal/dispatch pass".
     */
    data class Parked(val wakeAtMs: Long) : RunResult
}

class RunContext(
    val workId: String,
    val attempt: Int,
    val isStopped: () -> Boolean,
    /** Enqueue-time input, plus prerequisite outputs (overwrite-merged) for DAG work. */
    val input: BridgeData = BridgeData.EMPTY,
) {
    @Volatile
    private var output: BridgeData = BridgeData.EMPTY
    /**
     * Output journaled with the run: on the Finished event for plain workers, on each
     * ChunkCompleted for chunked workers (call inside runChunk before returning Success).
     * Visible in ledger() and overwrite-merged into DAG dependents' input.
     */
    fun setOutput(data: BridgeData) { output = data }
    internal fun output(): BridgeData = output
    /** Reads and clears — the chunk path journals per-chunk output exactly once. */
    internal fun takeOutput(): BridgeData = output.also { output = BridgeData.EMPTY }
}

interface BridgeWorker {
    suspend fun run(ctx: RunContext): RunResult
}

interface ChunkedWorker : BridgeWorker {
    suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult
    override suspend fun run(ctx: RunContext): RunResult =
        throw UnsupportedOperationException("chunked work is driven via runChunk")
}
