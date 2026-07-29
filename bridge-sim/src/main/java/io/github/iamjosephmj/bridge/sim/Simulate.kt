package io.github.iamjosephmj.bridge.sim

import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.WorkRequest
import io.github.iamjosephmj.bridge.signals.DozeMode
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue

/** Standby-bucket constants mirrored from UsageStatsManager so scenarios read naturally. */
object Buckets {
    const val ACTIVE = 10
    const val WORKING_SET = 20
    const val FREQUENT = 30
    const val RARE = 40
    const val RESTRICTED = 45
}

class SimScope internal constructor(internal val device: SimulatedDevice) {
    private val timeline get() = device.timeline

    fun worker(name: String, factory: () -> BridgeWorker) = device.worker(name, factory)

    fun bucket(bucket: Int, atMs: Long = 0L) =
        timeline.set(SignalKind.STANDBY_BUCKET, SignalValue.Bucket(bucket), atMs)

    /**
     * Deep (or light) doze from..until. Maintenance windows of [maintenanceLenMs] open every
     * [maintenanceEveryMs] inside the doze span — during them the gateway may run work.
     */
    fun doze(
        fromMs: Long, untilMs: Long, deep: Boolean = true,
        maintenanceEveryMs: Long = 0L, maintenanceLenMs: Long = 10.min,
    ) {
        val mode = if (deep) DozeMode.DEEP else DozeMode.LIGHT
        timeline.set(SignalKind.DOZE, SignalValue.Doze(mode), fromMs)
        timeline.set(SignalKind.DOZE, SignalValue.Doze(DozeMode.NONE), untilMs)
        if (maintenanceEveryMs > 0) {
            var w = fromMs + maintenanceEveryMs
            while (w < untilMs) {
                timeline.set(SignalKind.MAINTENANCE_WINDOW, SignalValue.Flag(true), w)
                timeline.set(SignalKind.MAINTENANCE_WINDOW, SignalValue.Flag(false),
                    minOf(w + maintenanceLenMs, untilMs))
                w += maintenanceEveryMs
            }
        }
    }

    fun dataSaver(on: Boolean, fromMs: Long = 0L) =
        timeline.set(SignalKind.DATA_SAVER, SignalValue.Flag(on), fromMs)

    fun bgRestricted(on: Boolean, fromMs: Long = 0L) =
        timeline.set(SignalKind.BG_RESTRICTED, SignalValue.Flag(on), fromMs)

    fun charging(on: Boolean, fromMs: Long = 0L) =
        timeline.set("charging", SignalValue.Flag(on), fromMs)

    /** PowerManager thermal status level (0 NONE … 3 SEVERE …). */
    fun thermal(status: Int, fromMs: Long = 0L) =
        timeline.set(SignalKind.THERMAL, SignalValue.Count(status), fromMs)

    fun unmetered(on: Boolean, fromMs: Long = 0L) =
        timeline.set("unmetered", SignalValue.Flag(on), fromMs)

    fun enqueue(request: WorkRequest): SimHandle = device.enqueue(request)

    fun advanceTo(ms: Long) = device.advanceTo(ms)
}

fun simulate(block: SimScope.() -> Unit) {
    SimScope(SimulatedDevice()).block()
}
