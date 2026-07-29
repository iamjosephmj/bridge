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
    // Plain: dispatched as soon as the OS grants a slot.
    Bridge.enqueue(workRequest("demo-simple", "demo-worker"))

    // Constrained: pends until BOTH constraints hold.
    Bridge.enqueue(
        workRequest("demo-constrained", "demo-worker") {
            unmetered()
            charging()
        }
    )
    Bridge.whyPending("demo-constrained")   // names the exact blocker
""".trimIndent()

/**
 * Enqueue + constraints. Same demo logic as the original section: two buttons, one
 * console, rehydrated from the journal on entry.
 */
@Composable
fun EnqueueScreen(onBack: () -> Unit) {
    // Rehydrated from the journal on entry so a restart doesn't blank the console.
    val console = rememberDemoConsole(Names.SIMPLE, Names.CONSTRAINED)
    FeatureScreen(
        title = "Enqueue + constraints",
        explainer = "The whole scheduling API is one call: Bridge.enqueue(workRequest(...)). " +
            "Unconstrained work runs as soon as the OS grants a slot. Add unmetered() and " +
            "charging() and the item waits until the device satisfies both — and unlike " +
            "WorkManager, you are never left guessing why nothing happened: " +
            "whyPending() returns a causal verdict naming the exact blocker.",
        snippet = SNIPPET,
        onBack = onBack,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                console.run {
                    val name = Bridge.enqueue(workRequest(Names.SIMPLE, Names.WORKER_SIMPLE))
                    log("enqueued '$name' — no constraints")
                    watchState(name)
                }
            }) { Text("Simple") }

            OutlinedButton(onClick = {
                console.run {
                    val name = Bridge.enqueue(
                        workRequest(Names.CONSTRAINED, Names.WORKER_SIMPLE) {
                            unmetered()
                            charging()
                        })
                    log("enqueued '$name' — unmetered + charging")
                    log(Bridge.whyPending(name).render(System.currentTimeMillis()))
                    watchState(name, timeoutMs = 10_000)
                }
            }) { Text("Constrained") }
        }
        ConsolePanel(console.text)
    }
}
