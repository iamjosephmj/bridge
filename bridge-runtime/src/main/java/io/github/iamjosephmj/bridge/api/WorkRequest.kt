package io.github.iamjosephmj.bridge.api

import io.github.iamjosephmj.bridge.policy.PressureLevel

enum class Importance { MIN, LOW, DEFAULT, HIGH }

/** Retry pacing between attempts, mapped to JobInfo.BACKOFF_POLICY_*. */
enum class BackoffPolicy { LINEAR, EXPONENTIAL }

class WorkRequest internal constructor(
    val name: String, val workerName: String, val importance: Importance,
    val requiresCharging: Boolean, val requiresUnmetered: Boolean,
    val chunkCount: Int, val estimatedUpBytes: Long, val maxAttempts: Int,
    val deadlineMs: Long = 0L,
    val requiresNetwork: Boolean = false,        // any connected network
    val requiresBatteryNotLow: Boolean = false,
    val requiresStorageNotLow: Boolean = false,
    val requiresDeviceIdle: Boolean = false,
    val initialDelayMs: Long = 0L,
    val periodicMs: Long = 0L,
    val contentUris: List<String> = emptyList(),
    val contentDescendants: Boolean = false,
    val contentUpdateDelayMs: Long = 0L,
    val contentMaxDelayMs: Long = 0L,
    val maxPressure: PressureLevel? = null,   // null = importance-derived default
    val backoffMs: Long = 0L,                 // 0 = default (30s exponential)
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
)

class WorkRequestBuilder internal constructor(
    private val name: String, private val workerName: String) {
    private var importance = Importance.DEFAULT
    private var charging = false
    private var unmetered = false
    private var network = false
    private var batteryNotLow = false
    private var storageNotLow = false
    private var deviceIdle = false
    private var initialDelayMs = 0L
    private var periodicMs = 0L
    private var contentUris = mutableListOf<String>()
    private var contentDescendants = false
    private var contentUpdateDelayMs = 0L
    private var contentMaxDelayMs = 0L
    private var chunkCount = 0
    private var estimatedUpBytes = 0L
    private var maxAttempts = 3
    private var deadlineMs = 0L
    private var maxPressure: PressureLevel? = null
    private var backoffMs = 0L
    private var backoffPolicy = BackoffPolicy.EXPONENTIAL

    fun importance(value: Importance) { importance = value }
    fun charging() { charging = true }
    fun unmetered() { unmetered = true }
    /** Requires any connected network. Without this (or [unmetered]) the work runs offline. */
    fun network() { network = true }
    fun batteryNotLow() { batteryNotLow = true }
    fun storageNotLow() { storageNotLow = true }
    /** Runs only while the device is idle (JobInfo.setRequiresDeviceIdle). */
    fun deviceIdle() { deviceIdle = true }
    /** Don't start before enqueue + delay (JobInfo.setMinimumLatency on an exact JobInfo). */
    fun initialDelay(ms: Long) { require(ms > 0); initialDelayMs = ms }
    /** Repeats every [ms] (>= 15 min, the platform floor). Each cycle is a new generation. */
    fun periodic(ms: Long) {
        require(ms >= 15 * 60_000L) { "period must be >= 15min" }
        require(contentUris.isEmpty()) {
            "periodic work cannot use contentTrigger: JobInfo forbids setPeriodic with setTriggerContentUri" }
        periodicMs = ms
    }
    /**
     * Runs when content under any of [uris] changes (JobInfo.TriggerContentUri; rides the
     * exact 1:1 path). [descendants] adds FLAG_NOTIFY_FOR_DESCENDANTS to every uri;
     * [updateDelayMs]/[maxDelayMs] map to setTriggerContentUpdateDelay/MaxDelay when > 0.
     * Cannot combine with [periodic] — the platform forbids periodic trigger jobs.
     */
    fun contentTrigger(vararg uris: String, descendants: Boolean = false,
                       updateDelayMs: Long = 0L, maxDelayMs: Long = 0L) {
        require(uris.isNotEmpty()) { "contentTrigger needs at least one uri" }
        require(periodicMs == 0L) {
            "periodic work cannot use contentTrigger: JobInfo forbids setPeriodic with setTriggerContentUri" }
        require(updateDelayMs >= 0 && maxDelayMs >= 0)
        contentUris += uris
        if (descendants) contentDescendants = true
        if (updateDelayMs > 0) contentUpdateDelayMs = updateDelayMs
        if (maxDelayMs > 0) contentMaxDelayMs = maxDelayMs
    }
    fun chunks(count: Int, estimatedUpBytes: Long = 0L) {
        require(count > 0) { "chunk count must be positive" }
        chunkCount = count; this.estimatedUpBytes = estimatedUpBytes
    }
    /** Size hint for un-chunked work; feeds L4 quota admission (~1MB/s heuristic). */
    fun estimatedBytes(bytes: Long) { require(bytes > 0); estimatedUpBytes = bytes }
    fun maxAttempts(value: Int) { require(value > 0); maxAttempts = value }
    /**
     * Dispatch only while process thread pressure is at or below [level] (runnable
     * threads vs cores: LOW ≤ cores < MEDIUM ≤ cores×2 < HIGH). Overrides the
     * importance-derived default (MIN/LOW yield at MEDIUM, DEFAULT yields at HIGH,
     * HIGH importance never yields). Deadline work always dispatches regardless.
     */
    fun maxThreadPressure(level: PressureLevel) { maxPressure = level }
    /** Deadline for L4 escalation: the policy engine walks urgency tiers as this nears. */
    fun mustCompleteBy(atMs: Long) { require(atMs > 0); deadlineMs = atMs }
    /**
     * Retry pacing between attempts (JobInfo.setBackoffCriteria), replacing the default
     * 30s exponential. [initialMs] must be >= 10s (JobInfo.MIN_BACKOFF_MILLIS). Cannot
     * combine with [deviceIdle] (the platform rejects backoff on idle-mode jobs) or
     * [periodic] (the period itself paces re-runs) — build() enforces both.
     */
    fun backoff(initialMs: Long, policy: BackoffPolicy = BackoffPolicy.EXPONENTIAL) {
        require(initialMs >= 10_000L) { "backoff initialMs must be >= 10s (JobInfo.MIN_BACKOFF_MILLIS)" }
        backoffMs = initialMs; backoffPolicy = policy
    }

    internal fun build(): WorkRequest {
        require(backoffMs == 0L || !deviceIdle) {
            "backoff cannot combine with deviceIdle: the platform rejects backoff on idle-mode jobs" }
        require(backoffMs == 0L || periodicMs == 0L) {
            "backoff cannot combine with periodic: the period itself paces re-runs" }
        return buildRequest()
    }

    private fun buildRequest() = WorkRequest(name, workerName, importance,
        charging, unmetered, chunkCount, estimatedUpBytes, maxAttempts, deadlineMs,
        requiresNetwork = network, requiresBatteryNotLow = batteryNotLow,
        requiresStorageNotLow = storageNotLow, requiresDeviceIdle = deviceIdle,
        initialDelayMs = initialDelayMs, periodicMs = periodicMs,
        contentUris = contentUris.toList(), contentDescendants = contentDescendants,
        contentUpdateDelayMs = contentUpdateDelayMs, contentMaxDelayMs = contentMaxDelayMs,
        maxPressure = maxPressure, backoffMs = backoffMs, backoffPolicy = backoffPolicy)
}

fun workRequest(name: String, workerName: String,
                block: WorkRequestBuilder.() -> Unit = {}): WorkRequest =
    WorkRequestBuilder(name, workerName).apply(block).build()
