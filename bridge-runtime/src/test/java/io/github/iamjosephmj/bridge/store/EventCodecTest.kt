package io.github.iamjosephmj.bridge.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EventCodecTest {
    @Test fun `round-trips every event type`() {
        val events = listOf(
            WorkEvent.Enqueued("w1", 100L, workerName = "upload", generation = 1,
                importance = 2, requiresCharging = true, requiresUnmetered = true,
                chunkCount = 40, estimatedUpBytes = 200_000_000L, maxAttempts = 5,
                deadlineMs = 999_000L),
            WorkEvent.Dispatched("w1", 101L, hostClass = "UNMETERED_CHARGING", generation = 1),
            WorkEvent.Started("w1", 102L, attempt = 1, generation = 1),
            WorkEvent.ChunkCompleted("w1", 103L, chunkIndex = 6),
            WorkEvent.Stopped("w1", 104L, stopReason = 3),
            WorkEvent.Died("w1", 105L, exitReason = 3, rssKb = 380_000, step = "chunk:6", attempt = 1),
            WorkEvent.Finished("w1", 106L, success = true,
                cpuUserMs = 1200, cpuSystemMs = 300, txBytes = 5_000_000, rxBytes = 1000),
            WorkEvent.PolicyDecision("w1", 107L, decision = "hold",
                why = "estimated 12m exceeds ~10m window"),
            WorkEvent.StepCompleted("w1", 108L, name = "upload",
                resultJson = "{\"url\":\"x\"}", generation = 1),
        )
        for (e in events) {
            assertThat(EventCodec.decode(EventCodec.encode(e))).isEqualTo(e)
        }
    }
}
