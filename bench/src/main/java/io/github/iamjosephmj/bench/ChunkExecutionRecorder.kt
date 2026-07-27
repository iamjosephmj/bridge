package io.github.iamjosephmj.bench

import android.content.Context

/**
 * Symmetric, backend-agnostic chunk-execution ledger.
 *
 * Both backends call [recordExecution] at the START of every chunk attempt (Bridge's
 * ChunkedWorker.runChunk and WorkManager's per-iteration chunk loop alike). A chunk that is
 * killed mid-execution therefore counts as an execution — and, if re-attempted, as a replay —
 * for both backends equally: this recorder does not know or care whether a chunk "finished",
 * only that it started running again.
 *
 * [replayed] is the measured (not assumed) count of re-executions: total executions minus the
 * number of distinct chunk indexes that were ever executed. Bridge's chunk-exact resume means
 * a given index should only ever execute once, so replayed() should measure 0 there; WorkManager
 * has no resume primitive and restarts a chunked job from index 0 after any kill, so replayed()
 * should show real, non-zero replays there after a kill mid-job.
 */
object ChunkExecutionRecorder {
    private const val PREFS = "chunk-recorder"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordExecution(context: Context, backend: String, itemId: String, chunkIndex: Int) {
        val p = prefs(context)
        val countKey = "$backend:$itemId:count"
        val indexesKey = "$backend:$itemId:indexes"
        val newCount = p.getInt(countKey, 0) + 1
        val existing = p.getStringSet(indexesKey, emptySet()) ?: emptySet()
        val updated = existing + chunkIndex.toString()
        // .commit() (synchronous): a process kill immediately after a chunk starts must not
        // lose the execution record, or the very replay we're trying to measure disappears.
        p.edit()
            .putInt(countKey, newCount)
            .putStringSet(indexesKey, updated)
            .commit()
    }

    fun replayed(context: Context, backend: String, itemId: String): Int {
        val p = prefs(context)
        val totalExecutions = p.getInt("$backend:$itemId:count", 0)
        val distinctCount = p.getStringSet("$backend:$itemId:indexes", emptySet())?.size ?: 0
        return computeReplayed(totalExecutions, distinctCount)
    }

    /** Pure arithmetic, split out so it's testable without a SharedPreferences/Context. */
    internal fun computeReplayed(totalExecutions: Int, distinctCount: Int): Int =
        (totalExecutions - distinctCount).coerceAtLeast(0)
}
