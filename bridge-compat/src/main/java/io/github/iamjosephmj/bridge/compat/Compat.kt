package io.github.iamjosephmj.bridge.compat

/**
 * androidx.work-shaped façade over Bridge (parent design §4.6 tier 1). Migration is an
 * import change for the covered surface: Worker/Result, OneTimeWorkRequest + Constraints
 * (charging / unmetered network), enqueueUniqueWork (KEEP|REPLACE), work state queries,
 * cancel, and sequential chains. Out of v0.4 scope (see M4 spec §1): periodic work,
 * Data payloads, tags, LiveData/Flow observers, multi-branch chains.
 */
abstract class Worker {
    abstract fun doWork(): Result

    sealed class Result {
        object Success : Result()
        object Retry : Result()
        object Failure : Result()
        companion object {
            @JvmStatic fun success(): Result = Success
            @JvmStatic fun retry(): Result = Retry
            @JvmStatic fun failure(): Result = Failure
        }
    }
}

enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED }

class Constraints private constructor(
    val requiresCharging: Boolean,
    val requiredNetworkType: NetworkType,
    val requiresBatteryNotLow: Boolean,
    val requiresStorageNotLow: Boolean,
    val requiresDeviceIdle: Boolean,
) {
    class Builder {
        private var charging = false
        private var network = NetworkType.NOT_REQUIRED
        private var batteryNotLow = false
        private var storageNotLow = false
        private var deviceIdle = false
        fun setRequiresCharging(value: Boolean) = apply { charging = value }
        fun setRequiredNetworkType(type: NetworkType) = apply { network = type }
        fun setRequiresBatteryNotLow(value: Boolean) = apply { batteryNotLow = value }
        fun setRequiresStorageNotLow(value: Boolean) = apply { storageNotLow = value }
        fun setRequiresDeviceIdle(value: Boolean) = apply { deviceIdle = value }
        fun build() = Constraints(charging, network, batteryNotLow, storageNotLow, deviceIdle)
    }
    companion object { val NONE = Builder().build() }
}

class OneTimeWorkRequest private constructor(
    val workerClass: Class<out Worker>,
    val constraints: Constraints,
    val initialDelayMs: Long,
) {
    class Builder(private val workerClass: Class<out Worker>) {
        private var constraints = Constraints.NONE
        private var initialDelayMs = 0L
        fun setConstraints(value: Constraints) = apply { constraints = value }
        fun setInitialDelay(ms: Long) = apply { require(ms > 0); initialDelayMs = ms }
        fun build() = OneTimeWorkRequest(workerClass, constraints, initialDelayMs)
    }
}

class PeriodicWorkRequest private constructor(
    val workerClass: Class<out Worker>,
    val constraints: Constraints,
    val intervalMs: Long,
) {
    class Builder(private val workerClass: Class<out Worker>, private val intervalMs: Long) {
        private var constraints = Constraints.NONE
        fun setConstraints(value: Constraints) = apply { constraints = value }
        fun build() = PeriodicWorkRequest(workerClass, constraints, intervalMs)
    }
}

enum class ExistingPeriodicWorkPolicy { KEEP, UPDATE }

enum class ExistingWorkPolicy { KEEP, REPLACE }

/** Mapped from Bridge's RunState; DISPATCHED reads as ENQUEUED, exactly like WorkManager. */
enum class WorkInfoState { ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
