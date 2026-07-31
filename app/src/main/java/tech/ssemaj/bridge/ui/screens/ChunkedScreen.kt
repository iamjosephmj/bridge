package tech.ssemaj.bridge.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.workRequest
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.ui.ConsolePanel
import tech.ssemaj.bridge.ui.FeatureScreen
import tech.ssemaj.bridge.ui.rememberDemoConsole

private val SNIPPET = """
    // ONE task, ten checkpointed steps — not ten tasks.
    Bridge.enqueue(
        workRequest("demo-chunked", "demo-chunked-worker") {
            chunks(10)   // Bridge drives runChunk(0..9)
        }
    )

    class DemoChunkedWorker : ChunkedWorker {
        override suspend fun runChunk(ctx, chunkIndex): RunResult {
            upload(slice = chunkIndex)        // uncheckpointed: re-runs if we die here
            ctx.setOutput(...)                // journaled state, survives death
            return RunResult.Success          // <- the checkpoint commits HERE
        }
    }

    Bridge.state("demo-chunked").nextChunk    // resume point, from the journal
""".trimIndent()

/**
 * Chunked resumable work — same demo logic as the original section, rehydrated on
 * entry so an interrupted run shows its resume point.
 */
@Composable
fun ChunkedScreen(onBack: () -> Unit) {
    // Rehydrated from the journal: an interrupted run shows its resume point on relaunch.
    val console = rememberDemoConsole(Names.CHUNKED)
    FeatureScreen(
        title = "Chunked resumption",
        explainer = "chunks(10) turns one work item into ten journaled units of progress — " +
            "still ONE task with one retry budget, not ten. There is no checkpoint API: " +
            "returning Success from runChunk IS the checkpoint — Bridge appends a durable " +
            "ChunkCompleted event at that moment, and nothing inside a chunk is saved until " +
            "it returns (so keep each chunk idempotent; a chunk interrupted mid-flight " +
            "re-runs from its start). Kill the app mid-transfer — Doze, a timeout, " +
            "swipe-away, force-stop — and the next attempt resumes at nextChunk, not zero, " +
            "with every completed chunk's setOutput() data merged back into ctx.input. " +
            "WorkManager would restart the whole doWork() from the top. Proven on hardware: " +
            "force-stopped mid-run, the run continued at its recorded chunk.",
        snippet = SNIPPET,
        onBack = onBack,
    ) {
        Button(onClick = {
            console.run {
                val name = Bridge.enqueue(
                    workRequest(Names.CHUNKED, Names.WORKER_CHUNKED) { chunks(10) })
                log("enqueued '$name' with chunks(10)")
                watchState(name)   // its state line includes "chunk n/10"
            }
        }) { Text("Run 10 chunks") }
        ConsolePanel(console.text)
    }
}
