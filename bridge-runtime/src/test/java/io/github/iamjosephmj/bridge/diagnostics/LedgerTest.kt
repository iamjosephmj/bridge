package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.signals.SignalSlice
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerTest {

    private val emptySlice = SignalSlice(emptyMap(), emptyList())

    @Test fun `M1-style history folds to two runs with ranges outcomes cost`() {
        val events = listOf(
            WorkEvent.Enqueued("w", 100L, "worker", 1, importance = 2, chunkCount = 40),
            WorkEvent.Dispatched("w", 110L, "DEFAULT", 1),
            WorkEvent.Started("w", 120L, attempt = 1, generation = 1),
            WorkEvent.ChunkCompleted("w", 130L, 0),
            WorkEvent.ChunkCompleted("w", 140L, 14),
            WorkEvent.Died("w", 150L, exitReason = 10, rssKb = 1024, step = "chunk:15", attempt = 1),
            WorkEvent.Dispatched("w", 200L, "DEFAULT", 1),
            WorkEvent.Started("w", 210L, attempt = 2, generation = 1),
            WorkEvent.ChunkCompleted("w", 220L, 15),
            WorkEvent.ChunkCompleted("w", 230L, 39),
            WorkEvent.Finished("w", 240L, success = true,
                cpuUserMs = 500, cpuSystemMs = 100, txBytes = 1000, rxBytes = 2000),
        )
        val intervals = mutableListOf<Pair<Long, Long>>()
        val ledger = LedgerFold.fold("w", events) { from, to ->
            intervals += from to to; emptySlice
        }
        assertEquals(2, ledger.runs.size)
        val (r1, r2) = ledger.runs
        assertEquals(LedgerOutcome.Died(10), r1.outcome)
        assertEquals(0..14, r1.chunksExecuted)
        assertEquals(110L, r1.dispatchedAt)
        assertEquals(150L, r1.endedAt)
        assertNull(r1.cost)
        assertEquals(LedgerOutcome.Completed(true), r2.outcome)
        assertEquals(15..39, r2.chunksExecuted)
        assertEquals(CostDelta(500, 100, 1000, 2000), r2.cost)
        assertNotNull(r2.deviceContext)
        assertEquals(listOf(110L to 150L, 200L to 240L), intervals)
    }

    @Test fun `cancelled and in-flight runs`() {
        val cancelled = LedgerFold.fold("c", listOf(
            WorkEvent.Enqueued("c", 1L, "worker", 1, importance = 2),
            WorkEvent.Dispatched("c", 2L, "DEFAULT", 1),
            WorkEvent.Cancelled("c", 3L))) { _, _ -> emptySlice }
        assertEquals(listOf<LedgerOutcome>(LedgerOutcome.Cancelled),
            cancelled.runs.map { it.outcome })

        val inFlight = LedgerFold.fold("f", listOf(
            WorkEvent.Enqueued("f", 1L, "worker", 1, importance = 2),
            WorkEvent.Dispatched("f", 2L, "DEFAULT", 1),
            WorkEvent.Started("f", 3L, 1, 1))) { _, _ -> emptySlice }
        assertEquals(listOf<LedgerOutcome>(LedgerOutcome.InFlight),
            inFlight.runs.map { it.outcome })
        assertNull(inFlight.runs[0].deviceContext)
    }

    @Test fun `no dispatch record still opens run at start`() {
        val ledger = LedgerFold.fold("s", listOf(
            WorkEvent.Enqueued("s", 1L, "worker", 1, importance = 2),
            WorkEvent.Started("s", 5L, 1, 1),
            WorkEvent.Finished("s", 9L, success = false))) { _, _ -> emptySlice }
        assertEquals(1, ledger.runs.size)
        assertNull(ledger.runs[0].dispatchedAt)
        assertEquals(5L, ledger.runs[0].startedAt)
        assertEquals(LedgerOutcome.Completed(false), ledger.runs[0].outcome)
    }
}
