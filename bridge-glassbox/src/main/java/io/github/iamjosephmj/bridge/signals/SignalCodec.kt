package io.github.iamjosephmj.bridge.signals

import kotlinx.serialization.json.Json

object SignalCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true }

    fun encode(t: SignalTransition): String = json.encodeToString(SignalTransition.serializer(), t)
    fun decode(s: String): SignalTransition = json.decodeFromString(SignalTransition.serializer(), s)
}
