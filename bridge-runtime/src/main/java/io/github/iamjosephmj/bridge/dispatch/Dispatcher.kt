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
) {
    @Synchronized
    fun dispatchAll() {
        journal.liveWork()
            .filter { it.runState == RunState.ENQUEUED }
            .forEach { dispatchState(it) }
    }

    @Synchronized
    fun dispatch(workId: String) {
        val state = journal.state(workId) ?: return
        if (state.runState == RunState.ENQUEUED) dispatchState(state)
    }

    private fun dispatchState(state: WorkState) {
        val decision = decide(state)
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
                val ok = gateway.enqueue(decision.tier,
                    WorkItemPayload(state.workId, state.generation))
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
        return engine.decide(state, journal.events(state.workId), snapshot, clock.now())
    }

    /** Journal a judgment once — identical consecutive decisions aren't re-appended. */
    private fun journalDecision(state: WorkState, decision: String, why: String) {
        val last = journal.events(state.workId).lastOrNull()
        if (last is WorkEvent.PolicyDecision && last.decision == decision && last.why == why) return
        journal.append(WorkEvent.PolicyDecision(state.workId, clock.now(), decision, why))
    }

    private companion object { const val ALARM_MARGIN_MS = 5 * 60_000L }
}
