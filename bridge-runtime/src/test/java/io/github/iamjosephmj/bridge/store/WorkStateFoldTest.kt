package io.github.iamjosephmj.bridge.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkStateFoldTest {
    private fun enq(chunks: Int = 0, maxAttempts: Int = 3) = WorkEvent.Enqueued(
        "w1", 1L, workerName = "upload", generation = 1, importance = 2,
        chunkCount = chunks, maxAttempts = maxAttempts)

    @Test fun `empty list folds to null`() {
        assertThat(foldWorkState(emptyList())).isNull()
    }

    @Test fun `enqueue then start is RUNNING at attempt 1`() {
        val s = foldWorkState(listOf(enq(), WorkEvent.Started("w1", 2L, 1, 1)))!!
        assertThat(s.runState).isEqualTo(RunState.RUNNING)
        assertThat(s.attempt).isEqualTo(1)
    }

    @Test fun `chunk completion advances nextChunk, stop returns to ENQUEUED`() {
        val s = foldWorkState(listOf(
            enq(chunks = 40),
            WorkEvent.Started("w1", 2L, 1, 1),
            WorkEvent.ChunkCompleted("w1", 3L, 0),
            WorkEvent.ChunkCompleted("w1", 4L, 1),
            WorkEvent.Stopped("w1", 5L, stopReason = 10),
        ))!!
        assertThat(s.nextChunk).isEqualTo(2)
        assertThat(s.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(s.lastStopReason).isEqualTo(10)
    }

    @Test fun `death returns work to ENQUEUED and records forensics`() {
        val died = WorkEvent.Died("w1", 5L, exitReason = 3, rssKb = 380_000, step = "chunk:2", attempt = 1)
        val s = foldWorkState(listOf(enq(chunks = 40), WorkEvent.Started("w1", 2L, 1, 1), died))!!
        assertThat(s.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(s.lastDeath).isEqualTo(died)
    }

    @Test fun `finished success is terminal SUCCEEDED`() {
        val s = foldWorkState(listOf(enq(), WorkEvent.Started("w1", 2L, 1, 1),
            WorkEvent.Finished("w1", 3L, success = true)))!!
        assertThat(s.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `finished failure is terminal FAILED`() {
        val s = foldWorkState(listOf(enq(), WorkEvent.Started("w1", 2L, 1, 1),
            WorkEvent.Finished("w1", 3L, success = false)))!!
        assertThat(s.runState).isEqualTo(RunState.FAILED)
    }

    @Test fun `re-enqueue bumps generation and resets progress`() {
        val s = foldWorkState(listOf(
            enq(chunks = 10),
            WorkEvent.Started("w1", 2L, 1, 1),
            WorkEvent.ChunkCompleted("w1", 3L, 0),
            WorkEvent.Finished("w1", 4L, success = true),
            enq(chunks = 10).copy(at = 5L, generation = 2),
        ))!!
        assertThat(s.generation).isEqualTo(2)
        assertThat(s.nextChunk).isEqualTo(0)
        assertThat(s.runState).isEqualTo(RunState.ENQUEUED)
    }
}
