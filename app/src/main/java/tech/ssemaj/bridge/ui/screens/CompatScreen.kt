package tech.ssemaj.bridge.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.compat.BridgeWorkManager
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.demos.enqueueCompatChain
import tech.ssemaj.bridge.ui.ConsolePanel
import tech.ssemaj.bridge.ui.FeatureScreen
import tech.ssemaj.bridge.ui.rememberDemoConsole

private val SNIPPET = """
    BridgeWorkManager.getInstance()
        .beginUniqueWork(
            "demo-compat-chain",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(CompressWorker::class.java)
                .build(),
        )
        .then(OneTimeWorkRequest.Builder(UploadWorker::class.java)
            .build())
        .enqueue()
""".trimIndent()

/** Compat chain — same demo logic as the original section. */
@Composable
fun CompatScreen(onBack: () -> Unit) {
    // Rehydrated from the journal: an in-flight chain resumes its watch on relaunch.
    val console = rememberDemoConsole(Names.COMPAT_CHAIN)
    FeatureScreen(
        title = "Compat",
        explainer = "bridge-compat is an androidx.work-shaped facade: workers extend a " +
            "Worker class with doWork(), and chains are built with " +
            "beginUniqueWork(...).then(...).enqueue() — migrating an app is mostly an " +
            "import change. Under the hood the whole chain becomes ONE Bridge work item " +
            "whose links run as chunks, so a chain interrupted at link 2 resumes at link 2 " +
            "instead of rerunning link 1. WorkInfo-style state comes from " +
            "getWorkInfoState().",
        snippet = SNIPPET,
        onBack = onBack,
    ) {
        Button(onClick = {
            console.run {
                val name = enqueueCompatChain()
                log("enqueued chain '$name': CompressWorker -> UploadWorker")
                watchState(name)   // chunk 0/2 → 2/2: one chunk per chain link
                log("WorkInfo state: ${BridgeWorkManager.getInstance().getWorkInfoState(Names.COMPAT_CHAIN)}")
            }
        }) { Text("Enqueue chain") }
        ConsolePanel(console.text)
    }
}
