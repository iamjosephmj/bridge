package tech.ssemaj.bridge.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.Importance
import io.github.iamjosephmj.bridge.api.workRequest
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.ui.ConsolePanel
import tech.ssemaj.bridge.ui.FeatureScreen
import tech.ssemaj.bridge.ui.rememberDemoConsole

private val SNIPPET = """
    Bridge.enqueue(
        workRequest("demo-deadline", "demo-worker") {
            importance(Importance.HIGH)
            mustCompleteBy(System.currentTimeMillis() + 2 * 60_000)
        }
    )
""".trimIndent()

/** Deadline work — same demo logic as the original section. */
@Composable
fun DeadlineScreen(onBack: () -> Unit) {
    // Rehydrated from the journal so a pending deadline run survives a restart visibly.
    val console = rememberDemoConsole(Names.DEADLINE)
    FeatureScreen(
        title = "Deadlines",
        explainer = "mustCompleteBy(...) gives work a hard finish-by time. As the deadline " +
            "approaches, the policy engine walks urgency tiers — asking the OS more and more " +
            "aggressively for execution, up to an exact alarm at the edge — instead of " +
            "waiting politely forever the way an expedited-but-deferrable WorkManager job " +
            "can. Importance.HIGH marks it as user-visible work for cost accounting.",
        snippet = SNIPPET,
        onBack = onBack,
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
        ConsolePanel(console.text)
    }
}
