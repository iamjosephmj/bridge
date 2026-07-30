package io.github.iamjosephmj.bridge.dispatch

import io.github.iamjosephmj.bridge.store.WorkState

enum class HostJobClass(val jobId: Int) {
    DEFAULT(710_001),            // requires any connected network
    DEFERRABLE(710_002),
    UNMETERED_CHARGING(710_003),
    EXPEDITED(710_004),          // L4 escalation tier: JobInfo.setExpedited on 31+
    NO_NETWORK(710_005);         // constraint-free work — runs offline (fixes the M1
                                 // accident where all work implicitly waited for network)

    companion object {
        /**
         * Multiplexed routing for the common shapes. Work whose exact constraint set no
         * host class matches (charging-only, unmetered-only, battery/storage/idle) is
         * routed by the dispatcher through the 1:1 path with a per-item JobInfo instead —
         * see [WorkState.needsExactConstraints].
         */
        fun forWork(state: WorkState): HostJobClass = when {
            state.requiresUnmetered && state.requiresCharging -> UNMETERED_CHARGING
            state.importance <= 1 -> DEFERRABLE   // MIN=0, LOW=1
            state.requiresNetwork -> DEFAULT
            state.hasNoConstraints -> NO_NETWORK
            else -> DEFAULT
        }
    }
}

val WorkState.hasNoConstraints: Boolean
    get() = !requiresCharging && !requiresUnmetered && !requiresNetwork &&
        !requiresBatteryNotLow && !requiresStorageNotLow && !requiresDeviceIdle

/**
 * True when no multiplexed host class expresses this work's exact constraints; the
 * dispatcher then uses the 1:1 gateway with a per-item JobInfo.
 */
val WorkState.needsExactConstraints: Boolean
    get() = requiresBatteryNotLow || requiresStorageNotLow || requiresDeviceIdle ||
        (requiresCharging && !requiresUnmetered) ||
        (requiresUnmetered && !requiresCharging) ||
        // Constraint-free work must also go 1:1: JobScheduler.enqueue() forbids timing
        // constraints and JobInfo.build() forbids NO constraints, so a multiplexed
        // constraint-free host job is unrepresentable. schedule()+1ms latency is legal.
        hasNoConstraints ||
        // Timing shapes are illegal for enqueue()-style jobs; both ride exact JobInfos.
        initialDelayMs > 0 || periodicMs > 0 ||
        // Content triggers are per-item by nature — one TriggerContentUri set per JobInfo.
        contentUris.isNotEmpty() ||
        // Custom backoff criteria live on the JobInfo, so they need a per-item one.
        backoffMs > 0
