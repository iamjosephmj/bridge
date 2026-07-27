package io.github.iamjosephmj.bridge.dispatch

import io.github.iamjosephmj.bridge.store.WorkState

enum class HostJobClass(val jobId: Int) {
    DEFAULT(710_001),
    DEFERRABLE(710_002),
    UNMETERED_CHARGING(710_003);

    companion object {
        fun forWork(state: WorkState): HostJobClass = when {
            state.requiresUnmetered && state.requiresCharging -> UNMETERED_CHARGING
            state.importance <= 1 -> DEFERRABLE   // MIN=0, LOW=1
            else -> DEFAULT
        }
    }
}
