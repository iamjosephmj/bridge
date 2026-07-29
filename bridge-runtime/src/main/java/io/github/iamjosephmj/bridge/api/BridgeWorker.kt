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
)

interface BridgeWorker {
    suspend fun run(ctx: RunContext): RunResult
}

interface ChunkedWorker : BridgeWorker {
    suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult
    override suspend fun run(ctx: RunContext): RunResult =
        throw UnsupportedOperationException("chunked work is driven via runChunk")
}
