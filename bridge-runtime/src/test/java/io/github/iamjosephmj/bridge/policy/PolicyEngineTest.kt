package io.github.iamjosephmj.bridge.policy

import io.github.iamjosephmj.bridge.dispatch.HostJobClass
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.WorkEvent
import io.github.iamjosephmj.bridge.store.foldWorkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    private val engine = PolicyEngine(apiLevel = 34)

    private fun snapshot(vararg values: Pair<SignalKind, SignalValue>) =
        SignalSnapshot(1000L, values.toMap())

    private fun events(importance: Int = 2, chunkCount: Int = 0, bytes: Long = 0L,
                       deadlineMs: Long = 0L, extra: List<WorkEvent> = emptyList()) = listOf(
        WorkEvent.Enqueued("w", 0L, "worker", 1, importance = importance,
            chunkCount = chunkCount, estimatedUpBytes = bytes, deadlineMs = deadlineMs)) + extra

    private fun decide(events: List<WorkEvent>, snap: SignalSnapshot, now: Long = 1000L) =
        engine.decide(foldWorkState(events)!!, events, snap, now)

    @Test fun `no signals, no deadline - admits default tier`() {
        assertEquals(Decision.Admit(HostJobClass.NO_NETWORK), decide(events(), snapshot()))
    }

    @Test fun `thermal SEVERE holds non-deadline work`() {
        val d = decide(events(), snapshot(SignalKind.THERMAL to SignalValue.Count(3)))
        assertTrue(d is Decision.Hold)
        assertTrue((d as Decision.Hold).why.contains("thermal"))
        assertEquals(1000L + PolicyEngine.THERMAL_RECHECK_MS, d.untilMs)
    }

    @Test fun `quota hold - long unchunked work in WORKING_SET, chunked bypasses`() {
        val demoted = snapshot(SignalKind.STANDBY_BUCKET to SignalValue.Bucket(20))
        // 900MB at ~1MB/s ≈ 15m > 10m window
        val hold = decide(events(bytes = 900_000_000L), demoted)
        assertTrue(hold is Decision.Hold)
        assertTrue((hold as Decision.Hold).why.contains("exceeds"))
        // chunked equivalent admits
        val chunked = decide(events(chunkCount = 40, bytes = 900_000_000L), demoted)
        assertTrue(chunked is Decision.Admit)
        // unknown duration admits
        val unknown = decide(events(), demoted)
        assertTrue(unknown is Decision.Admit)
    }

    @Test fun `quota hold uses ledger history over byte estimate`() {
        val demoted = snapshot(SignalKind.STANDBY_BUCKET to SignalValue.Bucket(20))
        val history = listOf<WorkEvent>(
            WorkEvent.Started("w", 10L, 1, 1),
            WorkEvent.Finished("w", 10L + 12 * 60_000L, success = true),  // 12m run
            WorkEvent.Enqueued("w", 2000L, "worker", 2, importance = 2))
        val evs = events() + history
        val d = engine.decide(foldWorkState(evs)!!, evs, demoted, now = 3000L)
        assertTrue("expected Hold, got $d", d is Decision.Hold)
        assertTrue((d as Decision.Hold).why.contains("12m"))
    }

    @Test fun `shed matrix - LOW importance in FREQUENT sheds, DEFAULT does not`() {
        val frequent = snapshot(SignalKind.STANDBY_BUCKET to SignalValue.Bucket(30))
        assertTrue(decide(events(importance = 1), frequent) is Decision.Shed)
        assertTrue(decide(events(importance = 0), frequent) is Decision.Shed)
        assertTrue(decide(events(importance = 2), frequent) is Decision.Admit)
        // LOW in WORKING_SET (below FREQUENT) does not shed
        val ws = snapshot(SignalKind.STANDBY_BUCKET to SignalValue.Bucket(20))
        assertTrue(decide(events(importance = 1), ws) is Decision.Admit)
    }

    @Test fun `deadline escalation walks tiers by remaining fraction`() {
        val evs = events(deadlineMs = 10_000L)   // enqueued at 0, deadline 10s
        fun tierAt(now: Long) = (decide(evs, snapshot(), now) as Decision.Admit)
        assertEquals(HostJobClass.NO_NETWORK, tierAt(2_000L).tier)       // 80% left: base tier
        assertEquals("deadline < 50% remaining", tierAt(6_000L).why)     // 40% left
        assertEquals(HostJobClass.EXPEDITED, tierAt(8_000L).tier)        // 20% left
        val alarm = tierAt(9_500L)                                       // 5% left
        assertEquals(PolicyEngine.ESCALATE_ALARM_WHY, alarm.why)
    }

    @Test fun `sub-31 skips EXPEDITED and says so`() {
        val engine26 = PolicyEngine(apiLevel = 26)
        val evs = events(deadlineMs = 10_000L)
        val d = engine26.decide(foldWorkState(evs)!!, evs, snapshot(), now = 8_000L)
        d as Decision.Admit
        assertEquals(HostJobClass.NO_NETWORK, d.tier)   // mid tier keeps the base shape
        assertTrue(d.why!!.contains("skip:EXPEDITED"))
    }

    @Test fun `deadline work ignores thermal hold`() {
        val d = decide(events(deadlineMs = 10_000L),
            snapshot(SignalKind.THERMAL to SignalValue.Count(4)), now = 2_000L)
        assertTrue(d is Decision.Admit)
    }

    @Test fun `fail-open - poisoned snapshot admits default`() {
        val poisoned = SignalSnapshot(1000L, object : Map<SignalKind, SignalValue> by emptyMap() {
            override fun get(key: SignalKind): SignalValue = throw IllegalStateException("boom")
        })
        val d = engine.decide(foldWorkState(events())!!, events(), poisoned, 1000L)
        assertEquals(Decision.Admit(HostJobClass.NO_NETWORK), d)
    }
}
