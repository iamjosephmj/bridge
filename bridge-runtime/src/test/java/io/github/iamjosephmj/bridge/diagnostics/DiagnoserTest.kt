package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.signals.DozeMode
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSlice
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.WorkEvent
import io.github.iamjosephmj.bridge.store.foldWorkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnoserTest {

    private val emptySlice = SignalSlice(emptyMap(), emptyList())

    private fun snapshot(vararg values: Pair<SignalKind, SignalValue>) =
        SignalSnapshot(at = 1000L, values = values.toMap())

    private fun enqueued(charging: Boolean = false, unmetered: Boolean = false) = listOf(
        WorkEvent.Enqueued("w", 100L, "worker", 1, importance = 2,
            requiresCharging = charging, requiresUnmetered = unmetered))

    private fun dispatched(events: List<WorkEvent>) =
        events + WorkEvent.Dispatched("w", 200L, "DEFAULT", 1)

    private fun diagnose(events: List<WorkEvent>, snap: SignalSnapshot,
                         slice: SignalSlice? = emptySlice) =
        Diagnoser.diagnose(foldWorkState(events), events, snap, slice)

    @Test fun `null state returns null`() {
        assertNull(Diagnoser.diagnose(null, emptyList(), snapshot(), emptySlice))
    }

    @Test fun `platform reported reason is primary with REPORTED basis`() {
        val v = diagnose(dispatched(enqueued()), snapshot(
            SignalKind.PENDING_REASONS to SignalValue.PendingReasons(
                listOf(PlatformPendingReasons.APP_STANDBY)),
            SignalKind.STANDBY_BUCKET to SignalValue.Bucket(40),
            SignalKind.DOZE to SignalValue.Doze(DozeMode.DEEP)))!!
        assertEquals(Diagnosis.DeferredByStandbyBucket(40), v.diagnosis)
        assertEquals(Basis.REPORTED, v.basis)
        // Doze inference still contributes
        assertTrue(v.contributing.any { it is Diagnosis.DeferredByDoze })
    }

    @Test fun `background restricted outranks doze outranks bucket`() {
        val v = diagnose(dispatched(enqueued()), snapshot(
            SignalKind.BG_RESTRICTED to SignalValue.Flag(true),
            SignalKind.DOZE to SignalValue.Doze(DozeMode.DEEP),
            SignalKind.STANDBY_BUCKET to SignalValue.Bucket(40)))!!
        assertEquals(Diagnosis.BackgroundRestricted, v.diagnosis)
        assertEquals(listOf(Diagnosis.DeferredByDoze(true), Diagnosis.DeferredByStandbyBucket(40)),
            v.contributing)
        assertEquals(Basis.INFERRED, v.basis)
    }

    @Test fun `data saver blocks only unmetered-constrained work`() {
        val saverOn = snapshot(SignalKind.DATA_SAVER to SignalValue.Flag(true))
        val unmetered = diagnose(dispatched(enqueued(unmetered = true)), saverOn)!!
        assertEquals(Diagnosis.DataSaverBlocked, unmetered.diagnosis)
        val plain = diagnose(dispatched(enqueued()), saverOn)!!
        assertTrue(plain.diagnosis is Diagnosis.Unexplained)
    }

    @Test fun `bucket FREQUENT or worse defers, ACTIVE does not`() {
        val rare = diagnose(dispatched(enqueued()),
            snapshot(SignalKind.STANDBY_BUCKET to SignalValue.Bucket(30)))!!
        assertEquals(Diagnosis.DeferredByStandbyBucket(30), rare.diagnosis)
        val active = diagnose(dispatched(enqueued()),
            snapshot(SignalKind.STANDBY_BUCKET to SignalValue.Bucket(10)))!!
        assertTrue(active.diagnosis is Diagnosis.Unexplained)
    }

    @Test fun `charging constraint reported when nothing blocks`() {
        val v = diagnose(dispatched(enqueued(charging = true)), snapshot())!!
        assertEquals(Diagnosis.AwaitingConstraint("charging"), v.diagnosis)
    }

    @Test fun `crash throttling detected from journal`() {
        val events = dispatched(enqueued()) +
            WorkEvent.Started("w", 300L, 1, 1) +
            WorkEvent.Stopped("w", 400L, 0) +
            WorkEvent.Started("w", 500L, 2, 1) +
            WorkEvent.Stopped("w", 600L, 0)
        val v = diagnose(events, snapshot())!!
        assertEquals(Diagnosis.ThrottledAfterCrashes(2), v.diagnosis)
    }

    @Test fun `undispatched work falls back to NotDispatched`() {
        val v = diagnose(enqueued(), snapshot())!!
        assertTrue(v.diagnosis is Diagnosis.NotDispatched)
    }

    @Test fun `running and finished are honest terminals`() {
        val running = dispatched(enqueued()) + WorkEvent.Started("w", 300L, 1, 1)
        assertEquals(Diagnosis.Running, diagnose(running, snapshot())!!.diagnosis)
        val done = running + WorkEvent.Finished("w", 400L, success = true)
        assertEquals(Diagnosis.Finished, diagnose(done, snapshot())!!.diagnosis)
    }

    @Test fun `null slice adds SignalHistoryUnavailable note`() {
        val v = diagnose(dispatched(enqueued()), snapshot(), slice = null)!!
        assertEquals(listOf("SignalHistoryUnavailable"), v.notes)
    }

    @Test fun `evidence carries all snapshot entries plus slice transitions`() {
        val slice = SignalSlice(emptyMap(), listOf(
            io.github.iamjosephmj.bridge.signals.SignalTransition(
                SignalKind.DOZE, SignalValue.Doze(DozeMode.NONE),
                SignalValue.Doze(DozeMode.DEEP), 500L,
                io.github.iamjosephmj.bridge.signals.Trigger.BROADCAST)))
        val v = diagnose(dispatched(enqueued()), snapshot(
            SignalKind.DOZE to SignalValue.Doze(DozeMode.DEEP),
            SignalKind.DATA_SAVER to SignalValue.Flag(false)), slice)!!
        assertEquals(3, v.evidence.size)
    }
}
