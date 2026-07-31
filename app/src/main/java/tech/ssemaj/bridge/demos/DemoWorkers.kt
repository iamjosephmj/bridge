package tech.ssemaj.bridge.demos

import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.ChunkedWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.bridgeDataOf
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
 * Resumable work. `chunks(10)` on the request does NOT create ten tasks — this is one
 * work item (one name, one platform job, one retry budget) whose execution Bridge drives
 * as ten checkpointed steps: [runChunk] is called with indices 0..9 in a loop.
 *
 * The checkpoint contract:
 *  - Returning [RunResult.Success] IS the checkpoint — Bridge journals a durable
 *    ChunkCompleted event at that moment. There is no checkpoint API to call.
 *  - Nothing inside a chunk is checkpointed: die mid-chunk and that whole chunk re-runs
 *    on the next attempt (at-least-once per chunk — keep side effects idempotent).
 *  - After a stop, crash, or force-stop, the next attempt starts at `WorkState.nextChunk`
 *    (derived from the journal), never chunk 0 — the UI polls that field for progress.
 *  - State set via [RunContext.setOutput] before returning Success rides inside the
 *    chunk's journal event and comes back merged into `ctx.input` on resume, even in a
 *    brand-new process.
 */
class DemoChunkedWorker : ChunkedWorker {
    override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
        delay(400)                         // pretend to upload slice #chunkIndex (uncheckpointed)

        // Checkpointed state: a running total carried across chunks — and across
        // process death — via the journal. Resumed attempts see the prior total.
        val uploaded = ctx.input.getInt("slicesUploaded") + 1
        ctx.setOutput(bridgeDataOf("slicesUploaded" to uploaded))

        return RunResult.Success           // <- checkpoint commits here
    }
}
