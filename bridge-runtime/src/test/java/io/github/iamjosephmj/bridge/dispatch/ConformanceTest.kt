package io.github.iamjosephmj.bridge.dispatch

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.store.KvStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConformanceTest {
    private fun conformance() = Conformance(KvStore(SQLiteDatabase.create(null)))

    @Test fun `defaults to MULTIPLEXED`() {
        assertThat(conformance().mode).isEqualTo(DispatchMode.MULTIPLEXED)
    }

    @Test fun `three consecutive failures flips to ONE_TO_ONE and persists`() {
        val db = SQLiteDatabase.create(null)
        val c = Conformance(KvStore(db))
        repeat(3) { c.recordEnqueueFailure() }
        assertThat(c.mode).isEqualTo(DispatchMode.ONE_TO_ONE)
        // A fresh KvStore reloads from the same database — the decision survived.
        assertThat(Conformance(KvStore(db)).mode).isEqualTo(DispatchMode.ONE_TO_ONE)
    }

    @Test fun `success resets the failure streak`() {
        val c = conformance()
        c.recordEnqueueFailure(); c.recordEnqueueFailure()
        c.recordEnqueueSuccess()
        c.recordEnqueueFailure(); c.recordEnqueueFailure()
        assertThat(c.mode).isEqualTo(DispatchMode.MULTIPLEXED)
    }

    @Test fun `selecting gateway falls back after failures`() {
        val failing = object : JobGateway {
            override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload) = false
            override fun cancelAll() {}
        }
        val fallback = FakeJobGateway()
        val sel = SelectingJobGateway(failing, fallback, conformance())
        repeat(3) { sel.enqueue(HostJobClass.DEFAULT, WorkItemPayload("w$it", 1)) }
        sel.enqueue(HostJobClass.DEFAULT, WorkItemPayload("w9", 1))
        assertThat(fallback.enqueued.map { it.second.workId }).containsExactly("w9")
    }
}
