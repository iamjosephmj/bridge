package tech.ssemaj.bridge.ui.sections

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.workRequest
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.ui.DemoSection
import tech.ssemaj.bridge.ui.ResultText
import tech.ssemaj.bridge.ui.rememberDemoConsole

/**
 * Demo 2 — chunked, resumable work.
 *
 * `chunks(10)` turns one work item into ten journaled units: Bridge drives
 * `ChunkedWorker.runChunk(0..9)` and appends a ChunkCompleted event after each. The
 * console polls `Bridge.state(name).nextChunk` to show progress; if the run is stopped
 * mid-way (Doze, timeout, process death) the next attempt resumes at nextChunk, not 0.
 */
@Composable
fun ChunkedSection() {
    val console = rememberDemoConsole()
    DemoSection(
        title = "2 · Chunked resumable work",
        description = "chunks(10): each chunk commits to the journal, so an interrupted " +
            "run resumes at the next chunk instead of starting over. Watch " +
            "nextChunk advance 0 → 10.",
    ) {
        Button(onClick = {
            console.run {
                val name = Bridge.enqueue(
                    workRequest(Names.CHUNKED, Names.WORKER_CHUNKED) { chunks(10) })
                log("enqueued '$name' with chunks(10)")
                watchState(name)   // its state line includes "chunk n/10"
            }
        }) { Text("Run 10 chunks") }
        ResultText(console.text)
    }
}
