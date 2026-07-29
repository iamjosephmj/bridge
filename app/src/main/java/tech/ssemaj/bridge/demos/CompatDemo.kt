package tech.ssemaj.bridge.demos

import io.github.iamjosephmj.bridge.compat.BridgeWorkManager
import io.github.iamjosephmj.bridge.compat.ExistingWorkPolicy
import io.github.iamjosephmj.bridge.compat.OneTimeWorkRequest
import io.github.iamjosephmj.bridge.compat.Worker

/**
 * bridge-compat: an androidx.work-shaped façade. These two classes look exactly like
 * WorkManager `Worker`s — migrating an app is mostly an import change. Under the hood a
 * chain becomes ONE Bridge work item whose links run as chunks, so a chain interrupted
 * at link 2 resumes at link 2.
 */
class CompressWorker : Worker() {
    override fun doWork(): Result {
        Thread.sleep(1_000)               // compat workers are blocking, like WorkManager's
        return Result.success()
    }
}

class UploadWorker : Worker() {
    override fun doWork(): Result {
        Thread.sleep(1_000)
        return Result.success()
    }
}

/** Enqueues Compress -> Upload as a sequential unique chain (KEEP semantics). */
fun enqueueCompatChain(): String =
    BridgeWorkManager.getInstance()
        .beginUniqueWork(
            Names.COMPAT_CHAIN,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(CompressWorker::class.java).build(),
        )
        .then(OneTimeWorkRequest.Builder(UploadWorker::class.java).build())
        .enqueue()
