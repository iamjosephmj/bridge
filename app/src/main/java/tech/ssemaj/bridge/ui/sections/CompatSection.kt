package tech.ssemaj.bridge.ui.sections

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.iamjosephmj.bridge.compat.BridgeWorkManager
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.demos.enqueueCompatChain
import tech.ssemaj.bridge.ui.DemoSection
import tech.ssemaj.bridge.ui.ResultText
import tech.ssemaj.bridge.ui.rememberDemoConsole

/**
 * Demo 7 — bridge-compat: the androidx.work-shaped façade.
 *
 * CompressWorker → UploadWorker is built with beginUniqueWork(...).then(...).enqueue(),
 * exactly like WorkManager. Under the hood the chain is ONE Bridge work item whose links
 * run as chunks — so the watchState line shows "chunk n/2", and a chain interrupted at
 * link 2 resumes at link 2. WorkInfo-style state comes from getWorkInfoState().
 */
@Composable
fun CompatSection() {
    val console = rememberDemoConsole()
    DemoSection(
        title = "7 · Compat chain",
        description = "BridgeWorkManager.beginUniqueWork(compress).then(upload).enqueue()" +
            " — WorkManager-shaped code, Bridge underneath: the two links run " +
            "as chunks of one resumable work item.",
    ) {
        Button(onClick = {
            console.run {
                val name = enqueueCompatChain()
                log("enqueued chain '$name': CompressWorker -> UploadWorker")
                watchState(name)   // chunk 0/2 → 2/2: one chunk per chain link
                log("WorkInfo state: ${BridgeWorkManager.getInstance().getWorkInfoState(Names.COMPAT_CHAIN)}")
            }
        }) { Text("Enqueue chain") }
        ResultText(console.text)
    }
}
