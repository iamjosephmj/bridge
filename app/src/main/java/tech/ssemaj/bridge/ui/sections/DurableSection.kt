package tech.ssemaj.bridge.ui.sections

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.store.RunState
import kotlinx.coroutines.delay
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.demos.launchDurableDemo
import tech.ssemaj.bridge.ui.DemoSection
import tech.ssemaj.bridge.ui.ResultText
import tech.ssemaj.bridge.ui.rememberDemoConsole

/**
 * Demo 3 — durable coroutine via `Bridge.scope().launch`.
 *
 * The block (see demos/DurableDemo.kt) runs step("fetch-token"), then delay(15s), then
 * step("commit"). The delay *parks*: the worker unwinds without burning an attempt, an
 * alarm is scheduled for the wake time, and on re-dispatch the journal replays the
 * completed steps and continues after the timer. While parked, whyPending() reports the
 * DurableParked verdict — the console polls it so you can watch park → completion.
 */
@Composable
fun DurableSection() {
    // Rehydrated from the journal: a durable block parked across a restart is watched again.
    val console = rememberDemoConsole(Names.DURABLE)
    DemoSection(
        title = "3 · Durable coroutine",
        description = "Bridge.scope().launch { step(..); delay(15s); step(..) } — a " +
            "suspend block that survives process death by journaled replay. " +
            "Expect DurableParked(delay until ...) for ~15s, then SUCCEEDED.",
    ) {
        Button(onClick = {
            console.run {
                val handle = launchDurableDemo()
                log("launched durable '${handle.name}'")
                val deadline = System.currentTimeMillis() + 40_000
                var last: String? = null
                while (System.currentTimeMillis() < deadline) {
                    val verdict = handle.whyPending()
                    // Headline only; the Diagnostics section shows full renders.
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
        ResultText(console.text)
    }
}
