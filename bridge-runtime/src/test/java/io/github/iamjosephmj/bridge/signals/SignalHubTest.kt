package io.github.iamjosephmj.bridge.signals

import io.github.iamjosephmj.bridge.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalHubTest {

    private val clock = FakeClock(1000L)
    private val store = InMemoryTransitionStore()
    private val log = SignalLog(store)

    private fun transitions() = store.all().map { SignalCodec.decode(it.second) }

    @Test fun `first snapshot logs one baseline per source`() {
        val hub = SignalHub(listOf(
            FakeSignalSource(SignalKind.DOZE, SignalValue.Doze(DozeMode.NONE)),
            FakeSignalSource(SignalKind.DATA_SAVER, SignalValue.Flag(false))), log, clock)
        hub.snapshot(Trigger.SCHEDULING_DECISION)
        val ts = transitions()
        assertEquals(2, ts.size)
        assertEquals(setOf(Trigger.BASELINE), ts.map { it.trigger }.toSet())
        assertEquals(setOf(SignalValue.Unknown), ts.map { it.from }.toSet())
    }

    @Test fun `unchanged snapshot logs nothing, change logs one transition`() {
        val doze = FakeSignalSource(SignalKind.DOZE, SignalValue.Doze(DozeMode.NONE))
        val hub = SignalHub(listOf(doze), log, clock)
        hub.snapshot(Trigger.SCHEDULING_DECISION)   // baseline
        hub.snapshot(Trigger.SCHEDULING_DECISION)   // unchanged
        assertEquals(1, transitions().size)
        clock.advance(500)
        doze.value = SignalValue.Doze(DozeMode.DEEP)
        hub.snapshot(Trigger.BROADCAST)
        val ts = transitions()
        assertEquals(2, ts.size)
        assertEquals(SignalValue.Doze(DozeMode.NONE), ts[1].from)
        assertEquals(SignalValue.Doze(DozeMode.DEEP), ts[1].to)
        assertEquals(Trigger.BROADCAST, ts[1].trigger)
        assertEquals(1500L, ts[1].at)
    }

    @Test fun `throwing source degrades to Unknown without exception`() {
        val bad = object : SignalSource {
            override val kind = SignalKind.STANDBY_BUCKET
            override fun read(): SignalValue = throw IllegalStateException("OEM quirk")
        }
        val hub = SignalHub(listOf(bad), log, clock)
        val snap = hub.snapshot(Trigger.DIAGNOSIS)
        assertEquals(SignalValue.Unknown, snap.values[SignalKind.STANDBY_BUCKET])
    }
}
