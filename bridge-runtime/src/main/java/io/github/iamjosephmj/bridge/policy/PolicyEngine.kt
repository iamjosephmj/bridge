package io.github.iamjosephmj.bridge.policy

import io.github.iamjosephmj.bridge.dispatch.HostJobClass
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSlice
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.WorkEvent
import io.github.iamjosephmj.bridge.store.WorkState

/** Process thread pressure, classified from runnable threads relative to cores. */
enum class PressureLevel { LOW, MEDIUM, HIGH }

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
class PolicyEngine(
    private val apiLevel: Int,
    private val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
) {

    fun decide(state: WorkState, events: List<WorkEvent>,
               snapshot: SignalSnapshot, now: Long,
               history: SignalSlice? = null): Decision =
        try { decideOrThrow(state, events, snapshot, now, history) }
        catch (_: Exception) { Decision.Admit(HostJobClass.forWork(state)) }

    private fun decideOrThrow(state: WorkState, events: List<WorkEvent>,
                              snapshot: SignalSnapshot, now: Long,
                              history: SignalSlice?): Decision {
        // M4 rhythm: with enough window history, holds land on the predicted next
        // maintenance window instead of a fixed recheck.
        val predictedWindow = history?.let { RhythmModel.predictNextMaintenance(it, now) }
        // 1. Thermal admission: SEVERE(3)+ holds everything non-deadline.
        val thermal = snapshot.values[SignalKind.THERMAL]
        if (thermal is SignalValue.Count && thermal.value >= THERMAL_SEVERE &&
            state.deadlineMs == 0L) {
            return Decision.Hold(predictedWindow ?: (now + THERMAL_RECHECK_MS),
                "thermal status ${thermal.value} >= SEVERE($THERMAL_SEVERE)")
        }

        // 2. Thread-pressure admission, tiered: MEDIUM defers MIN/LOW work, HIGH also
        //    defers DEFAULT; Importance.HIGH and deadline work always run. A request's
        //    maxThreadPressure(tolerance) overrides the importance mapping in either
        //    direction. Pressure is transient, so the recheck is short and there is no Shed.
        val pressure = snapshot.values[SignalKind.THREAD_PRESSURE]
        if (pressure is SignalValue.Count && state.deadlineMs == 0L) {
            val level = pressureLevel(pressure.value, cpuCores)
            val defers = if (state.maxPressure >= 0) level.ordinal > state.maxPressure
            else when (level) {
                PressureLevel.LOW -> false
                PressureLevel.MEDIUM -> state.importance <= IMPORTANCE_LOW
                PressureLevel.HIGH -> state.importance <= IMPORTANCE_DEFAULT
            }
            if (defers) return Decision.Hold(now + PRESSURE_RECHECK_MS,
                "thread pressure $level (runnable ${pressure.value} / $cpuCores cores) — " +
                    if (state.maxPressure >= 0)
                        "request tolerates <= ${PressureLevel.entries[state.maxPressure]}"
                    else "deferring importance ${state.importance} work")
        }

        // 3. Quota admission (labeled heuristic — see M3 spec §1): demoted buckets get
        //    ~10min windows; un-chunked work estimated longer than a window is held.
        val bucket = (snapshot.values[SignalKind.STANDBY_BUCKET] as? SignalValue.Bucket)?.bucket ?: 10
        if (bucket >= BUCKET_WORKING_SET && state.chunkCount == 0) {
            val estimate = estimateDurationMs(state, events)
            if (estimate != null && estimate > QUOTA_WINDOW_MS) {
                return Decision.Hold(predictedWindow ?: (now + QUOTA_RECHECK_MS),
                    "estimated ${estimate / 60_000}m exceeds ~${QUOTA_WINDOW_MS / 60_000}m " +
                        "bucket window (bucket $bucket)")
            }
        }

        // 4. Quota budgeting: in FREQUENT or worse, spend quota on higher-value work first.
        if (bucket >= BUCKET_FREQUENT && state.importance <= IMPORTANCE_LOW) {
            return Decision.Shed(
                "bucket $bucket >= FREQUENT, importance ${state.importance} <= LOW — " +
                    "quota reserved for higher-value work")
        }

        // 5. Deadline escalation.
        if (state.deadlineMs > 0L) {
            val enqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>()
                .lastOrNull { it.generation == state.generation }?.at ?: now
            val total = (state.deadlineMs - enqueuedAt).coerceAtLeast(1)
            val fraction = (state.deadlineMs - now).toDouble() / total
            val base = HostJobClass.forWork(state)
            // Mid-tier escalation only promotes DEFERRABLE work; other bases keep their
            // constraint shape (escalating NO_NETWORK to DEFAULT would ADD a constraint).
            val mid = if (base == HostJobClass.DEFERRABLE) HostJobClass.DEFAULT else base
            return when {
                fraction > 0.5 -> Decision.Admit(base)
                fraction > 0.25 -> Decision.Admit(mid, "deadline < 50% remaining")
                fraction > 0.10 ->
                    if (apiLevel >= 31) Decision.Admit(HostJobClass.EXPEDITED, "deadline < 25% remaining")
                    else Decision.Admit(mid,
                        "deadline < 25% remaining; skip:EXPEDITED (API $apiLevel < 31)")
                else -> Decision.Admit(HostJobClass.EXPEDITED.takeIf { apiLevel >= 31 }
                    ?: mid, ESCALATE_ALARM_WHY)
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
        const val PRESSURE_HIGH_FACTOR = 2       // runnable > cores × 2 → HIGH
        const val PRESSURE_RECHECK_MS = 60_000L

        /** LOW: runnable ≤ cores (normal parallelism). MEDIUM: ≤ cores × 2. HIGH: beyond. */
        fun pressureLevel(runnable: Int, cores: Int): PressureLevel = when {
            runnable <= cores -> PressureLevel.LOW
            runnable <= cores * PRESSURE_HIGH_FACTOR -> PressureLevel.MEDIUM
            else -> PressureLevel.HIGH
        }
        const val QUOTA_WINDOW_MS = 10 * 60_000L
        const val QUOTA_RECHECK_MS = 30 * 60_000L
        const val BUCKET_WORKING_SET = 20
        const val BUCKET_FREQUENT = 30
        const val IMPORTANCE_LOW = 1
        const val IMPORTANCE_DEFAULT = 2
        const val BYTES_PER_MS = 1_000L                       // ~1MB/s
        /** Marker why-string: dispatcher also schedules the alarm tier when it sees this. */
        const val ESCALATE_ALARM_WHY = "deadline < 10% remaining; escalate:ALARM"
    }
}
