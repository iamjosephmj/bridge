package io.github.iamjosephmj.bridge.api

enum class Importance { MIN, LOW, DEFAULT, HIGH }

class WorkRequest internal constructor(
    val name: String, val workerName: String, val importance: Importance,
    val requiresCharging: Boolean, val requiresUnmetered: Boolean,
    val chunkCount: Int, val estimatedUpBytes: Long, val maxAttempts: Int,
    val deadlineMs: Long = 0L,
    val requiresNetwork: Boolean = false,        // any connected network
    val requiresBatteryNotLow: Boolean = false,
    val requiresStorageNotLow: Boolean = false,
    val requiresDeviceIdle: Boolean = false,
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
    private var chunkCount = 0
    private var estimatedUpBytes = 0L
    private var maxAttempts = 3
    private var deadlineMs = 0L

    fun importance(value: Importance) { importance = value }
    fun charging() { charging = true }
    fun unmetered() { unmetered = true }
    /** Requires any connected network. Without this (or [unmetered]) the work runs offline. */
    fun network() { network = true }
    fun batteryNotLow() { batteryNotLow = true }
    fun storageNotLow() { storageNotLow = true }
    /** Runs only while the device is idle (JobInfo.setRequiresDeviceIdle). */
    fun deviceIdle() { deviceIdle = true }
    fun chunks(count: Int, estimatedUpBytes: Long = 0L) {
        require(count > 0) { "chunk count must be positive" }
        chunkCount = count; this.estimatedUpBytes = estimatedUpBytes
    }
    /** Size hint for un-chunked work; feeds L4 quota admission (~1MB/s heuristic). */
    fun estimatedBytes(bytes: Long) { require(bytes > 0); estimatedUpBytes = bytes }
    fun maxAttempts(value: Int) { require(value > 0); maxAttempts = value }
    /** Deadline for L4 escalation: the policy engine walks urgency tiers as this nears. */
    fun mustCompleteBy(atMs: Long) { require(atMs > 0); deadlineMs = atMs }

    internal fun build() = WorkRequest(name, workerName, importance,
        charging, unmetered, chunkCount, estimatedUpBytes, maxAttempts, deadlineMs,
        requiresNetwork = network, requiresBatteryNotLow = batteryNotLow,
        requiresStorageNotLow = storageNotLow, requiresDeviceIdle = deviceIdle)
}

fun workRequest(name: String, workerName: String,
                block: WorkRequestBuilder.() -> Unit = {}): WorkRequest =
    WorkRequestBuilder(name, workerName).apply(block).build()
