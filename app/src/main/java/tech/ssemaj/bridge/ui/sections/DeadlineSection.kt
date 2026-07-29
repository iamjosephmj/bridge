package tech.ssemaj.bridge.ui.sections

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.Importance
import io.github.iamjosephmj.bridge.api.workRequest
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.ui.DemoSection
import tech.ssemaj.bridge.ui.ResultText
import tech.ssemaj.bridge.ui.rememberDemoConsole

/**
 * Demo 4 — deadline work.
 *
 * `mustCompleteBy(now + 2min)` feeds the policy engine's urgency tiers: as the deadline
 * nears, Bridge escalates how aggressively it asks the OS for execution (up to an exact
 * alarm at the edge). Importance.HIGH marks it user-visible work for cost accounting.
 */
@Composable
fun DeadlineSection() {
    val console = rememberDemoConsole()
    DemoSection(
        title = "4 · Deadline",
        description = "mustCompleteBy(now + 2 min): the policy engine walks urgency " +
            "tiers as the deadline approaches instead of waiting politely forever.",
    ) {
        Button(onClick = {
            console.run {
                val deadline = System.currentTimeMillis() + 2 * 60_000L
                val name = Bridge.enqueue(
                    workRequest(Names.DEADLINE, Names.WORKER_SIMPLE) {
                        importance(Importance.HIGH)
                        mustCompleteBy(deadline)
                    })
                log("enqueued '$name' — must complete by now+2min")
                log(Bridge.whyPending(name).render(System.currentTimeMillis()))
                watchState(name)
            }
        }) { Text("Enqueue with deadline") }
        ResultText(console.text)
    }
}
