package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.signals.Trigger

sealed interface Diagnosis {
    data class DeferredByStandbyBucket(val bucket: Int) : Diagnosis
    data class DeferredByDoze(val deep: Boolean) : Diagnosis
    object BackgroundRestricted : Diagnosis { override fun toString() = "BackgroundRestricted" }
    object DataSaverBlocked : Diagnosis { override fun toString() = "DataSaverBlocked" }
    data class AwaitingConstraint(val constraint: String) : Diagnosis
    object AwaitingConformanceFallback : Diagnosis { override fun toString() = "AwaitingConformanceFallback" }
    data class ThrottledAfterCrashes(val crashes: Int) : Diagnosis
    /** Bridge's own L4 judgment held/shed this work; `why` is the journaled arithmetic. */
    data class HeldByPolicy(val why: String) : Diagnosis
    /** M5: a durable block is parked on a timer/await — waiting, not stuck. */
    data class DurableParked(val why: String) : Diagnosis
    data class NotDispatched(val reason: String) : Diagnosis
    object Running : Diagnosis { override fun toString() = "Running" }
    object Finished : Diagnosis { override fun toString() = "Finished" }
    data class Unexplained(val note: String) : Diagnosis
}

enum class Basis { REPORTED, INFERRED }

data class Evidence(
    val kind: SignalKind,
    val value: SignalValue,
    val at: Long,
    val trigger: Trigger,
)

/**
 * JobScheduler PENDING_JOB_REASON_* constants (API 34+), centralized. CONSTRAINT_CHARGING
 * and DEVICE_STATE were verified on a Pixel 6 Pro / API 36.
 */
object PlatformPendingReasons {
    const val APP_STANDBY = 2
    const val BACKGROUND_RESTRICTION = 3
    const val CONSTRAINT_CHARGING = 5
    const val CONSTRAINT_CONNECTIVITY = 6
    const val CONSTRAINT_DEVICE_IDLE = 8
    const val DEVICE_STATE = 12

    fun map(reason: Int, snapshot: io.github.iamjosephmj.bridge.signals.SignalSnapshot): Diagnosis = when (reason) {
        APP_STANDBY -> {
            val b = snapshot.values[SignalKind.STANDBY_BUCKET]
            Diagnosis.DeferredByStandbyBucket(if (b is SignalValue.Bucket) b.bucket else -1)
        }
        BACKGROUND_RESTRICTION -> Diagnosis.BackgroundRestricted
        CONSTRAINT_CHARGING -> Diagnosis.AwaitingConstraint("charging")
        CONSTRAINT_CONNECTIVITY -> Diagnosis.AwaitingConstraint("unmetered")
        CONSTRAINT_DEVICE_IDLE -> Diagnosis.AwaitingConstraint("device-idle")
        DEVICE_STATE -> {
            val d = snapshot.values[SignalKind.DOZE]
            Diagnosis.DeferredByDoze(d is SignalValue.Doze &&
                d.mode == io.github.iamjosephmj.bridge.signals.DozeMode.DEEP)
        }
        else -> Diagnosis.NotDispatched("platform reason $reason")
    }
}
