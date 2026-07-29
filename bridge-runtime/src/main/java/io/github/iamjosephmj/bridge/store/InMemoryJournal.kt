package io.github.iamjosephmj.bridge.store

class InMemoryJournal : EventJournal {
    private val events = mutableListOf<WorkEvent>()

    @Synchronized override fun append(event: WorkEvent) { events += event }
    @Synchronized override fun appendAll(events: List<WorkEvent>) { this.events += events }

    @Synchronized override fun events(workId: String): List<WorkEvent> =
        events.filter { it.workId == workId }

    @Synchronized override fun state(workId: String): WorkState? =
        foldWorkState(events(workId))

    @Synchronized override fun liveWork(): List<WorkState> = allWork().filter {
        it.runState in setOf(RunState.ENQUEUED, RunState.DISPATCHED, RunState.RUNNING)
    }

    @Synchronized override fun runningWork(): List<WorkState> =
        allWork().filter { it.runState == RunState.RUNNING }

    @Synchronized override fun allWork(): List<WorkState> =
        events.map { it.workId }.distinct().mapNotNull { state(it) }

    @Synchronized override fun prune(olderThanMs: Long, now: Long) {
        val cutoff = now - olderThanMs
        val terminal = setOf(RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED)
        val dead = events.map { it.workId }.distinct().filter { id ->
            val st = state(id) ?: return@filter false
            st.runState in terminal && events(id).maxOf { it.at } < cutoff
        }.toSet()
        events.removeAll { it.workId in dead }
    }

    override fun close() = Unit
}
