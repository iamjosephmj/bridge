package io.github.iamjosephmj.bridge.store

/**
 * Append-only work-event journal. `Journal` is the durable SQLite implementation;
 * `InMemoryJournal` backs pure-JVM tests and the bridge-sim module.
 */
interface EventJournal {
    fun append(event: WorkEvent)
    fun appendAll(events: List<WorkEvent>)
    fun events(workId: String): List<WorkEvent>
    fun state(workId: String): WorkState?
    fun liveWork(): List<WorkState>
    fun runningWork(): List<WorkState>
    fun allWork(): List<WorkState>
    fun prune(olderThanMs: Long, now: Long)
    fun close()
}
