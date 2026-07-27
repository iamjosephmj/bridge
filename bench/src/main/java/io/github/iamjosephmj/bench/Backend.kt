package io.github.iamjosephmj.bench

data class RunRecord(
    val itemId: String, val backend: String,
    val enqueuedAt: Long, val firstStartAt: Long?, val completedAt: Long?,
    val attempts: Int, val chunksReplayed: Int,
)

interface Backend {
    val name: String
    fun enqueueAll(items: List<CorpusItem>)
    fun collect(): List<RunRecord>
}

/** Simulated transfer: deterministic CPU+IO proportional to size; no network. */
fun simulateChunk(bytes: Long, scratchDir: java.io.File, tag: String) {
    val f = java.io.File(scratchDir, "scratch-$tag.bin")
    f.writeBytes(ByteArray(minOf(bytes, 1_000_000L).toInt()))
    Thread.sleep(bytes / 1_000_000L + 5)   // ~1ms per MB
    f.delete()
}
