package io.github.iamjosephmj.bench

import android.content.Context
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.store.WorkEvent

class BridgeBackend(private val context: Context) : Backend {
    override val name = "bridge"

    override fun enqueueAll(items: List<CorpusItem>) {
        for (item in items) {
            Bridge.enqueue(workRequest(item.id, workerName = workerFor(item)) {
                if (item.profile == CorpusItem.Profile.UNMETERED_CHARGING) {
                    unmetered(); charging()
                }
                if (item.kind.chunks > 0) {
                    chunks(count = item.kind.chunks, estimatedUpBytes = item.kind.bytes)
                }
            })
        }
    }

    override fun collect(): List<RunRecord> = CORPUS.map { item ->
        // Measured, not assumed: see ChunkExecutionRecorder. Symmetric with the
        // WorkManager backend's chunksReplayed computation.
        recordFor(item.id, Bridge.events(item.id),
            ChunkExecutionRecorder.replayed(context, name, item.id))
    }

    companion object {
        /** Builds the record from the CURRENT run only — everything at/after the last
         *  Enqueued event. CORPUS ids are static, so the journal accumulates events from
         *  every prior bench run of the same item; without this slice, firstStartAt /
         *  completedAt / attempts leak history (stale timestamps, inflated attempts, and a
         *  prior run's Finished masquerading as this run's completion). */
        fun recordFor(itemId: String, events: List<WorkEvent>, chunksReplayed: Int): RunRecord {
            val lastEnqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>()
                .maxOfOrNull { it.at } ?: 0L
            val run = events.filter { it.at >= lastEnqueuedAt }
            val starts = run.filterIsInstance<WorkEvent.Started>()
            return RunRecord(
                itemId = itemId, backend = "bridge",
                enqueuedAt = lastEnqueuedAt,
                firstStartAt = starts.minOfOrNull { it.at },
                completedAt = run.filterIsInstance<WorkEvent.Finished>()
                    .lastOrNull { it.success }?.at,
                attempts = starts.size,
                chunksReplayed = chunksReplayed)
        }

        fun workerFor(item: CorpusItem) =
            if (item.kind.chunks > 0) "bench-chunked" else "bench-plain-${item.kind.name}"
    }
}
