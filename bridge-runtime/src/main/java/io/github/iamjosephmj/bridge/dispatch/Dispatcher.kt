package io.github.iamjosephmj.bridge.dispatch

import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent

class Dispatcher(
    private val journal: Journal,
    private val gateway: JobGateway,
    private val clock: BridgeClock,
) {
    fun dispatchAll() {
        journal.liveWork()
            .filter { it.runState == RunState.ENQUEUED }
            .forEach { dispatchState(it) }
    }

    fun dispatch(workId: String) {
        val state = journal.state(workId) ?: return
        if (state.runState == RunState.ENQUEUED) dispatchState(state)
    }

    private fun dispatchState(state: io.github.iamjosephmj.bridge.store.WorkState) {
        val hostClass = HostJobClass.forWork(state)
        val ok = gateway.enqueue(hostClass, WorkItemPayload(state.workId, state.generation))
        if (ok) {
            journal.append(WorkEvent.Dispatched(
                state.workId, clock.now(), hostClass.name, state.generation))
        }
        // On failure: stay ENQUEUED; reconciler / next dispatchAll retries.
    }
}
