package tech.ssemaj.bridge.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
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
 * Demo 1 — plain and constrained enqueue.
 *
 * `Bridge.enqueue(workRequest(name, worker) { ... })` is the whole API. Unconstrained
 * work is dispatched immediately; the constrained variant declares unmetered + charging
 * and stays ENQUEUED until the device satisfies both — `whyPending()` names the exact
 * blocker instead of leaving you guessing.
 */
@Composable
fun EnqueueSection() {
    // Rehydrated from the journal on launch so a restart doesn't blank the console.
    val console = rememberDemoConsole(Names.SIMPLE, Names.CONSTRAINED)
    DemoSection(
        title = "1 · Enqueue",
        description = "Left: no constraints — runs as soon as the OS grants a slot. " +
            "Right: unmetered network + charging — pends until both hold, and " +
            "whyPending() explains why.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                console.run {
                    val name = Bridge.enqueue(workRequest(Names.SIMPLE, Names.WORKER_SIMPLE))
                    log("enqueued '$name' — no constraints")
                    watchState(name)
                }
            }) { Text("Simple") }

            Button(onClick = {
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
        ResultText(console.text)
    }
}
