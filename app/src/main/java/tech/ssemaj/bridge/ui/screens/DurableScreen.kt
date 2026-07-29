package tech.ssemaj.bridge.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.store.RunState
import kotlinx.coroutines.delay
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
 * Durable coroutine — same demo logic as the original section: poll the handle's
 * whyPending() headline until terminal.
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
                val deadline = System.currentTimeMillis() + 40_000
                var last: String? = null
                while (System.currentTimeMillis() < deadline) {
                    val verdict = handle.whyPending()
                    // Headline only; the Diagnostics screen shows full renders.
                    val line = verdict.render(System.currentTimeMillis()).lineSequence().first()
                    if (line != last) { log(line); last = line }
                    if (verdict.state in setOf(
                            RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED)) return@run
                    // Nudge the dispatcher; on a real device the scheduled alarm does this.
                    Bridge.reconcileIfInitialized()
                    delay(1_000)
                }
                log("(gave up watching after 40s — alarm wake-up may still be pending)")
            }
        }) { Text("Launch durable") }
        ConsolePanel(console.text)
    }
}
