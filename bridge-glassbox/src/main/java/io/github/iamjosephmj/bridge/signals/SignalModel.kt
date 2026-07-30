package io.github.iamjosephmj.bridge.signals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SignalKind {
    PENDING_REASONS, STANDBY_BUCKET, BG_RESTRICTED, DATA_SAVER,
    DOZE, MAINTENANCE_WINDOW, NETWORK_VALIDATED, BATT_OPT_EXEMPT, PROCESS_DEATH,
    THERMAL,        // M3: PowerManager thermal status (29+)
    CHARGE_TIME,    // M3: BatteryManager.computeChargeTimeRemaining minutes (28+)
    THREAD_PRESSURE, // runnable threads of this process, from /proc/self/task (all APIs)
}

enum class DozeMode { NONE, LIGHT, DEEP }

/** What prompted an observation — recorded so late-noticed changes are honest about the gap. */
enum class Trigger { BASELINE, BROADCAST, SCHEDULING_DECISION, DIAGNOSIS }

@Serializable
sealed interface SignalValue {
    @Serializable @SerialName("unknown")
    object Unknown : SignalValue { override fun toString() = "Unknown" }

    @Serializable @SerialName("flag")
    data class Flag(val on: Boolean) : SignalValue

    /** UsageStatsManager STANDBY_BUCKET_* constant (10 ACTIVE … 45 RESTRICTED). */
    @Serializable @SerialName("bucket")
    data class Bucket(val bucket: Int) : SignalValue

    @Serializable @SerialName("doze")
    data class Doze(val mode: DozeMode) : SignalValue

    /** JobScheduler PENDING_JOB_REASON_* constants (API 34+). */
    @Serializable @SerialName("reasons")
    data class PendingReasons(val reasons: List<Int>) : SignalValue

    @Serializable @SerialName("death")
    data class Death(val exitReason: Int, val atMs: Long) : SignalValue

    /** Generic integer signal (thermal status level, minutes of charge remaining, …). */
    @Serializable @SerialName("count")
    data class Count(val value: Int) : SignalValue
}

@Serializable
data class SignalTransition(
    val kind: SignalKind,
    val from: SignalValue,
    val to: SignalValue,
    val at: Long,
    val trigger: Trigger,
)

data class SignalSnapshot(val at: Long, val values: Map<SignalKind, SignalValue>)

/** Signal history over an interval: state entering it plus every transition inside it. */
data class SignalSlice(
    val baseline: Map<SignalKind, SignalValue>,
    val transitions: List<SignalTransition>,
)
