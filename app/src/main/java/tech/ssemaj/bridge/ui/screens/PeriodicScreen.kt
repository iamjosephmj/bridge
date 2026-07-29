package tech.ssemaj.bridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.workRequest
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.ui.ConsolePanel
import tech.ssemaj.bridge.ui.FeatureScreen
import tech.ssemaj.bridge.ui.rememberDemoConsole

private val SNIPPET = """
    Bridge.enqueue(
        workRequest("demo-periodic", "demo-worker") {
            periodic(15 * 60_000L)   // platform floor
        }
    )

    Bridge.cancel("demo-periodic")   // ends the series
""".trimIndent()

/** Periodic work — same demo logic as the original section: start + cancel. */
@Composable
fun PeriodicScreen(onBack: () -> Unit) {
    // Rehydrated from the journal: an active periodic series is picked up on relaunch.
    val console = rememberDemoConsole(Names.PERIODIC)
    FeatureScreen(
        title = "Periodic",
        explainer = "periodic(15 min) — the platform floor — re-enqueues a fresh generation " +
            "of the same work name after each completed cycle, so the journal keeps one " +
            "continuous history per name instead of scattering runs across random UUIDs. " +
            "Bridge.cancel(name) ends the series: the state fold shows CANCELLED and the " +
            "underlying OS job is withdrawn.",
        snippet = SNIPPET,
        onBack = onBack,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                console.run {
                    val name = Bridge.enqueue(
                        workRequest(Names.PERIODIC, Names.WORKER_SIMPLE) {
                            periodic(15 * 60_000L)
                        })
                    log("enqueued '$name' every 15 min (platform floor)")
                    watchState(name, timeoutMs = 10_000)
                    log("…the next cycle re-enqueues as a new generation")
                }
            }) { Text("Start periodic") }

            OutlinedButton(onClick = {
                console.run {
                    Bridge.cancel(Names.PERIODIC)
                    log("cancelled '${Names.PERIODIC}'")
                    log("state now: ${Bridge.state(Names.PERIODIC)?.runState ?: "unknown"}")
                }
            }) { Text("Cancel") }
        }
        ConsolePanel(console.text)
    }
}
