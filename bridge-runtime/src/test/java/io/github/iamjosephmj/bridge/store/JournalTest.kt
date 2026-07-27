package io.github.iamjosephmj.bridge.store

import androidx.test.core.app.ApplicationProvider   // via robolectric's androidx.test bundling
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val direct = Executor { it.run() }
    private fun journal() = Journal(context, dbName = "test-${System.nanoTime()}.db", ioExecutor = direct)

    private fun enq(id: String, at: Long) = WorkEvent.Enqueued(
        id, at, workerName = "w", generation = 1, importance = 2)

    @Test fun `append then read round-trips and folds`() {
        val j = journal()
        j.append(enq("w1", 1L))
        j.append(WorkEvent.Started("w1", 2L, attempt = 1, generation = 1))
        assertThat(j.events("w1")).hasSize(2)
        assertThat(j.state("w1")!!.runState).isEqualTo(RunState.RUNNING)
    }

    @Test fun `liveWork excludes terminal work`() {
        val j = journal()
        j.append(enq("w1", 1L))
        j.append(enq("w2", 2L))
        j.appendAll(listOf(
            WorkEvent.Started("w2", 3L, 1, 1),
            WorkEvent.Finished("w2", 4L, success = true)))
        assertThat(j.liveWork().map { it.workId }).containsExactly("w1")
    }

    @Test fun `state survives reopen`() {
        val name = "persist-${System.nanoTime()}.db"
        Journal(context, name, direct).apply { append(enq("w1", 1L)); close() }
        assertThat(Journal(context, name, direct).state("w1")!!.runState)
            .isEqualTo(RunState.ENQUEUED)
    }

    @Test fun `prune drops old terminal events but keeps live work`() {
        val j = journal()
        j.append(enq("old", 1L))
        j.appendAll(listOf(WorkEvent.Started("old", 2L, 1, 1),
            WorkEvent.Finished("old", 3L, success = true)))
        j.append(enq("live", 4L))
        j.prune(olderThanMs = 100L, now = 1000L)
        assertThat(j.events("old")).isEmpty()
        assertThat(j.events("live")).hasSize(1)
    }

    @Test fun `prune respects per-work-item atomicity for straddling events`() {
        val j = journal()
        // terminal work with old Enqueued but newer Finished (straddles cutoff at 500)
        j.append(enq("straddle", 100L))
        j.appendAll(listOf(
            WorkEvent.Started("straddle", 200L, 1, 1),
            WorkEvent.Finished("straddle", 600L, success = true)))  // newer than cutoff
        // fully old terminal work (all events < cutoff at 500)
        j.append(enq("fullyOld", 50L))
        j.appendAll(listOf(
            WorkEvent.Started("fullyOld", 100L, 1, 1),
            WorkEvent.Finished("fullyOld", 200L, success = true)))
        // live work (not deleted regardless)
        j.append(enq("live", 10L))

        j.prune(olderThanMs = 500L, now = 1000L)  // cutoff = 500

        // straddle kept intact because latest (600) >= cutoff (500)
        assertThat(j.events("straddle")).hasSize(3)
        assertThat(j.state("straddle")!!.runState).isEqualTo(RunState.SUCCEEDED)

        // fullyOld fully removed (latest = 200 < 500)
        assertThat(j.events("fullyOld")).isEmpty()
        assertThat(j.state("fullyOld")).isNull()

        // live kept
        assertThat(j.events("live")).hasSize(1)
    }
}
