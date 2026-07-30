package io.github.iamjosephmj.bridge.store

/** UNKNOWN is a query-result-only state (never journaled): "no such work". */
enum class RunState { ENQUEUED, DISPATCHED, RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

data class WorkState(
    val workId: String, val workerName: String, val generation: Int,
    val runState: RunState, val attempt: Int,
    val nextChunk: Int, val chunkCount: Int, val maxAttempts: Int,
    val importance: Int, val requiresCharging: Boolean, val requiresUnmetered: Boolean,
    val estimatedUpBytes: Long,
    val lastStopReason: Int?, val lastDeath: WorkEvent.Died?,
    val deadlineMs: Long = 0L,
    val requiresNetwork: Boolean = false,
    val requiresBatteryNotLow: Boolean = false,
    val requiresStorageNotLow: Boolean = false,
    val requiresDeviceIdle: Boolean = false,
    val initialDelayMs: Long = 0L,
    val periodicMs: Long = 0L,
    val enqueuedAt: Long = 0L,
    val contentUris: List<String> = emptyList(),
    val contentDescendants: Boolean = false,
    val contentUpdateDelayMs: Long = 0L,
    val contentMaxDelayMs: Long = 0L,
    val maxPressure: Int = -1,
)

fun foldWorkState(events: List<WorkEvent>): WorkState? {
    var s: WorkState? = null
    for (e in events) {
        s = when (e) {
            is WorkEvent.Enqueued -> WorkState(
                workId = e.workId, workerName = e.workerName, generation = e.generation,
                runState = RunState.ENQUEUED, attempt = 0,
                nextChunk = 0, chunkCount = e.chunkCount, maxAttempts = e.maxAttempts,
                importance = e.importance, requiresCharging = e.requiresCharging,
                requiresUnmetered = e.requiresUnmetered, estimatedUpBytes = e.estimatedUpBytes,
                lastStopReason = null, lastDeath = null,
                deadlineMs = e.deadlineMs,
                requiresNetwork = e.requiresNetwork,
                requiresBatteryNotLow = e.requiresBatteryNotLow,
                requiresStorageNotLow = e.requiresStorageNotLow,
                requiresDeviceIdle = e.requiresDeviceIdle,
                initialDelayMs = e.initialDelayMs,
                periodicMs = e.periodicMs,
                enqueuedAt = e.at,
                contentUris = e.contentUris,
                contentDescendants = e.contentDescendants,
                contentUpdateDelayMs = e.contentUpdateDelayMs,
                contentMaxDelayMs = e.contentMaxDelayMs,
                maxPressure = e.maxPressure,
            )
            is WorkEvent.Dispatched -> s?.copy(runState = RunState.DISPATCHED)
            is WorkEvent.Started -> s?.copy(runState = RunState.RUNNING, attempt = e.attempt)
            is WorkEvent.ChunkCompleted -> s?.copy(nextChunk = maxOf(s.nextChunk, e.chunkIndex + 1))
            is WorkEvent.Stopped -> s?.copy(runState = RunState.ENQUEUED, lastStopReason = e.stopReason)
            is WorkEvent.Died -> s?.copy(runState = RunState.ENQUEUED, lastDeath = e)
            is WorkEvent.Finished -> s?.copy(
                runState = if (e.success) RunState.SUCCEEDED else RunState.FAILED)
            is WorkEvent.Cancelled -> s?.copy(runState = RunState.CANCELLED)
            is WorkEvent.PolicyDecision -> s   // judgment records don't change run state
            is WorkEvent.StepCompleted -> s    // durable replay records don't either
        }
    }
    return s
}
