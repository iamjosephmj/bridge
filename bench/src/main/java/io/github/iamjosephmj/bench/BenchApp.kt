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
            // M5 device gap-closer: a durable block whose step counters persist in prefs
            // (memory dies with the process — that's the scenario). run-durable-fs.sh
            // force-stops mid-delay and asserts each step executed exactly once.
            durable("durable-fs") { ctx ->
                ctx.step("first") { bumpDurableCounter("first") }
                ctx.delay(20_000L)
                ctx.step("second") { bumpDurableCounter("second") }
            }
        }
    }

    private fun bumpDurableCounter(name: String): Int {
        val prefs = getSharedPreferences("durable-fs-counters", MODE_PRIVATE)
        val next = prefs.getInt(name, 0) + 1
        prefs.edit().putInt(name, next).commit()
        return next
    }
    private fun plain(kind: CorpusItem.Kind) = object : BridgeWorker {
        override suspend fun run(ctx: RunContext): RunResult {
            simulateChunk(kind.bytes, cacheDir, ctx.workId); return RunResult.Success
        }
    }
}
