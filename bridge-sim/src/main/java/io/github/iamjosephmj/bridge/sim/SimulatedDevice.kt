package io.github.iamjosephmj.bridge.sim

import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.WorkRequest
import io.github.iamjosephmj.bridge.api.WorkerRegistry
import io.github.iamjosephmj.bridge.diagnostics.Diagnoser
import io.github.iamjosephmj.bridge.diagnostics.Verdict
import io.github.iamjosephmj.bridge.dispatch.Dispatcher
import io.github.iamjosephmj.bridge.exec.BlackBox
import io.github.iamjosephmj.bridge.exec.CostMeter
import io.github.iamjosephmj.bridge.exec.CostSnapshot
import io.github.iamjosephmj.bridge.exec.WorkRunner
import io.github.iamjosephmj.bridge.signals.FakeSignalSource
import io.github.iamjosephmj.bridge.signals.InMemoryTransitionStore
import io.github.iamjosephmj.bridge.signals.SignalHub
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalLog
import io.github.iamjosephmj.bridge.signals.Trigger
import io.github.iamjosephmj.bridge.store.InMemoryJournal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import kotlinx.coroutines.runBlocking

private object NoopBlackBox : BlackBox {
    override fun stamp(workId: String, step: String, attempt: Int) = Unit
    override fun clear() = Unit
}

private object ZeroCostMeter : CostMeter {
    override fun snapshot() = CostSnapshot(0, 0, 0, 0)
}

/**
 * A whole Bridge stack — real journal fold, dispatcher, runner, hub, diagnoser — driven by
 * a scripted [Timeline] and a [FakeClock]. Advances in 60-second ticks; work executes
 * inline the tick its gates open.
 */
class SimulatedDevice internal constructor() {
    val clock = FakeClock(0L)
    val timeline = Timeline()
    val journal = InMemoryJournal()
    private val registry = WorkerRegistry()
    private val signalLog = SignalLog(InMemoryTransitionStore())
    private val sources = SignalKind.entries.map { kind ->
        FakeSignalSource(kind, timeline.valueAt(kind, 0L))
    }
    private val hub = SignalHub(sources, signalLog, clock)
    internal val gateway = SimulatedGateway(timeline, journal)
    internal val alarms = io.github.iamjosephmj.bridge.dispatch.FakeAlarmGateway()
    private val dispatcher = Dispatcher(journal, gateway, clock,
        policy = io.github.iamjosephmj.bridge.policy.PolicyEngine(apiLevel = 34),
        alarmGateway = alarms,
        snapshotProvider = { hub.snapshot(Trigger.SCHEDULING_DECISION) },
        historyProvider = {
            signalLog.slice(clock.now() - 3L * 24 * 60 * 60 * 1000, clock.now())
        })
    private val runner = WorkRunner(journal, registry, NoopBlackBox, ZeroCostMeter, clock)

    private val tickMs = 60_000L

    fun worker(name: String, factory: () -> BridgeWorker) = registry.register(name, factory)

    /** M5: register a durable block; start instances with [startDurable]. */
    fun durable(name: String, block: io.github.iamjosephmj.bridge.api.DurableBlock) {
        val deps = io.github.iamjosephmj.bridge.api.DurableDeps(
            journal, clock, { hub.snapshot(Trigger.DIAGNOSIS) }, alarms)
        registry.register(name) { io.github.iamjosephmj.bridge.api.DurableWorker(block, deps) }
    }

    fun startDurable(name: String): SimHandle =
        enqueue(io.github.iamjosephmj.bridge.api.workRequest(name, name))

    /**
     * Simulated process death + relaunch: scheduler-side state is dropped (M1's
     * setPersisted(false) world) and the reconcile path re-dispatches from the journal.
     * The journal, signal log, and armed alarms survive — that is the point.
     */
    fun restartProcess() {
        gateway.cancelAll()
        dispatcher.dispatchAll()
    }

    fun enqueue(request: WorkRequest): SimHandle {
        val existing = journal.state(request.name)
        val generation = (existing?.generation ?: 0) + 1
        journal.append(WorkEvent.Enqueued(request.name, clock.now(), request.workerName, generation,
            importance = request.importance.ordinal,
            requiresCharging = request.requiresCharging,
            requiresUnmetered = request.requiresUnmetered,
            chunkCount = request.chunkCount,
            estimatedUpBytes = request.estimatedUpBytes,
            maxAttempts = request.maxAttempts,
            deadlineMs = request.deadlineMs,
            requiresNetwork = request.requiresNetwork,
            requiresBatteryNotLow = request.requiresBatteryNotLow,
            requiresStorageNotLow = request.requiresStorageNotLow,
            requiresDeviceIdle = request.requiresDeviceIdle,
            initialDelayMs = request.initialDelayMs,
            periodicMs = request.periodicMs))
        // Sync sources before the dispatch decision — policy must see the scripted present.
        for (src in sources) src.value = timeline.valueAt(src.kind, clock.now())
        dispatcher.dispatch(request.name)
        hub.snapshot(Trigger.SCHEDULING_DECISION)
        return SimHandle(request.name, this)
    }

    fun advanceTo(ms: Long) {
        while (clock.now() < ms) {
            clock.nowMs = minOf(clock.now() + tickMs, ms)
            tick()
        }
    }

    private fun tick() {
        val now = clock.now()
        for (src in sources) src.value = timeline.valueAt(src.kind, now)
        hub.snapshot(Trigger.BROADCAST)
        // Held/shed work re-runs policy every tick (device: reconcile paths + broadcasts).
        dispatcher.dispatchAll()
        // Due while-idle alarms fire and are consumed.
        alarms.scheduled.removeAll { (at, _) ->
            if (at <= now) { dispatcher.dispatchAll(); true } else false
        }
        for (payload in gateway.runnable(now)) {
            val state = journal.state(payload.workId) ?: continue
            val deliveryCount = journal.events(payload.workId)
                .count { it is WorkEvent.Started && it.generation == state.generation } + 1
            val outcome = runBlocking {
                runner.run(payload.workId, payload.generation, deliveryCount) { false }
            }
            when (outcome) {
                io.github.iamjosephmj.bridge.exec.RunOutcome.COMPLETED,
                io.github.iamjosephmj.bridge.exec.RunOutcome.FAILED ->
                    // Periodic platform jobs outlive their cycles; cancelled series drop.
                    if ((journal.state(payload.workId)?.periodicMs ?: 0L) == 0L ||
                        journal.state(payload.workId)?.runState ==
                            io.github.iamjosephmj.bridge.store.RunState.CANCELLED) {
                        gateway.remove(payload.workId)
                    } else Unit
                io.github.iamjosephmj.bridge.exec.RunOutcome.RETRY -> Unit   // stays parked, backoff gates it
            }
        }
    }

    fun verdict(name: String): Verdict? {
        val state = journal.state(name) ?: return null
        val events = journal.events(name)
        val snapshot = hub.snapshot(Trigger.DIAGNOSIS)
        val enqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>()
            .lastOrNull { it.generation == state.generation }?.at ?: 0L
        return Diagnoser.diagnose(state, events, snapshot, signalLog.slice(enqueuedAt, clock.now()))
    }
}

class SimHandle internal constructor(val name: String, private val device: SimulatedDevice) {
    fun verdictAt(ms: Long): Verdict {
        device.advanceTo(ms)
        return checkNotNull(device.verdict(name)) { "unknown work $name" }
    }

    fun completedWithin(ms: Long): Boolean {
        device.advanceTo(ms)
        return device.journal.state(name)?.runState == RunState.SUCCEEDED
    }

    fun state() = device.journal.state(name)
}
