package io.github.iamjosephmj.bench

import android.content.Context
import androidx.work.*
import java.util.concurrent.ConcurrentHashMap

/** WorkManager keeps no run history, so the bench self-instruments timestamps.
 *  Recorded in-process + flushed to SharedPreferences to survive process death. */
object WmRecorder {
    private const val PREFS = "wm-recorder"
    lateinit var appContext: Context
    private val prefs by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun mark(itemId: String, key: String, onlyFirst: Boolean = false) {
        val k = "$itemId:$key"
        if (onlyFirst && prefs.contains(k)) {
            if (key == "start") bumpAttempts(itemId)
            return
        }
        prefs.edit().putLong(k, System.currentTimeMillis()).apply()
        if (key == "start") bumpAttempts(itemId)
    }
    private fun bumpAttempts(itemId: String) {
        prefs.edit().putInt("$itemId:attempts",
            prefs.getInt("$itemId:attempts", 0) + 1).apply()
    }
    fun record(itemId: String): RunRecord = RunRecord(
        itemId = itemId, backend = "workmanager",
        enqueuedAt = prefs.getLong("$itemId:enqueue", 0L),
        firstStartAt = prefs.getLong("$itemId:start", 0L).takeIf { it != 0L },
        completedAt = prefs.getLong("$itemId:complete", 0L).takeIf { it != 0L },
        attempts = prefs.getInt("$itemId:attempts", 0),
        chunksReplayed = 0)   // WorkManager has no chunk concept; restarts re-run everything
}

class WmBenchWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {
    override fun doWork(): Result {
        val itemId = inputData.getString("itemId")!!
        val bytes = inputData.getLong("bytes", 0L)
        val chunks = inputData.getInt("chunks", 0)
        WmRecorder.mark(itemId, "start", onlyFirst = true)
        if (chunks > 0) {
            // No resume support: always from zero. That's the comparison point.
            for (i in 0 until chunks) {
                if (isStopped) return Result.retry()
                simulateChunk(bytes / chunks, applicationContext.cacheDir, "$itemId-$i")
            }
        } else {
            simulateChunk(bytes, applicationContext.cacheDir, itemId)
        }
        WmRecorder.mark(itemId, "complete")
        return Result.success()
    }
}

class WorkManagerBackend(private val context: Context) : Backend {
    override val name = "workmanager"

    override fun enqueueAll(items: List<CorpusItem>) {
        WmRecorder.appContext = context.applicationContext
        val wm = WorkManager.getInstance(context)
        for (item in items) {
            WmRecorder.mark(item.id, "enqueue")
            val constraints = if (item.profile == CorpusItem.Profile.UNMETERED_CHARGING) {
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true).build()
            } else Constraints.NONE
            wm.enqueueUniqueWork(item.id, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WmBenchWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(
                        "itemId" to item.id,
                        "bytes" to item.kind.bytes,
                        "chunks" to item.kind.chunks))
                    .build())
        }
    }

    override fun collect(): List<RunRecord> = CORPUS.map { WmRecorder.record(it.id) }
}
