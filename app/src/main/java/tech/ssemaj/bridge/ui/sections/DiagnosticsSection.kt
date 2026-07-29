package tech.ssemaj.bridge.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.diagnostics.Ledger
import io.github.iamjosephmj.bridge.glassbox.GlassBox
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.ui.DemoSection
import tech.ssemaj.bridge.ui.ResultText
import tech.ssemaj.bridge.ui.rememberDemoConsole

/**
 * Demo 6 — the diagnostics surface. All four calls are total (never null, never throw
 * for unknown names) and are safe to call from production code paths:
 *
 * - whyPending(name): one causal verdict for a pending item, with evidence lines;
 * - ledger(name):     per-attempt run history (outcome, chunks, cost, device context);
 * - report():         one line per known work item + conformance + cost flags;
 * - GlassBox.explain(): device-level causes — works even without the Bridge scheduler.
 */
@Composable
fun DiagnosticsSection() {
    val console = rememberDemoConsole()
    DemoSection(
        title = "6 · Diagnostics",
        description = "whyPending / ledger / report from bridge-runtime, plus " +
            "GlassBox.explain() from bridge-glassbox. Run demos 1–5 first so " +
            "there is history to look at.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier) {
            Button(onClick = {
                console.run {
                    log("whyPending('${Names.CONSTRAINED}'):")
                    log(Bridge.whyPending(Names.CONSTRAINED).render(System.currentTimeMillis()))
                }
            }) { Text("whyPending") }

            Button(onClick = {
                console.run {
                    log("ledger('${Names.CHUNKED}'):")
                    log(Bridge.ledger(Names.CHUNKED).renderRuns())
                }
            }) { Text("Ledger") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                console.run {
                    log("Bridge.report():")
                    log(Bridge.report().render(System.currentTimeMillis()))
                }
            }) { Text("Report") }

            Button(onClick = {
                console.run {
                    log("GlassBox.explain():")
                    log(GlassBox.explain().render())
                }
            }) { Text("GlassBox") }
        }
        ResultText(console.text)
    }
}

/** One line per attempt: generation, attempt number, outcome, chunk range, CPU cost. */
private fun Ledger.renderRuns(): String =
    if (runs.isEmpty()) "no runs recorded for '$workId'"
    else runs.joinToString("\n") { r ->
        buildString {
            append("gen=").append(r.generation)
            append(" attempt=").append(r.attempt)
            append("  ").append(r.outcome)
            r.chunksExecuted?.let { append("  chunks=").append(it) }
            r.cost?.let { append("  cpu=").append(it.cpuUserMs + it.cpuSystemMs).append("ms") }
        }
    }
