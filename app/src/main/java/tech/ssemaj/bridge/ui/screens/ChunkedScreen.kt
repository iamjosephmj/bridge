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
    Bridge.enqueue(
        workRequest("demo-chunked", "demo-chunked-worker") {
            chunks(10)   // Bridge drives runChunk(0..9),
        }                // journaling each completion
    )

    Bridge.state("demo-chunked")?.nextChunk   // resume point
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
        explainer = "chunks(10) turns one work item into ten journaled units of progress. " +
            "After each chunk, Bridge appends a ChunkCompleted event to its on-disk journal. " +
            "Kill the app mid-transfer — Doze, a timeout, swipe-away, force-stop — and the " +
            "next attempt resumes at the chunk it reached, not from zero. WorkManager would " +
            "restart the whole doWork() from the top. Proven on hardware: force-stopped " +
            "mid-run, the run continued at its recorded chunk.",
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
