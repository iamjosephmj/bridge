package io.github.iamjosephmj.bridge.exec

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeathAttributorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "da-${System.nanoTime()}.db", Executor { it.run() })
    private val clock = FakeClock(5000L)

    private class FakeDeathSource(val deaths: List<ProcessDeath>) : ProcessDeathSource {
        override fun recentDeaths() = deaths
    }

    private fun startWork(id: String) {
        journal.append(WorkEvent.Enqueued(id, 1L, "w", 1, 2))
        journal.append(WorkEvent.Started(id, 2L, attempt = 1, generation = 1))
    }

    @Test fun `matching death summary produces a Died event with forensics`() {
        startWork("w1")
        DeathAttributor(journal, FakeDeathSource(listOf(
            ProcessDeath(3L, reason = 3, rssKb = 380_000, summary = "w1|chunk:6|1"))), clock)
            .attributeDeaths()
        val state = journal.state("w1")!!
        assertThat(state.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(state.lastDeath!!.exitReason).isEqualTo(3)
        assertThat(state.lastDeath!!.step).isEqualTo("chunk:6")
    }

    @Test fun `running work with no matching death gets a generic Stopped`() {
        startWork("w1")
        DeathAttributor(journal, FakeDeathSource(emptyList()), clock).attributeDeaths()
        val state = journal.state("w1")!!
        assertThat(state.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(state.lastStopReason).isEqualTo(-1)
    }

    @Test fun `terminal work is untouched`() {
        startWork("w1")
        journal.append(WorkEvent.Finished("w1", 3L, success = true))
        DeathAttributor(journal, FakeDeathSource(emptyList()), clock).attributeDeaths()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `workId containing pipe is parsed correctly`() {
        startWork("sync|photos")
        DeathAttributor(journal, FakeDeathSource(listOf(
            ProcessDeath(3L, reason = 2, rssKb = 256_000, summary = "sync|photos|chunk:2|1"))), clock)
            .attributeDeaths()
        val state = journal.state("sync|photos")!!
        assertThat(state.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(state.lastDeath!!.exitReason).isEqualTo(2)
        assertThat(state.lastDeath!!.step).isEqualTo("chunk:2")
        assertThat(state.lastDeath!!.attempt).isEqualTo(1)
    }
}
