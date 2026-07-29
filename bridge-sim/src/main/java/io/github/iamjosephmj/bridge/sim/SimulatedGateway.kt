package io.github.iamjosephmj.bridge.sim

import io.github.iamjosephmj.bridge.dispatch.HostJobClass
import io.github.iamjosephmj.bridge.dispatch.JobGateway
import io.github.iamjosephmj.bridge.dispatch.WorkItemPayload
import io.github.iamjosephmj.bridge.signals.DozeMode
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.EventJournal
import io.github.iamjosephmj.bridge.store.RunState
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
        if (state.generation != p.generation) return@filter false
        if (state.runState !in setOf(RunState.ENQUEUED, RunState.DISPATCHED)) return@filter false

        if ((timeline.valueAt(SignalKind.BG_RESTRICTED, atMs) as? SignalValue.Flag)?.on == true)
            return@filter false

        val doze = timeline.valueAt(SignalKind.DOZE, atMs)
        val inMaintenance =
            (timeline.valueAt(SignalKind.MAINTENANCE_WINDOW, atMs) as? SignalValue.Flag)?.on == true
        if (doze is SignalValue.Doze && doze.mode == DozeMode.DEEP && !inMaintenance)
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
                it.stopReason != io.github.iamjosephmj.bridge.exec.STOP_REASON_PARKED) ||
                it is WorkEvent.Died }
        val lastStart = evs.indexOfLast { it is WorkEvent.Started }
        if (lastCrash > lastStart && atMs < evs[lastCrash].at + crashBackoffMs)
            return@filter false

        true
    }
}
