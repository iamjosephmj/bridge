package io.github.iamjosephmj.bridge.api

sealed interface RunResult {
    data object Success : RunResult
    data object Failure : RunResult
    data object Retry : RunResult
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
