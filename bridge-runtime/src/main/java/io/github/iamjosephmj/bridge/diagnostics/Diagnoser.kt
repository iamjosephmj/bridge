package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.signals.DozeMode
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSlice
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import io.github.iamjosephmj.bridge.store.WorkState

/**
 * Pure diagnosis rules: no Android imports, fully JVM-testable. Ordered matchers,
 * most-authoritative first — the first hit is the primary diagnosis, later hits contribute.
 */
object Diagnoser {

    fun diagnose(
        state: WorkState?,
        events: List<WorkEvent>,
        snapshot: SignalSnapshot,
        slice: SignalSlice?,
    ): Verdict? {
        if (state == null) return null

        val evidence = snapshot.values.map { (k, v) ->
            Evidence(k, v, snapshot.at, io.github.iamjosephmj.bridge.signals.Trigger.DIAGNOSIS)
        } + (slice?.transitions?.map { Evidence(it.kind, it.to, it.at, it.trigger) } ?: emptyList())
        val notes = if (slice == null) listOf("SignalHistoryUnavailable") else emptyList()
        val pendingSince = events.filterIsInstance<WorkEvent.Enqueued>()
            .lastOrNull { it.generation == state.generation }?.at

        // Terminal / running states: honest short-circuit.
        when (state.runState) {
            RunState.RUNNING -> return Verdict(state.workId, state.runState, Diagnosis.Running,
                emptyList(), evidence, Basis.INFERRED, pendingSince, notes)
            RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED ->
                return Verdict(state.workId, state.runState, Diagnosis.Finished,
                    emptyList(), evidence, Basis.INFERRED, pendingSince, notes)
            RunState.ENQUEUED, RunState.DISPATCHED -> Unit
        }

        val matches = mutableListOf<Pair<Diagnosis, Basis>>()

        // 1. Platform-reported reasons trump everything.
        val reported = snapshot.values[SignalKind.PENDING_REASONS]
        if (reported is SignalValue.PendingReasons && reported.reasons.isNotEmpty()) {
            for (r in reported.reasons) matches += PlatformPendingReasons.map(r, snapshot) to Basis.REPORTED
        }

        // 2. Bridge's own held decisions from the journal.
        val lastPolicy = events.lastOrNull { it is WorkEvent.PolicyDecision }
            as? WorkEvent.PolicyDecision
        if (lastPolicy != null && events.last() === lastPolicy &&
            (lastPolicy.decision == "hold" || lastPolicy.decision == "shed")) {
            matches += Diagnosis.HeldByPolicy(lastPolicy.why) to Basis.INFERRED
        }
        // M5: a park newer than the newest start means the durable block is waiting.
        if (lastPolicy != null && lastPolicy.decision == "park" &&
            events.indexOf(lastPolicy as WorkEvent) >
                events.indexOfLast { it is WorkEvent.Started }) {
            matches += Diagnosis.DurableParked(lastPolicy.why) to Basis.INFERRED
        }
        val genEvents = events.filter {
            it !is WorkEvent.Enqueued || it.generation == state.generation }
        val crashes = genEvents.count { it is WorkEvent.Died } +
            genEvents.count { it is WorkEvent.Stopped && it.stopReason == 0 /* retry */ }
        // "Between attempts" = the latest crash comes after the latest start; re-dispatch
        // records (Dispatched/PolicyDecision) after the crash don't clear the throttle.
        val lastStart = genEvents.indexOfLast { it is WorkEvent.Started }
        val lastCrash = genEvents.indexOfLast { it is WorkEvent.Stopped || it is WorkEvent.Died }
        if (crashes >= 2 && lastCrash > lastStart) {
            matches += Diagnosis.ThrottledAfterCrashes(crashes) to Basis.INFERRED
        }
        // 3. Device-state inference, most-blocking first.
        val bg = snapshot.values[SignalKind.BG_RESTRICTED]
        if (bg == SignalValue.Flag(true)) matches += Diagnosis.BackgroundRestricted to Basis.INFERRED
        val saver = snapshot.values[SignalKind.DATA_SAVER]
        if (saver == SignalValue.Flag(true) && state.requiresUnmetered) {
            matches += Diagnosis.DataSaverBlocked to Basis.INFERRED
        }
        val doze = snapshot.values[SignalKind.DOZE]
        if (doze is SignalValue.Doze && doze.mode != DozeMode.NONE) {
            matches += Diagnosis.DeferredByDoze(doze.mode == DozeMode.DEEP) to Basis.INFERRED
        }
        val bucket = snapshot.values[SignalKind.STANDBY_BUCKET]
        if (bucket is SignalValue.Bucket && bucket.bucket >= 30) {   // FREQUENT or worse
            matches += Diagnosis.DeferredByStandbyBucket(bucket.bucket) to Basis.INFERRED
        }
        if (state.requiresCharging) matches += Diagnosis.AwaitingConstraint("charging") to Basis.INFERRED
        if (state.requiresUnmetered) matches += Diagnosis.AwaitingConstraint("unmetered") to Basis.INFERRED
        if (state.requiresNetwork) matches += Diagnosis.AwaitingConstraint("network") to Basis.INFERRED
        if (state.requiresBatteryNotLow) matches += Diagnosis.AwaitingConstraint("battery-not-low") to Basis.INFERRED
        if (state.requiresStorageNotLow) matches += Diagnosis.AwaitingConstraint("storage-not-low") to Basis.INFERRED
        if (state.requiresDeviceIdle) matches += Diagnosis.AwaitingConstraint("device-idle") to Basis.INFERRED

        // 4. Weakest inference: enqueued but never handed to the scheduler this generation.
        val dispatchedThisGen = events.any {
            it is WorkEvent.Dispatched && it.generation == state.generation }
        if (state.runState == RunState.ENQUEUED && !dispatchedThisGen) {
            matches += Diagnosis.NotDispatched("gateway rejected or not yet dispatched") to Basis.INFERRED
        }

        val primary = matches.firstOrNull()
            ?: (Diagnosis.Unexplained("no explaining signal") to Basis.INFERRED)
        return Verdict(
            workId = state.workId, state = state.runState,
            diagnosis = primary.first,
            contributing = matches.drop(1).map { it.first }.distinct(),
            evidence = evidence,
            basis = primary.second,
            pendingSinceMs = pendingSince,
            notes = notes,
        )
    }

}
