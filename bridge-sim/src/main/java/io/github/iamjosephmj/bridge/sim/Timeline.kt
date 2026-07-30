package io.github.iamjosephmj.bridge.sim

import io.github.iamjosephmj.bridge.signals.DozeMode
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue

/** Millisecond helpers for scenario scripts. */
val Int.h: Long get() = this * 3_600_000L
val Int.min: Long get() = this * 60_000L

/**
 * Scripted signal values over simulated time. Keys are [SignalKind]s plus the two
 * execution-constraint gates ("charging", "unmetered") that are not platform signals.
 */
class Timeline {
    private val tracks = mutableMapOf<String, MutableList<Pair<Long, SignalValue>>>()

    fun set(key: String, value: SignalValue, atMs: Long) {
        tracks.getOrPut(key) { mutableListOf() }.apply { add(atMs to value); sortBy { it.first } }
    }

    fun set(kind: SignalKind, value: SignalValue, atMs: Long) = set(kind.name, value, atMs)

    fun valueAt(key: String, atMs: Long): SignalValue? =
        tracks[key]?.lastOrNull { it.first <= atMs }?.second

    /** Time of the latest [set] on [key] at or before [atMs]; null when never set. */
    fun lastSetAt(key: String, atMs: Long): Long? =
        tracks[key]?.lastOrNull { it.first <= atMs }?.first

    fun valueAt(kind: SignalKind, atMs: Long): SignalValue =
        valueAt(kind.name, atMs) ?: defaultFor(kind)

    fun chargingAt(atMs: Long): Boolean =
        (valueAt("charging", atMs) as? SignalValue.Flag)?.on ?: false

    fun unmeteredAt(atMs: Long): Boolean =
        (valueAt("unmetered", atMs) as? SignalValue.Flag)?.on ?: true

    private fun defaultFor(kind: SignalKind): SignalValue = when (kind) {
        SignalKind.DOZE -> SignalValue.Doze(DozeMode.NONE)
        SignalKind.STANDBY_BUCKET -> SignalValue.Bucket(10)   // ACTIVE
        SignalKind.BG_RESTRICTED, SignalKind.DATA_SAVER,
        SignalKind.MAINTENANCE_WINDOW -> SignalValue.Flag(false)
        SignalKind.NETWORK_VALIDATED -> SignalValue.Flag(true)
        SignalKind.BATT_OPT_EXEMPT -> SignalValue.Flag(false)
        SignalKind.PENDING_REASONS, SignalKind.PROCESS_DEATH,
        SignalKind.THERMAL, SignalKind.CHARGE_TIME,
        SignalKind.THREAD_PRESSURE -> SignalValue.Unknown
    }
}
