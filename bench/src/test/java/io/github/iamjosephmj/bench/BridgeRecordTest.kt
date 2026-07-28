package io.github.iamjosephmj.bench

import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test

class BridgeRecordTest {
    private fun enqueued(at: Long, generation: Int) = WorkEvent.Enqueued(
        workId = "large_chunked-none", at = at, workerName = "bench-chunked",
        generation = generation, importance = 2)

    @Test fun `record only reflects the run since the last enqueue`() {
        // A prior completed run (generation 1) followed by a fresh enqueue (generation 2)
        // whose run started but has not finished yet — exactly the state a repeat bench
        // run produces, because CORPUS ids are static.
        val events = listOf(
            enqueued(at = 1_000L, generation = 1),
            WorkEvent.Started("large_chunked-none", at = 1_100L, attempt = 1, generation = 1),
            WorkEvent.Finished("large_chunked-none", at = 1_500L, success = true),
            enqueued(at = 10_000L, generation = 2),
            WorkEvent.Started("large_chunked-none", at = 10_200L, attempt = 1, generation = 2),
        )
        val record = BridgeBackend.recordFor("large_chunked-none", events, chunksReplayed = 0)
        assertThat(record.enqueuedAt).isEqualTo(10_000L)
        assertThat(record.firstStartAt).isEqualTo(10_200L)   // not 1_100 from the old run
        assertThat(record.completedAt).isNull()              // old run's Finished must not leak
        assertThat(record.attempts).isEqualTo(1)             // not 2 across runs
    }

    @Test fun `record with completion in current run`() {
        val events = listOf(
            enqueued(at = 1_000L, generation = 1),
            WorkEvent.Started("large_chunked-none", at = 1_100L, attempt = 1, generation = 1),
            WorkEvent.Started("large_chunked-none", at = 1_300L, attempt = 2, generation = 1),
            WorkEvent.Finished("large_chunked-none", at = 1_500L, success = true),
        )
        val record = BridgeBackend.recordFor("large_chunked-none", events, chunksReplayed = 3)
        assertThat(record.enqueuedAt).isEqualTo(1_000L)
        assertThat(record.firstStartAt).isEqualTo(1_100L)
        assertThat(record.completedAt).isEqualTo(1_500L)
        assertThat(record.attempts).isEqualTo(2)
        assertThat(record.chunksReplayed).isEqualTo(3)
    }
}
