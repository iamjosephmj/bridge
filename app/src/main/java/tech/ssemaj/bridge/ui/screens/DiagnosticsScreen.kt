package tech.ssemaj.bridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.diagnostics.Ledger
import io.github.iamjosephmj.bridge.glassbox.GlassBox
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.ui.ConsolePanel
import tech.ssemaj.bridge.ui.FeatureScreen
import tech.ssemaj.bridge.ui.rememberDemoConsole

private val SNIPPET = """
    Bridge.whyPending(name)   // one causal verdict + evidence
    Bridge.ledger(name)       // per-attempt run history
    Bridge.report()           // one line per known work item
    GlassBox.explain()        // device-level causes, no scheduler needed
""".trimIndent()

/** Diagnostics surface — same four calls as the original section. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val console = rememberDemoConsole()
    FeatureScreen(
        title = "Diagnostics",
        explainer = "Every question you would normally answer with a debugger has a total, " +
            "production-safe API: whyPending(name) returns one causal verdict with evidence " +
            "lines; ledger(name) lists every attempt with outcome, chunks, and CPU cost; " +
            "report() summarizes all known work; GlassBox.explain() names device-level " +
            "causes (Doze, App Standby, battery saver) even without the Bridge scheduler. " +
            "None of them return null or throw for unknown names. WorkManager's WorkInfo " +
            "tells you THAT work is ENQUEUED — verdicts tell you WHY, and the verdict " +
            "surface was proven on hardware. Run the other demos first so there is history.",
        snippet = SNIPPET,
        onBack = onBack,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        ConsolePanel(console.text)
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
