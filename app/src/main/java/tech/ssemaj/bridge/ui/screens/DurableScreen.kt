package tech.ssemaj.bridge.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.Bridge
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.demos.launchDurableDemo
import tech.ssemaj.bridge.ui.ConsolePanel
import tech.ssemaj.bridge.ui.FeatureScreen
import tech.ssemaj.bridge.ui.rememberDemoConsole

private val SNIPPET = """
    Bridge.scope().launch("demo-durable") {
        val startedAt = now()               // journaled once
        val token = step("fetch-token") {   // runs once, ever
            "token-${'$'}{startedAt % 100_000}"
        }
        delay(15_000)                       // parks; survives death
        step("commit") { "committed ${'$'}token" }
    }
""".trimIndent()

/**
 * Durable coroutine demo. The watcher is the new scope-join API: `handle.await()`
 * suspends on a journal listener until the run is terminal — no polling loop. A small
 * nudger coroutine stands in for the parked alarm so the demo stays snappy in-app.
 */
@Composable
fun DurableScreen(onBack: () -> Unit) {
    // Rehydrated from the journal: a durable block parked across a restart is watched again.
    val console = rememberDemoConsole(Names.DURABLE)
    FeatureScreen(
        title = "Durable coroutines",
        explainer = "A suspend block that survives process death. Each step(...) result is " +
            "journaled and executes exactly once; delay(...) parks the block — the worker " +
            "unwinds without burning an attempt, an alarm is scheduled, and on wake-up the " +
            "journal replays completed steps and continues after the timer. WorkManager has " +
            "no equivalent: a killed coroutine there simply reruns from the top. Proven on " +
            "hardware: parked across a process kill, then completed. Expect " +
            "DurableParked(delay until ...) for about 15 seconds, then SUCCEEDED.",
        snippet = SNIPPET,
        onBack = onBack,
    ) {
        Button(onClick = {
            console.run {
                val handle = launchDurableDemo()
                log("launched durable '${handle.name}'")
                // Headline only; the Diagnostics screen shows full renders.
                log(handle.whyPending().render(System.currentTimeMillis()).lineSequence().first())
                // The showcase: suspend on the journal until the run is terminal.
                val terminal = withTimeoutOrNull(40_000) {
                    coroutineScope {
                        // Nudge the dispatcher; on a real device the scheduled alarm does this.
                        val nudger = launch {
                            while (true) { delay(1_000); Bridge.reconcileIfInitialized() }
                        }
                        handle.await().also { nudger.cancel() }
                    }
                }
                if (terminal == null) {
                    log("(gave up waiting after 40s — alarm wake-up may still be pending)")
                } else {
                    log("join() returned — $terminal")
                }
            }
        }) { Text("Launch durable") }
        ConsolePanel(console.text)
    }
}
