package io.github.iamjosephmj.bridge.compat

/**
 * androidx.work-shaped façade over Bridge (parent design §4.6 tier 1). Migration is an
 * import change for the covered surface: Worker/Result (with Data input/output), Data,
 * tags, OneTimeWorkRequest + Constraints, enqueueUniqueWork (KEEP|REPLACE), periodic
 * work, work state queries + Flow observers, cancel (by name and by tag), sequential
 * chains, and multi-branch chains via WorkContinuation.combine.
 */

/** androidx.work.Data shape: string-keyed payload with typed getters (parse-on-read). */
class Data internal constructor(internal val map: Map<String, String>) {
    fun getString(key: String): String? = map[key]
    fun getInt(key: String, defaultValue: Int): Int = map[key]?.toIntOrNull() ?: defaultValue
    fun getLong(key: String, defaultValue: Long): Long = map[key]?.toLongOrNull() ?: defaultValue
    fun getDouble(key: String, defaultValue: Double): Double =
        map[key]?.toDoubleOrNull() ?: defaultValue
    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        map[key]?.toBooleanStrictOrNull() ?: defaultValue

    override fun equals(other: Any?) = other is Data && other.map == map
    override fun hashCode() = map.hashCode()
    override fun toString() = "Data$map"

    class Builder {
        private val map = mutableMapOf<String, String>()
        fun putString(key: String, value: String?) = apply {
            if (value == null) map.remove(key) else map[key] = value }
        fun putInt(key: String, value: Int) = apply { map[key] = value.toString() }
        fun putLong(key: String, value: Long) = apply { map[key] = value.toString() }
        fun putDouble(key: String, value: Double) = apply { map[key] = value.toString() }
        fun putBoolean(key: String, value: Boolean) = apply { map[key] = value.toString() }
        fun putAll(data: Data) = apply { map += data.map }
        fun build() = Data(map.toMap())
    }

    companion object { @JvmField val EMPTY = Data(emptyMap()) }
}

fun workDataOf(vararg pairs: Pair<String, Any?>): Data =
    Data(buildMap { for ((k, v) in pairs) if (v != null) put(k, v.toString()) })

abstract class Worker {
    /** Set by the runner before doWork(): request input merged with upstream outputs. */
    var inputData: Data = Data.EMPTY
        internal set

    abstract fun doWork(): Result

    sealed class Result {
        class Success internal constructor(val outputData: Data) : Result()
        object Retry : Result()
        class Failure internal constructor(val outputData: Data) : Result()
        companion object {
            @JvmStatic fun success(): Result = Success(Data.EMPTY)
            @JvmStatic fun success(outputData: Data): Result = Success(outputData)
            @JvmStatic fun retry(): Result = Retry
            @JvmStatic fun failure(): Result = Failure(Data.EMPTY)
            @JvmStatic fun failure(outputData: Data): Result = Failure(outputData)
        }
    }
}

enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED }

/** androidx.work.Constraints.ContentUriTrigger shape: one watched uri + descendants flag. */
data class ContentUriTrigger(val uri: String, val triggerForDescendants: Boolean)

class Constraints private constructor(
    val requiresCharging: Boolean,
    val requiredNetworkType: NetworkType,
    val requiresBatteryNotLow: Boolean,
    val requiresStorageNotLow: Boolean,
    val requiresDeviceIdle: Boolean,
    val contentUriTriggers: List<ContentUriTrigger> = emptyList(),
    val contentTriggerUpdateDelayMs: Long = 0L,
    val contentTriggerMaxDelayMs: Long = 0L,
) {
    class Builder {
        private var charging = false
        private var network = NetworkType.NOT_REQUIRED
        private var batteryNotLow = false
        private var storageNotLow = false
        private var deviceIdle = false
        private val contentUriTriggers = mutableListOf<ContentUriTrigger>()
        private var contentUpdateDelayMs = 0L
        private var contentMaxDelayMs = 0L
        fun setRequiresCharging(value: Boolean) = apply { charging = value }
        fun setRequiredNetworkType(type: NetworkType) = apply { network = type }
        fun setRequiresBatteryNotLow(value: Boolean) = apply { batteryNotLow = value }
        fun setRequiresStorageNotLow(value: Boolean) = apply { storageNotLow = value }
        fun setRequiresDeviceIdle(value: Boolean) = apply { deviceIdle = value }
        fun addContentUriTrigger(uri: String, triggerForDescendants: Boolean) =
            apply { contentUriTriggers += ContentUriTrigger(uri, triggerForDescendants) }
        fun setTriggerContentUpdateDelay(ms: Long) = apply { require(ms > 0); contentUpdateDelayMs = ms }
        fun setTriggerContentMaxDelay(ms: Long) = apply { require(ms > 0); contentMaxDelayMs = ms }
        fun build() = Constraints(charging, network, batteryNotLow, storageNotLow, deviceIdle,
            contentUriTriggers.toList(), contentUpdateDelayMs, contentMaxDelayMs)
    }
    companion object { val NONE = Builder().build() }
}

class OneTimeWorkRequest private constructor(
    val workerClass: Class<out Worker>,
    val constraints: Constraints,
    val initialDelayMs: Long,
    val inputData: Data = Data.EMPTY,
    val tags: Set<String> = emptySet(),
) {
    class Builder(private val workerClass: Class<out Worker>) {
        private var constraints = Constraints.NONE
        private var initialDelayMs = 0L
        private var inputData = Data.EMPTY
        private val tags = mutableSetOf<String>()
        fun setConstraints(value: Constraints) = apply { constraints = value }
        fun setInitialDelay(ms: Long) = apply { require(ms > 0); initialDelayMs = ms }
        fun setInputData(value: Data) = apply { inputData = value }
        fun addTag(tag: String) = apply { tags += tag }
        fun build() = OneTimeWorkRequest(workerClass, constraints, initialDelayMs,
            inputData, tags.toSet())
    }
}

class PeriodicWorkRequest private constructor(
    val workerClass: Class<out Worker>,
    val constraints: Constraints,
    val intervalMs: Long,
    val inputData: Data = Data.EMPTY,
    val tags: Set<String> = emptySet(),
) {
    class Builder(private val workerClass: Class<out Worker>, private val intervalMs: Long) {
        private var constraints = Constraints.NONE
        private var inputData = Data.EMPTY
        private val tags = mutableSetOf<String>()
        fun setConstraints(value: Constraints) = apply { constraints = value }
        fun setInputData(value: Data) = apply { inputData = value }
        fun addTag(tag: String) = apply { tags += tag }
        fun build() = PeriodicWorkRequest(workerClass, constraints, intervalMs,
            inputData, tags.toSet())
    }
}

enum class ExistingPeriodicWorkPolicy { KEEP, UPDATE }

enum class ExistingWorkPolicy { KEEP, REPLACE }

/** Mapped from Bridge's RunState; DISPATCHED reads as ENQUEUED, exactly like WorkManager. */
enum class WorkInfoState { ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
