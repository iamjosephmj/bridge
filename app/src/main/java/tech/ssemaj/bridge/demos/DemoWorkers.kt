package tech.ssemaj.bridge.demos

import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.ChunkedWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import kotlinx.coroutines.delay

/**
 * The simplest possible [BridgeWorker]: a suspend function that does ~2s of "work" and
 * reports success. Used by the simple / constrained / deadline / periodic demos.
 */
class DemoWorker : BridgeWorker {
    override suspend fun run(ctx: RunContext): RunResult {
        delay(2_000)                       // pretend to sync something
        if (ctx.isStopped()) return RunResult.Retry
        return RunResult.Success
    }
}

/**
 * Resumable work: enqueued with `chunks(10)`, so Bridge calls [runChunk] once per chunk
 * and journals each completion. If the run is interrupted (stop, crash, process death)
 * the next attempt starts at `WorkState.nextChunk` instead of chunk 0 — the UI polls
 * that field to show live progress.
 */
class DemoChunkedWorker : ChunkedWorker {
    override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
        delay(400)                         // each chunk is a small, independently-committed unit
        return RunResult.Success
    }
}
