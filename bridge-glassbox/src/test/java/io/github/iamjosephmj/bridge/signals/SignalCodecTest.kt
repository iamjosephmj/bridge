package io.github.iamjosephmj.bridge.signals

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalCodecTest {

    private fun roundTrip(v: SignalValue) {
        val t = SignalTransition(SignalKind.DOZE, SignalValue.Unknown, v, 42L, Trigger.BROADCAST)
        assertEquals(t, SignalCodec.decode(SignalCodec.encode(t)))
    }

    @Test fun `round-trips every SignalValue case`() {
        roundTrip(SignalValue.Unknown)
        roundTrip(SignalValue.Flag(true))
        roundTrip(SignalValue.Bucket(40))
        roundTrip(SignalValue.Doze(DozeMode.DEEP))
        roundTrip(SignalValue.PendingReasons(listOf(1, 5)))
        roundTrip(SignalValue.Death(exitReason = 10, atMs = 999L))
    }

    @Test fun `garbage throws SerializationException`() {
        assertThrows(SerializationException::class.java) { SignalCodec.decode("{nope") }
    }
}
