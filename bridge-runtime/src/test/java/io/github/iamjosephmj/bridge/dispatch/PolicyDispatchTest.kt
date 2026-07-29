package io.github.iamjosephmj.bridge.dispatch

import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.policy.PolicyEngine
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.InMemoryJournal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyDispatchTest {

    private val clock = FakeClock(1000L)
    private val journal = InMemoryJournal()
    private val gateway = FakeJobGateway()
    private val alarms = FakeAlarmGateway()
    private var snapshot = SignalSnapshot(1000L, emptyMap())

    private fun dispatcher(apiLevel: Int = 34) = Dispatcher(journal, gateway, clock,
        policy = PolicyEngine(apiLevel), alarmGateway = alarms,
        snapshotProvider = { snapshot })

    private fun enqueue(importance: Int = 2, deadlineMs: Long = 0L, bytes: Long = 0L) {
        journal.append(WorkEvent.Enqueued("w", clock.now(), "worker", 1,
            importance = importance, estimatedUpBytes = bytes, deadlineMs = deadlineMs))
    }

    @Test fun `hold journals PolicyDecision and does not enqueue`() {
        snapshot = SignalSnapshot(1000L, mapOf(SignalKind.THERMAL to SignalValue.Count(3)))
        enqueue()
        dispatcher().dispatch("w")
        assertTrue(gateway.enqueued.isEmpty())
        val last = journal.events("w").last() as WorkEvent.PolicyDecision
        assertEquals("hold", last.decision)
        assertEquals(RunState.ENQUEUED, journal.state("w")!!.runState)
    }

    @Test fun `identical consecutive hold is journaled once`() {
        snapshot = SignalSnapshot(1000L, mapOf(SignalKind.THERMAL to SignalValue.Count(3)))
        enqueue()
        val d = dispatcher()
        d.dispatch("w"); d.dispatchAll(); d.dispatchAll()
        assertEquals(1, journal.events("w").count { it is WorkEvent.PolicyDecision })
    }

    @Test fun `held work dispatches once the condition clears`() {
        snapshot = SignalSnapshot(1000L, mapOf(SignalKind.THERMAL to SignalValue.Count(3)))
        enqueue()
        val d = dispatcher()
        d.dispatch("w")
        assertTrue(gateway.enqueued.isEmpty())
        snapshot = SignalSnapshot(2000L, emptyMap())
        d.dispatchAll()
        assertEquals("w", gateway.enqueued.single().second.workId)
        assertEquals(RunState.DISPATCHED, journal.state("w")!!.runState)
    }

    @Test fun `shed journals with why`() {
        snapshot = SignalSnapshot(1000L, mapOf(SignalKind.STANDBY_BUCKET to SignalValue.Bucket(30)))
        enqueue(importance = 1)
        dispatcher().dispatch("w")
        val last = journal.events("w").last() as WorkEvent.PolicyDecision
        assertEquals("shed", last.decision)
        assertTrue(last.why.contains("quota"))
    }

    @Test fun `alarm-tier escalation schedules the while-idle alarm`() {
        enqueue(deadlineMs = 10_000L)
        clock.nowMs = 9_500L
        dispatcher().dispatch("w")
        assertEquals(1, alarms.scheduled.size)
        assertEquals("w", alarms.scheduled[0].second)
        // Alarm never lands before now
        assertTrue(alarms.scheduled[0].first >= 9_500L)
        // Work still dispatched to the scheduler tier too
        assertEquals(HostJobClass.EXPEDITED, gateway.enqueued.single().first)
    }

    @Test fun `escalated tier is journaled`() {
        enqueue(deadlineMs = 10_000L)
        clock.nowMs = 8_000L        // 20% remaining → EXPEDITED
        dispatcher().dispatch("w")
        val pd = journal.events("w").filterIsInstance<WorkEvent.PolicyDecision>().single()
        assertEquals("admit:EXPEDITED", pd.decision)
        val dispatched = journal.events("w").filterIsInstance<WorkEvent.Dispatched>().single()
        assertEquals("EXPEDITED", dispatched.hostClass)
    }

    @Test fun `no policy engine - M1 behavior unchanged`() {
        enqueue()
        Dispatcher(journal, gateway, clock).dispatch("w")
        assertEquals(HostJobClass.NO_NETWORK, gateway.enqueued.single().first)
        assertTrue(journal.events("w").none { it is WorkEvent.PolicyDecision })
    }
}
