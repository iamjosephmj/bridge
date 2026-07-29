package tech.ssemaj.bridge.ui.sections

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
import tech.ssemaj.bridge.ui.DemoSection
import tech.ssemaj.bridge.ui.ResultText
import tech.ssemaj.bridge.ui.rememberDemoConsole

/**
 * Demo 5 — periodic work.
 *
 * `periodic(15 min)` (the platform floor) re-enqueues a fresh generation after each
 * completed cycle. `Bridge.cancel(name)` ends the series — the state fold shows
 * CANCELLED and the OS job is withdrawn.
 */
@Composable
fun PeriodicSection() {
    // Rehydrated from the journal: an active periodic series is picked up on relaunch.
    val console = rememberDemoConsole(Names.PERIODIC)
    DemoSection(
        title = "5 · Periodic",
        description = "periodic(15 min): each cycle is a new generation of the same " +
            "work name. Cancel ends the series.",
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
        ResultText(console.text)
    }
}
