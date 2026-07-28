package io.github.iamjosephmj.bridge.dispatch

import android.content.SharedPreferences

/**
 * Dispatch strategy Bridge uses to talk to JobScheduler. Starts out MULTIPLEXED (many work
 * items batched behind one JobInfo via JobWorkItem/dequeueWork) and falls back to ONE_TO_ONE
 * (one JobInfo per work item) when the host device's JobScheduler proves unable to sustain
 * multiplexed enqueues (some OEM schedulers throw / silently drop on `enqueue()`).
 */
enum class DispatchMode { MULTIPLEXED, ONE_TO_ONE }

/**
 * Persists the current [DispatchMode] plus a consecutive-enqueue-failure counter in
 * [SharedPreferences] so the fallback decision survives process death. Three consecutive
 * multiplexed-enqueue failures trip the breaker to ONE_TO_ONE permanently (until the app is
 * reinstalled / prefs cleared) — there is deliberately no automatic recovery back to
 * MULTIPLEXED, since a device whose scheduler can't sustain multiplexing now is unlikely to
 * become able to mid-session.
 */
class Conformance(private val prefs: SharedPreferences) {
    var mode: DispatchMode
        get() = DispatchMode.valueOf(
            prefs.getString(KEY_MODE, DispatchMode.MULTIPLEXED.name)!!)
        private set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    private var failures: Int
        get() = prefs.getInt(KEY_FAILURES, 0)
        set(value) { prefs.edit().putInt(KEY_FAILURES, value).apply() }

    fun recordEnqueueFailure() {
        failures += 1
        if (failures >= FAILURE_THRESHOLD) mode = DispatchMode.ONE_TO_ONE
    }

    fun recordEnqueueSuccess() { failures = 0 }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_FAILURES = "failures"
        const val FAILURE_THRESHOLD = 3
    }
}

/**
 * Routes each enqueue by the current [Conformance.mode]: MULTIPLEXED goes to [multiplexed]
 * (the batched JobWorkItem path), ONE_TO_ONE goes to [oneToOne] (one JobInfo per item). Every
 * multiplexed attempt reports its outcome back to [conformance], which trips the breaker after
 * enough consecutive failures — subsequent calls (not the one that tripped it) then route
 * straight to [oneToOne].
 */
class SelectingJobGateway(
    private val multiplexed: JobGateway,
    private val oneToOne: JobGateway,
    private val conformance: Conformance,
) : JobGateway {
    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        if (conformance.mode == DispatchMode.ONE_TO_ONE) {
            return oneToOne.enqueue(hostClass, payload)
        }
        val ok = multiplexed.enqueue(hostClass, payload)
        if (ok) conformance.recordEnqueueSuccess() else conformance.recordEnqueueFailure()
        return ok
    }

    override fun cancelAll() { multiplexed.cancelAll(); oneToOne.cancelAll() }
}
