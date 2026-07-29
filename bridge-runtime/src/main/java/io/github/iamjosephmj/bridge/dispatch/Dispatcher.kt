package io.github.iamjosephmj.bridge.dispatch

import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.policy.Decision
import io.github.iamjosephmj.bridge.policy.PolicyEngine
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.store.EventJournal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import io.github.iamjosephmj.bridge.store.WorkState

class Dispatcher(
    private val journal: EventJournal,
    private val gateway: JobGateway,
    private val clock: BridgeClock,
    private val policy: PolicyEngine? = null,               // null → M1 behavior (no judgment)
    private val alarmGateway: AlarmGateway? = null,
    private val snapshotProvider: (() -> SignalSnapshot)? = null,
    private val historyProvider: (() -> io.github.iamjosephmj.bridge.signals.SignalSlice?)? = null,
) {
    @Synchronized
    fun dispatchAll() {
        journal.liveWork()
            .filter {
                it.runState == RunState.ENQUEUED ||
                    // Deadline work re-runs policy even when already with the scheduler —
                    // escalation must be able to re-tier a pending job as the deadline nears.
                    (it.runState == RunState.DISPATCHED && it.deadlineMs > 0 && policy != null)
            }
            .forEach { dispatchState(it) }
    }

    @Synchronized
    fun dispatch(workId: String) {
        val state = journal.state(workId) ?: return
        if (state.runState == RunState.ENQUEUED) dispatchState(state)
    }

    private fun dispatchState(state: WorkState) {
        val decision = decide(state)
        if (state.runState == RunState.DISPATCHED) {
            // Already with the scheduler: only act on an escalation to a *different* tier —
            // except the final ALARM stage, whose action (arming the alarm) is tierless and
            // must fire exactly once per generation.
            if (decision !is Decision.Admit) return
            val events = journal.events(state.workId)
            val lastTier = events.filterIsInstance<WorkEvent.Dispatched>().lastOrNull()?.hostClass
            if (decision.tier.name == lastTier) {
                val alarmArmed = events.any {
                    it is WorkEvent.PolicyDecision && it.why == PolicyEngine.ESCALATE_ALARM_WHY }
                if (decision.why != PolicyEngine.ESCALATE_ALARM_WHY || alarmArmed) return
            }
        }
        when (decision) {
            is Decision.Hold -> { journalDecision(state, "hold", decision.why); return }
            is Decision.Shed -> { journalDecision(state, "shed", decision.why); return }
            is Decision.Admit -> {
                if (decision.why != null) {
                    journalDecision(state, "admit:${decision.tier.name}", decision.why)
                }
                if (decision.why == PolicyEngine.ESCALATE_ALARM_WHY && state.deadlineMs > 0) {
                    // Final tier: also arm a while-idle alarm shortly before the deadline.
                    alarmGateway?.scheduleAt(
                        maxOf(clock.now(), state.deadlineMs - ALARM_MARGIN_MS), state.workId)
                }
                val exact = if (state.needsExactConstraints) ItemConstraints(
                    network = when {
                        state.requiresUnmetered -> 2
                        state.requiresNetwork -> 1
                        else -> 0
                    },
                    charging = state.requiresCharging,
                    batteryNotLow = state.requiresBatteryNotLow,
                    storageNotLow = state.requiresStorageNotLow,
                    deviceIdle = state.requiresDeviceIdle,
                ) else null
                val ok = gateway.enqueue(decision.tier,
                    WorkItemPayload(state.workId, state.generation, exact))
                if (ok) {
                    journal.append(WorkEvent.Dispatched(
                        state.workId, clock.now(), decision.tier.name, state.generation))
                }
                // On failure: stay ENQUEUED; reconciler / next dispatchAll retries.
            }
        }
    }

    private fun decide(state: WorkState): Decision {
        val engine = policy ?: return Decision.Admit(HostJobClass.forWork(state))
        val snapshot = try { snapshotProvider?.invoke() } catch (_: Exception) { null }
            ?: return Decision.Admit(HostJobClass.forWork(state))
        val history = try { historyProvider?.invoke() } catch (_: Exception) { null }
        return engine.decide(state, journal.events(state.workId), snapshot, clock.now(), history)
    }

    /** Journal a judgment once — identical consecutive decisions aren't re-appended. */
    private fun journalDecision(state: WorkState, decision: String, why: String) {
        val last = journal.events(state.workId).lastOrNull()
        if (last is WorkEvent.PolicyDecision && last.decision == decision && last.why == why) return
        journal.append(WorkEvent.PolicyDecision(state.workId, clock.now(), decision, why))
    }

    private companion object { const val ALARM_MARGIN_MS = 5 * 60_000L }
}
