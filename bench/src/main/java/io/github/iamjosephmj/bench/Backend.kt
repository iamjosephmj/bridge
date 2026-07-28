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
    // ~200ms per MB: paces a 5MB chunk at ~1s, so LARGE_CHUNKED (40 chunks) spans ~40s and
    // is still mid-flight when the force-stop scenario kills the process at +20s. At the
    // original ~1ms/MB the whole corpus finished in ~2s and force-stop interrupted nothing.
    Thread.sleep(bytes / 1_000_000L * 200 + 5)
    f.delete()
}
