package io.github.iamjosephmj.bridge.api

import io.github.iamjosephmj.bridge.store.WorkEvent
import io.github.iamjosephmj.bridge.store.WorkState

/**
 * Canonical [WorkEvent.Enqueued] factories. Kept as extensions in api/ (not on the data
 * class itself) so store — the bottom layer — doesn't grow a dependency on [WorkRequest].
 */

/** Journal record for a fresh enqueue of [request] as generation [generation]. */
fun WorkEvent.Enqueued.Companion.of(
    request: WorkRequest, generation: Int, at: Long,
): WorkEvent.Enqueued = WorkEvent.Enqueued(
    request.name, at, request.workerName, generation,
    importance = request.importance.ordinal,
    requiresCharging = request.requiresCharging,
    requiresUnmetered = request.requiresUnmetered,
    chunkCount = request.chunkCount,
    estimatedUpBytes = request.estimatedUpBytes,
    maxAttempts = request.maxAttempts,
    deadlineMs = request.deadlineMs,
    requiresNetwork = request.requiresNetwork,
    requiresBatteryNotLow = request.requiresBatteryNotLow,
    requiresStorageNotLow = request.requiresStorageNotLow,
    requiresDeviceIdle = request.requiresDeviceIdle,
    initialDelayMs = request.initialDelayMs,
    periodicMs = request.periodicMs,
    contentUris = request.contentUris,
    contentDescendants = request.contentDescendants,
    contentUpdateDelayMs = request.contentUpdateDelayMs,
    contentMaxDelayMs = request.contentMaxDelayMs,
    maxPressure = request.maxPressure?.ordinal ?: -1)

/**
 * Journal record rolling periodic work from a terminal cycle into the next generation.
 *
 * Deliberately drops deadlineMs and initialDelayMs: a deadline (mustCompleteBy) is an
 * absolute wall-clock point that belonged to the original request — carrying it forward
 * would make every later cycle instantly overdue — and initial delay gates only the
 * first cycle; the period itself paces every subsequent one. Content-trigger fields are
 * absent by construction: the builder forbids periodic + contentTrigger (as the platform
 * does), so periodic state never carries them.
 */
fun WorkEvent.Enqueued.Companion.nextCycle(state: WorkState, at: Long): WorkEvent.Enqueued =
    WorkEvent.Enqueued(
        state.workId, at, state.workerName, state.generation + 1,
        importance = state.importance,
        requiresCharging = state.requiresCharging,
        requiresUnmetered = state.requiresUnmetered,
        chunkCount = state.chunkCount,
        estimatedUpBytes = state.estimatedUpBytes,
        maxAttempts = state.maxAttempts,
        requiresNetwork = state.requiresNetwork,
        requiresBatteryNotLow = state.requiresBatteryNotLow,
        requiresStorageNotLow = state.requiresStorageNotLow,
        requiresDeviceIdle = state.requiresDeviceIdle,
        periodicMs = state.periodicMs,
        maxPressure = state.maxPressure)
