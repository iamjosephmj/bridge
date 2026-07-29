package io.github.iamjosephmj.bridge.policy

import io.github.iamjosephmj.bridge.dispatch.HostJobClass
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.WorkEvent
import io.github.iamjosephmj.bridge.store.WorkState

sealed interface Decision {
    /** Dispatch now with this tier. `why` is non-null only when the tier is non-default. */
    data class Admit(val tier: HostJobClass, val why: String? = null) : Decision
    data class Hold(val untilMs: Long, val why: String) : Decision
    data class Shed(val why: String) : Decision
}

/**
 * L4 judgment: pure functions from (state, journal, signals, now) to a decision.
 * No android imports — the simulator runs this class verbatim. Failures fail open
 * to Admit(default): the policy layer must never lose work.
 */
class PolicyEngine(private val apiLevel: Int) {

    fun decide(state: WorkState, events: List<WorkEvent>,
               snapshot: SignalSnapshot, now: Long): Decision =
        try { decideOrThrow(state, events, snapshot, now) }
        catch (_: Exception) { Decision.Admit(HostJobClass.forWork(state)) }

    private fun decideOrThrow(state: WorkState, events: List<WorkEvent>,
                              snapshot: SignalSnapshot, now: Long): Decision {
        // 1. Thermal admission: SEVERE(3)+ holds everything non-deadline.
        val thermal = snapshot.values[SignalKind.THERMAL]
        if (thermal is SignalValue.Count && thermal.value >= THERMAL_SEVERE &&
            state.deadlineMs == 0L) {
            return Decision.Hold(now + THERMAL_RECHECK_MS,
                "thermal status ${thermal.value} >= SEVERE($THERMAL_SEVERE)")
        }

        // 2. Quota admission (labeled heuristic — see M3 spec §1): demoted buckets get
        //    ~10min windows; un-chunked work estimated longer than a window is held.
        val bucket = (snapshot.values[SignalKind.STANDBY_BUCKET] as? SignalValue.Bucket)?.bucket ?: 10
        if (bucket >= BUCKET_WORKING_SET && state.chunkCount == 0) {
            val estimate = estimateDurationMs(state, events)
            if (estimate != null && estimate > QUOTA_WINDOW_MS) {
                return Decision.Hold(now + QUOTA_RECHECK_MS,
                    "estimated ${estimate / 60_000}m exceeds ~${QUOTA_WINDOW_MS / 60_000}m " +
                        "bucket window (bucket $bucket)")
            }
        }

        // 3. Quota budgeting: in FREQUENT or worse, spend quota on higher-value work first.
        if (bucket >= BUCKET_FREQUENT && state.importance <= IMPORTANCE_LOW) {
            return Decision.Shed(
                "bucket $bucket >= FREQUENT, importance ${state.importance} <= LOW — " +
                    "quota reserved for higher-value work")
        }

        // 4. Deadline escalation.
        if (state.deadlineMs > 0L) {
            val enqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>()
                .lastOrNull { it.generation == state.generation }?.at ?: now
            val total = (state.deadlineMs - enqueuedAt).coerceAtLeast(1)
            val fraction = (state.deadlineMs - now).toDouble() / total
            return when {
                fraction > 0.5 -> Decision.Admit(HostJobClass.forWork(state))
                fraction > 0.25 -> Decision.Admit(HostJobClass.DEFAULT, "deadline < 50% remaining")
                fraction > 0.10 ->
                    if (apiLevel >= 31) Decision.Admit(HostJobClass.EXPEDITED, "deadline < 25% remaining")
                    else Decision.Admit(HostJobClass.DEFAULT,
                        "deadline < 25% remaining; skip:EXPEDITED (API $apiLevel < 31)")
                else -> Decision.Admit(HostJobClass.EXPEDITED.takeIf { apiLevel >= 31 }
                    ?: HostJobClass.DEFAULT, ESCALATE_ALARM_WHY)
            }
        }

        return Decision.Admit(HostJobClass.forWork(state))
    }

    /** Mean of past run durations; else bytes at ~1MB/s; else null (admit-if-unknown). */
    private fun estimateDurationMs(state: WorkState, events: List<WorkEvent>): Long? {
        val durations = mutableListOf<Long>()
        var startAt: Long? = null
        for (e in events) when (e) {
            is WorkEvent.Started -> startAt = e.at
            is WorkEvent.Finished -> { startAt?.let { durations += e.at - it }; startAt = null }
            is WorkEvent.Stopped, is WorkEvent.Died -> startAt = null   // partial runs lie
            else -> Unit
        }
        if (durations.isNotEmpty()) return durations.average().toLong()
        if (state.estimatedUpBytes > 0) return state.estimatedUpBytes / BYTES_PER_MS
        return null
    }

    companion object {
        const val THERMAL_SEVERE = 3
        const val THERMAL_RECHECK_MS = 15 * 60_000L
        const val QUOTA_WINDOW_MS = 10 * 60_000L
        const val QUOTA_RECHECK_MS = 30 * 60_000L
        const val BUCKET_WORKING_SET = 20
        const val BUCKET_FREQUENT = 30
        const val IMPORTANCE_LOW = 1
        const val BYTES_PER_MS = 1_000L                       // ~1MB/s
        /** Marker why-string: dispatcher also schedules the alarm tier when it sees this. */
        const val ESCALATE_ALARM_WHY = "deadline < 10% remaining; escalate:ALARM"
    }
}
