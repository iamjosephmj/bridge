package io.github.iamjosephmj.bridge.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalLogTest {

    private fun t(kind: SignalKind, to: SignalValue, at: Long,
                  from: SignalValue = SignalValue.Unknown,
                  trigger: Trigger = Trigger.BROADCAST) =
        SignalTransition(kind, from, to, at, trigger)

    @Test fun `slice returns baseline from before window and transitions inside it`() {
        val log = SignalLog(InMemoryTransitionStore())
        log.append(t(SignalKind.STANDBY_BUCKET, SignalValue.Bucket(10), at = 100))
        log.append(t(SignalKind.STANDBY_BUCKET, SignalValue.Bucket(40), at = 200))
        log.append(t(SignalKind.DOZE, SignalValue.Doze(DozeMode.DEEP), at = 300))
        val slice = log.slice(250, 400)
        assertEquals(SignalValue.Bucket(40), slice.baseline[SignalKind.STANDBY_BUCKET])
        assertEquals(1, slice.transitions.size)
        assertEquals(SignalKind.DOZE, slice.transitions[0].kind)
    }

    @Test fun `entry budget breach folds oldest half into baselines`() {
        val log = SignalLog(InMemoryTransitionStore(), maxEntries = 100)
        for (i in 0 until 101) {
            log.append(t(SignalKind.DATA_SAVER, SignalValue.Flag(i % 2 == 0), at = i.toLong()))
        }
        val (count, _) = log.health()
        assertTrue("expected fold to shrink log, got $count", count <= 52)
        // Baseline survives the fold: state entering a late window is still resolvable
        val slice = log.slice(90, 101)
        assertTrue(slice.baseline.containsKey(SignalKind.DATA_SAVER))
        assertTrue(slice.transitions.isNotEmpty())
    }

    @Test fun `age budget breach folds`() {
        val dayMs = 24L * 60 * 60 * 1000
        val log = SignalLog(InMemoryTransitionStore(), maxAgeMs = 14 * dayMs)
        log.append(t(SignalKind.DOZE, SignalValue.Doze(DozeMode.LIGHT), at = 0))
        log.append(t(SignalKind.DOZE, SignalValue.Doze(DozeMode.NONE), at = 1 * dayMs))
        log.append(t(SignalKind.DOZE, SignalValue.Doze(DozeMode.DEEP), at = 20 * dayMs))
        val (_, oldest) = log.health()
        assertTrue("oldest entry should have been folded forward", oldest!! >= 1 * dayMs)
        // History older than horizon degrades to a baseline, not to nothing
        val slice = log.slice(19 * dayMs, 21 * dayMs)
        assertEquals(SignalValue.Doze(DozeMode.NONE), slice.baseline[SignalKind.DOZE])
    }

    @Test fun `corrupt payload is skipped not thrown`() {
        val store = InMemoryTransitionStore()
        store.append(5L, "{corrupt")
        val log = SignalLog(store)
        log.append(t(SignalKind.BG_RESTRICTED, SignalValue.Flag(true), at = 10))
        val slice = log.slice(0, 20)
        assertEquals(1, slice.transitions.size)
    }
}
