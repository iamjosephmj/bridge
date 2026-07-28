package io.github.iamjosephmj.bridge.store

import kotlinx.serialization.json.Json

object EventCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(event: WorkEvent): String = json.encodeToString(WorkEvent.serializer(), event)
    fun decode(raw: String): WorkEvent = json.decodeFromString(WorkEvent.serializer(), raw)
}
