package io.github.iamjosephmj.bench

import android.content.Context
import androidx.work.*

/** WorkManager keeps no run history, so the bench self-instruments timestamps.
 *  Recorded in-process + flushed to SharedPreferences to survive process death.
 *  Writes use .commit() (synchronous), not .apply(): a process kill immediately after a
 *  mark must not lose it, or the recorder fails at its one job of surviving process death.
 *
 *  Every method takes a [Context] (like [ChunkExecutionRecorder]) instead of a shared
 *  lateinit: a retried worker can be the FIRST bench code to run in a freshly relaunched
 *  process (force-stop scenario), and a lateinit that call sites must remember to set
 *  crashes exactly there — killing the retry the scenario exists to measure. */
object WmRecorder {
    private const val PREFS = "wm-recorder"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mark(context: Context, itemId: String, key: String, onlyFirst: Boolean = false) {
        val p = prefs(context)
        val k = "$itemId:$key"
        if (onlyFirst && p.contains(k)) {
            if (key == "start") bumpAttempts(context, itemId)
            return
        }
        p.edit().putLong(k, System.currentTimeMillis()).commit()
        if (key == "start") bumpAttempts(context, itemId)
    }
    private fun bumpAttempts(context: Context, itemId: String) {
        val p = prefs(context)
        p.edit().putInt("$itemId:attempts",
            p.getInt("$itemId:attempts", 0) + 1).commit()
    }

    /** Clears all marks before a fresh ENQUEUE_WM run so stale timestamps/attempts from a
     *  prior run (static [CORPUS] ids) don't pollute this run's [record] results. */
    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
    }

    fun record(context: Context, itemId: String): RunRecord {
        val p = prefs(context)
        return RunRecord(
            itemId = itemId, backend = "workmanager",
            enqueuedAt = p.getLong("$itemId:enqueue", 0L),
            firstStartAt = p.getLong("$itemId:start", 0L).takeIf { it != 0L },
            completedAt = p.getLong("$itemId:complete", 0L).takeIf { it != 0L },
            attempts = p.getInt("$itemId:attempts", 0),
            // Measured, not assumed: see ChunkExecutionRecorder. Filled in by collect() below.
            chunksReplayed = 0)
    }
}

class WmBenchWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {
    override fun doWork(): Result {
        val itemId = inputData.getString("itemId")!!
        val bytes = inputData.getLong("bytes", 0L)
        val chunks = inputData.getInt("chunks", 0)
        WmRecorder.mark(applicationContext, itemId, "start", onlyFirst = true)
        if (chunks > 0) {
            // No resume support: always from zero. That's the comparison point.
            for (i in 0 until chunks) {
                if (isStopped) return Result.retry()
                ChunkExecutionRecorder.recordExecution(applicationContext, "workmanager", itemId, i)
                simulateChunk(bytes / chunks, applicationContext.cacheDir, "$itemId-$i")
            }
        } else {
            simulateChunk(bytes, applicationContext.cacheDir, itemId)
        }
        WmRecorder.mark(applicationContext, itemId, "complete")
        return Result.success()
    }
}

class WorkManagerBackend(private val context: Context) : Backend {
    override val name = "workmanager"

    override fun enqueueAll(items: List<CorpusItem>) {
        val wm = WorkManager.getInstance(context)
        for (item in items) {
            WmRecorder.mark(context, item.id, "enqueue")
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

    override fun collect(): List<RunRecord> = CORPUS.map { item ->
        WmRecorder.record(context, item.id).copy(
            chunksReplayed = ChunkExecutionRecorder.replayed(context, "workmanager", item.id))
    }
}
