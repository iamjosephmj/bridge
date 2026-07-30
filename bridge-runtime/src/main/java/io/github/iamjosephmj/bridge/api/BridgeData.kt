package io.github.iamjosephmj.bridge.api

/**
 * Small key-value payload attached to work. Journaled verbatim on the Enqueued event
 * (input) and the Finished event (output), so the ledger can show what every attempt
 * received and produced — not just the latest.
 *
 * Values are stored as strings; the typed getters parse on read and fall back to the
 * default on absence or parse failure. Total size (UTF-8 keys + values) is capped at
 * [MAX_BYTES], enforced at enqueue — the journal is for coordinates, not cargo.
 */
class BridgeData internal constructor(val asMap: Map<String, String>) {

    fun getString(key: String): String? = asMap[key]
    fun getInt(key: String, default: Int = 0): Int = asMap[key]?.toIntOrNull() ?: default
    fun getLong(key: String, default: Long = 0L): Long = asMap[key]?.toLongOrNull() ?: default
    fun getDouble(key: String, default: Double = 0.0): Double =
        asMap[key]?.toDoubleOrNull() ?: default
    fun getBoolean(key: String, default: Boolean = false): Boolean =
        asMap[key]?.toBooleanStrictOrNull() ?: default

    val size: Int get() = asMap.size
    fun isEmpty(): Boolean = asMap.isEmpty()

    /** Overwrite merge: keys in [other] win — the semantics chain outputs use. */
    operator fun plus(other: BridgeData): BridgeData = BridgeData(asMap + other.asMap)

    internal fun byteSize(): Int =
        asMap.entries.sumOf { it.key.toByteArray().size + it.value.toByteArray().size }

    override fun equals(other: Any?) = other is BridgeData && other.asMap == asMap
    override fun hashCode() = asMap.hashCode()
    override fun toString() = "BridgeData$asMap"

    companion object {
        val EMPTY = BridgeData(emptyMap())
        const val MAX_BYTES = 10_240
        /** Wraps an existing string map (used by adapters like bridge-compat). */
        fun of(map: Map<String, String>): BridgeData = BridgeData(map.toMap())
    }
}

/** Builds a [BridgeData]; null values are skipped, everything else is stored via toString(). */
fun bridgeDataOf(vararg pairs: Pair<String, Any?>): BridgeData =
    BridgeData(buildMap { for ((k, v) in pairs) if (v != null) put(k, v.toString()) })
