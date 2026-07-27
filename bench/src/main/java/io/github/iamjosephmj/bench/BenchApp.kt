package io.github.iamjosephmj.bench

import android.app.Application
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.ChunkedWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult

class BenchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Bridge.initialize(this) {
            worker("bench-plain-PING") { plain(CorpusItem.Kind.PING) }
            worker("bench-plain-MEDIUM_SYNC") { plain(CorpusItem.Kind.MEDIUM_SYNC) }
            worker("bench-chunked") { object : ChunkedWorker {
                override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
                    // Recorded at the START of the chunk attempt (symmetric with
                    // WmBenchWorker) so a mid-execution kill counts as an execution here too.
                    ChunkExecutionRecorder.recordExecution(
                        this@BenchApp, "bridge", ctx.workId, chunkIndex)
                    val kind = CorpusItem.Kind.LARGE_CHUNKED
                    simulateChunk(kind.bytes / kind.chunks, cacheDir, "${ctx.workId}-$chunkIndex")
                    return RunResult.Success
                } } }
        }
    }
    private fun plain(kind: CorpusItem.Kind) = object : BridgeWorker {
        override suspend fun run(ctx: RunContext): RunResult {
            simulateChunk(kind.bytes, cacheDir, ctx.workId); return RunResult.Success
        }
    }
}
