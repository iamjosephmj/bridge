package io.github.iamjosephmj.bridge.sim

import io.github.iamjosephmj.bridge.dispatch.HostJobClass
import io.github.iamjosephmj.bridge.dispatch.JobGateway
import io.github.iamjosephmj.bridge.dispatch.WorkItemPayload
import io.github.iamjosephmj.bridge.signals.DozeMode
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.EventJournal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.StopReason
import io.github.iamjosephmj.bridge.store.WorkEvent

/**
 * JobGateway that parks payloads and releases them per a deliberately simple gating model
 * (see bridge-sim README): background-restricted blocks all; deep Doze blocks outside
 * maintenance windows; Data Saver blocks unmetered-constrained work; standby buckets delay
 * first dispatch by the platform's documented deferral floors; charging/unmetered gates
 * read the scripted timeline. This is a logic model, not a scheduler-fidelity claim.
 */
class SimulatedGateway(
    private val timeline: Timeline,
    private val journal: EventJournal,
) : JobGateway {
    private val parked = linkedMapOf<String, WorkItemPayload>()
    private val hostClasses = mutableMapOf<String, HostJobClass>()
    private val crashBackoffMs = 30 * 60_000L

    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        parked[payload.workId] = payload
        hostClasses[payload.workId] = hostClass
        return true
    }

    override fun cancelAll() { parked.clear() }

    fun remove(workId: String) { parked.remove(workId) }

    fun runnable(atMs: Long): List<WorkItemPayload> = parked.values.filter { p ->
        val state = journal.state(p.workId) ?: return@filter false
        if (state.generation != p.generation && state.periodicMs == 0L) return@filter false
        val terminalPeriodicDue = state.periodicMs > 0 &&
            state.runState in setOf(RunState.SUCCEEDED, RunState.FAILED) &&
            atMs >= (journal.events(p.workId)
                .lastOrNull { it is WorkEvent.Finished }?.at ?: 0L) + state.periodicMs
        if (state.runState !in setOf(RunState.ENQUEUED, RunState.DISPATCHED) &&
            !terminalPeriodicDue) return@filter false
        if (atMs < state.enqueuedAt + state.initialDelayMs) return@filter false

        // Content-trigger gate: runnable only after a scripted contentChanged on one of the
        // work's uris at or after enqueue — a change that predates the enqueue doesn't count,
        // exactly like the platform's per-schedule observer registration.
        if (state.contentUris.isNotEmpty()) {
            val triggered = state.contentUris.any { uri ->
                val key = "content:$uri"
                (timeline.valueAt(key, atMs) as? SignalValue.Flag)?.on == true &&
                    (timeline.lastSetAt(key, atMs) ?: Long.MIN_VALUE) >= state.enqueuedAt
            }
            if (!triggered) return@filter false
        }

        if ((timeline.valueAt(SignalKind.BG_RESTRICTED, atMs) as? SignalValue.Flag)?.on == true)
            return@filter false

        val doze = timeline.valueAt(SignalKind.DOZE, atMs)
        val dozeMode = (doze as? SignalValue.Doze)?.mode ?: DozeMode.NONE
        val inMaintenance =
            (timeline.valueAt(SignalKind.MAINTENANCE_WINDOW, atMs) as? SignalValue.Flag)?.on == true
        // Device-idle work is inverted: it runs ONLY while the device is idle.
        if (state.requiresDeviceIdle) {
            if (dozeMode == DozeMode.NONE) return@filter false
        } else if (dozeMode == DozeMode.DEEP && !inMaintenance) {
            return@filter false
        }

        if (state.requiresBatteryNotLow &&
            (timeline.valueAt("batteryLow", atMs) as? SignalValue.Flag)?.on == true)
            return@filter false
        if (state.requiresStorageNotLow &&
            (timeline.valueAt("storageLow", atMs) as? SignalValue.Flag)?.on == true)
            return@filter false
        if (state.requiresNetwork &&
            timeline.valueAt(SignalKind.NETWORK_VALIDATED, atMs) == SignalValue.Flag(false))
            return@filter false

        if (state.requiresUnmetered) {
            val saver = timeline.valueAt(SignalKind.DATA_SAVER, atMs)
            if ((saver as? SignalValue.Flag)?.on == true) return@filter false
            if (!timeline.unmeteredAt(atMs)) return@filter false
        }
        if (state.requiresCharging && !timeline.chargingAt(atMs)) return@filter false

        // Standby-bucket deferral floor from enqueue time (documented floors, not real heuristics).
        val enqueuedAt = journal.events(p.workId)
            .filterIsInstance<WorkEvent.Enqueued>()
            .lastOrNull { it.generation == state.generation }?.at ?: 0L
        // Expedited jobs get relaxed quota on the platform; the sim models that as a
        // bucket-floor bypass (fidelity disclaimer in README applies).
        val expedited = hostClasses[p.workId] == HostJobClass.EXPEDITED

        val bucket = (timeline.valueAt(SignalKind.STANDBY_BUCKET, atMs) as? SignalValue.Bucket)?.bucket ?: 10
        val floor = when {
            bucket >= 40 -> 24.h        // RARE
            bucket >= 30 -> 8.h         // FREQUENT
            bucket >= 20 -> 2.h         // WORKING_SET
            else -> 0L
        }
        if (!expedited && atMs < enqueuedAt + floor) return@filter false

        // Crash backoff: a crash newer than the newest start parks the item for 30 simulated
        // minutes — re-dispatch/policy records appended after the crash don't clear it.
        val evs = journal.events(p.workId)
        val lastCrash = evs.indexOfLast {
            (it is WorkEvent.Stopped &&
                StopReason.from(it.stopReason) != StopReason.PARKED) ||
                it is WorkEvent.Died }
        val lastStart = evs.indexOfLast { it is WorkEvent.Started }
        if (lastCrash > lastStart && atMs < evs[lastCrash].at + crashBackoffMs)
            return@filter false

        true
    }
}
