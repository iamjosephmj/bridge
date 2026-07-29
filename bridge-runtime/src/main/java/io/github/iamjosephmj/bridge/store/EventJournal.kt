package io.github.iamjosephmj.bridge.store

/**
 * Append-only work-event journal. `Journal` is the durable SQLite implementation;
 * `InMemoryJournal` backs pure-JVM tests and the bridge-sim module.
 */
interface EventJournal {
    fun append(event: WorkEvent)
    fun appendAll(events: List<WorkEvent>)

    /**
     * Registers an in-process listener invoked once per successfully appended event,
     * after the write has committed and outside any journal lock. Close the returned
     * handle to unregister (idempotent). Listeners may fire on any appending thread and
     * concurrent appends may interleave notifications — treat an event as a hint and
     * re-query [state] for truth. Listener exceptions are swallowed: observation must
     * never break the write path.
     */
    fun addListener(listener: (WorkEvent) -> Unit): AutoCloseable
    fun events(workId: String): List<WorkEvent>
    fun state(workId: String): WorkState?
    fun liveWork(): List<WorkState>
    fun runningWork(): List<WorkState>
    fun allWork(): List<WorkState>
    fun prune(olderThanMs: Long, now: Long)
    fun close()
}
