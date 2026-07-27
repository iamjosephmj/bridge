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
        val events = Bridge.events(item.id)
        val starts = events.filterIsInstance<WorkEvent.Started>()
        RunRecord(
            itemId = item.id, backend = name,
            enqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>().lastOrNull()?.at ?: 0L,
            firstStartAt = starts.minOfOrNull { it.at },
            completedAt = events.filterIsInstance<WorkEvent.Finished>()
                .lastOrNull { it.success }?.at,
            attempts = starts.size,
            // Measured, not assumed: see ChunkExecutionRecorder. Symmetric with the
            // WorkManager backend's chunksReplayed computation.
            chunksReplayed = ChunkExecutionRecorder.replayed(context, name, item.id))
    }

    companion object {
        fun workerFor(item: CorpusItem) =
            if (item.kind.chunks > 0) "bench-chunked" else "bench-plain-${item.kind.name}"
    }
}
