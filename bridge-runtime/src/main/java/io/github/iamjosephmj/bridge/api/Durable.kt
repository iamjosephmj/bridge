package io.github.iamjosephmj.bridge.api

import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.dispatch.AlarmGateway
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.store.EventJournal
import io.github.iamjosephmj.bridge.store.WorkEvent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.concurrent.CancellationException

/**
 * M5 durable coroutines: suspend blocks that survive process death via deterministic
 * replay (design §4.7, M5 spec). Effects belong inside step(); code between steps must
 * be deterministic; time/randomness via ctx.now()/ctx.random().
 */
typealias DurableBlock = suspend (DurableContext) -> Unit

class DurableDeps(
    val journal: EventJournal,
    val clock: BridgeClock,
    val snapshotProvider: () -> SignalSnapshot,
    val alarmGateway: AlarmGateway?,
)

/** Thrown to unwind out of a durable block when it parks; caught by [DurableWorker]. */
internal class ParkSignal(val wakeAtMs: Long, val why: String) : CancellationException(why)

class DurableStructureMismatch(message: String) : IllegalStateException(message)

private val json = Json { ignoreUnknownKeys = true }

class DurableContext internal constructor(
    private val workId: String,
    private val generation: Int,
    private val deps: DurableDeps,
) {
    private val journaled: List<WorkEvent.StepCompleted> =
        deps.journal.events(workId)
            .filterIsInstance<WorkEvent.StepCompleted>()
            .filter { it.generation == generation }
    private var cursor = 0
    private var sysCounter = 0

    /**
     * The only place for effects. Executes once ever: on replay a completed step
     * returns its journaled result without re-running the block.
     */
    suspend fun <T> step(name: String, serializer: KSerializer<T>, block: suspend () -> T): T {
        val replayed = journaled.getOrNull(cursor)
        if (replayed != null) {
            if (replayed.name != name) throw DurableStructureMismatch(
                "replay position $cursor: journal has step '${replayed.name}', code has '$name' " +
                    "— the durable block changed shape mid-flight")
            cursor++
            return json.decodeFromString(serializer, replayed.resultJson)
        }
        val result = block()
        deps.journal.append(WorkEvent.StepCompleted(
            workId, deps.clock.now(), name, json.encodeToString(serializer, result), generation))
        cursor++
        return result
    }

    suspend inline fun <reified T> step(name: String, noinline block: suspend () -> T): T =
        step(name, serializer(), block)

    /** Journaled timer: survives death — replay skips past an elapsed timer instantly. */
    suspend fun delay(ms: Long) {
        val wakeAt = step("\$sys:delay:${sysCounter++}", Long.serializer()) {
            deps.clock.now() + ms
        }
        if (deps.clock.now() < wakeAt) {
            deps.alarmGateway?.scheduleAt(wakeAt, workId)
            throw ParkSignal(wakeAt, "delay until $wakeAt")
        }
    }

    /** Parks until the signal hub satisfies [predicate]; satisfaction is journaled. */
    suspend fun await(name: String, predicate: (SignalSnapshot) -> Boolean) {
        // Already satisfied on a previous run? The journaled step replays through.
        val satisfied = journaled.getOrNull(cursor) != null ||
            predicate(deps.snapshotProvider())
        if (!satisfied) throw ParkSignal(0L, "await $name")
        step("\$sys:await:$name", Boolean.serializer()) { true }
    }

    /** Deterministic time: journaled on first execution, identical on every replay. */
    suspend fun now(): Long = step("\$sys:now:${sysCounter++}", Long.serializer()) {
        deps.clock.now()
    }

    /** Deterministic randomness, same contract as [now]. */
    suspend fun random(): Long = step("\$sys:random:${sysCounter++}", Long.serializer()) {
        kotlin.random.Random.nextLong()
    }
}

class DurableWorker(
    private val block: DurableBlock,
    private val deps: DurableDeps,
) : BridgeWorker {
    override suspend fun run(ctx: RunContext): RunResult {
        val generation = deps.journal.state(ctx.workId)?.generation ?: 1
        val dctx = DurableContext(ctx.workId, generation, deps)
        return try {
            block(dctx)
            RunResult.Success
        } catch (p: ParkSignal) {
            deps.journal.append(WorkEvent.PolicyDecision(
                ctx.workId, deps.clock.now(), "park", p.why))
            RunResult.Parked(p.wakeAtMs)
        } catch (m: DurableStructureMismatch) {
            deps.journal.append(WorkEvent.PolicyDecision(
                ctx.workId, deps.clock.now(), "structure-mismatch", m.message ?: ""))
            RunResult.Failure
        }
    }
}
