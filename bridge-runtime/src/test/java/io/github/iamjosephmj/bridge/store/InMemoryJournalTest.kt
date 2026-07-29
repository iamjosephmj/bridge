package io.github.iamjosephmj.bridge.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryJournalTest {

    private fun enqueued(id: String, at: Long = 1L) = WorkEvent.Enqueued(
        id, at, workerName = "w", generation = 1, importance = 2)

    @Test
    fun `enqueue folds to ENQUEUED state`() {
        val j = InMemoryJournal()
        j.append(enqueued("a"))
        val s = j.state("a")!!
        assertEquals(RunState.ENQUEUED, s.runState)
        assertEquals("a", s.workId)
    }

    @Test
    fun `liveWork excludes terminal work, allWork includes it`() {
        val j = InMemoryJournal()
        j.append(enqueued("live"))
        j.appendAll(listOf(
            enqueued("done"),
            WorkEvent.Finished("done", 2L, success = true)))
        assertEquals(listOf("live"), j.liveWork().map { it.workId })
        assertEquals(setOf("live", "done"), j.allWork().map { it.workId }.toSet())
    }

    @Test
    fun `runningWork only returns RUNNING`() {
        val j = InMemoryJournal()
        j.append(enqueued("r"))
        j.append(WorkEvent.Dispatched("r", 2L, "DEFAULT", 1))
        j.append(WorkEvent.Started("r", 3L, attempt = 1, generation = 1))
        assertEquals(listOf("r"), j.runningWork().map { it.workId })
        assertTrue(j.runningWork().all { it.runState == RunState.RUNNING })
    }

    @Test
    fun `prune removes old terminal work only`() {
        val j = InMemoryJournal()
        j.appendAll(listOf(enqueued("old", at = 1L),
            WorkEvent.Finished("old", 10L, success = true)))
        j.appendAll(listOf(enqueued("fresh", at = 5_000L),
            WorkEvent.Finished("fresh", 9_000L, success = false)))
        j.append(enqueued("pending", at = 1L))
        j.prune(olderThanMs = 1_000L, now = 10_000L)
        assertNull(j.state("old"))
        assertEquals(RunState.FAILED, j.state("fresh")!!.runState)
        assertEquals(RunState.ENQUEUED, j.state("pending")!!.runState)
    }

    @Test
    fun `events returns append order`() {
        val j = InMemoryJournal()
        j.append(enqueued("a"))
        j.append(WorkEvent.Dispatched("a", 2L, "DEFAULT", 1))
        assertEquals(2, j.events("a").size)
        assertTrue(j.events("a")[0] is WorkEvent.Enqueued)
    }
}
