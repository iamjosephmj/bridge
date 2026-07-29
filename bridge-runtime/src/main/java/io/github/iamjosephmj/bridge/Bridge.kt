package io.github.iamjosephmj.bridge

import android.content.Context
import io.github.iamjosephmj.bridge.api.BridgeScope
import io.github.iamjosephmj.bridge.api.DurableBlock
import io.github.iamjosephmj.bridge.api.DurableDeps
import io.github.iamjosephmj.bridge.api.DurableWorker
import io.github.iamjosephmj.bridge.api.WorkRequest
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.WorkerRegistry
import io.github.iamjosephmj.bridge.api.of
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.diagnostics.*
import io.github.iamjosephmj.bridge.dispatch.*
import io.github.iamjosephmj.bridge.exec.*
import io.github.iamjosephmj.bridge.policy.PolicyEngine
import io.github.iamjosephmj.bridge.signals.*
import io.github.iamjosephmj.bridge.store.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class BridgeConfigBuilder internal constructor() {
    internal val registry = WorkerRegistry()
    var clock: BridgeClock = SystemBridgeClock()
    var gateway: JobGateway? = null
    var costMeter: CostMeter? = null
    var deathSource: ProcessDeathSource? = null
    var ioExecutor: Executor? = null
    var dbName: String = "bridge.db"
    var signalSources: List<SignalSource>? = null
    var transitionStore: TransitionStore? = null

    fun worker(name: String, factory: () -> BridgeWorker) = registry.register(name, factory)

    internal val durables = mutableMapOf<String, DurableBlock>()
    /** Registers a durable block (M5). Start instances with [Bridge.durable]. */
    fun durable(name: String, block: DurableBlock) { durables[name] = block }
}

/** Everything a fully initialized Bridge needs, immutable so it can be published safely. */
private class BridgeRuntime(
    val journal: EventJournal,
    val dispatcher: Dispatcher,
    val clock: BridgeClock,
    val registry: WorkerRegistry,
    val durableDeps: DurableDeps,
    val gateway: JobGateway,
    val signalHub: SignalHub,
    val signalLog: SignalLog,
    val conformance: Conformance,
)

object Bridge {
    @Volatile
    private var runtime: BridgeRuntime? = null

    /**
     * Initializes Bridge: builds the journal/dispatcher/runner, then performs synchronous
     * reconciliation (death attribution, force-stop recovery, dispatch of any live work).
     * Idempotent — subsequent calls are no-ops once initialized.
     *
     * This reconciliation does synchronous DB work on the calling thread and its cost scales
     * with the size of any live-work backlog. Prefer calling this off the main thread (e.g. a
     * background thread from `Application.onCreate`) when large backlogs are possible.
     */
    @Synchronized
    fun initialize(context: Context, block: BridgeConfigBuilder.() -> Unit) {
        if (runtime != null) return   // idempotent
        val b = BridgeConfigBuilder().apply(block)
        val appContext = context.applicationContext
        val j = Journal(appContext, b.dbName,
            b.ioExecutor ?: Executors.newSingleThreadExecutor())
        val conf = Conformance(appContext.getSharedPreferences(
            "bridge.conformance", Context.MODE_PRIVATE))
        val gw = b.gateway ?: SelectingJobGateway(
            SystemJobGateway(appContext), OneToOneJobGateway(appContext), conf)
        val log = SignalLog(b.transitionStore ?: SqliteTransitionStore(appContext))
        val sources = b.signalSources ?: AndroidSignalSources.all(appContext)
        val hub = SignalHub(sources, log, b.clock)
        val alarmGw = SystemAlarmGateway(appContext)
        val d = Dispatcher(j, gw, b.clock,
            policy = PolicyEngine(android.os.Build.VERSION.SDK_INT),
            alarmGateway = alarmGw,
            snapshotProvider = { hub.snapshot(Trigger.SCHEDULING_DECISION) },
            historyProvider = {
                val now = b.clock.now()
                log.slice(now - 3L * 24 * 60 * 60 * 1000, now)   // 3 days of rhythm history
            })
        try {
            SignalBroadcasts(hub, sources)
                .apply { onBurstDrain = { reconcileIfInitialized() } }
                .start(appContext)
        } catch (_: Exception) { }
        val deps = DurableDeps(j, b.clock,
            snapshotProvider = { hub.snapshot(Trigger.DIAGNOSIS) }, alarmGateway = alarmGw)
        for ((name, block) in b.durables) {
            b.registry.register(name) { DurableWorker(block, deps) }
        }
        val runner = WorkRunner(j, b.registry, SystemBlackBox(appContext),
            b.costMeter ?: HealthStatsCostMeter(appContext), b.clock)
        BridgeServices.runner = runner
        runtime = BridgeRuntime(
            journal = j, dispatcher = d, clock = b.clock, registry = b.registry,
            durableDeps = deps, gateway = gw, signalHub = hub, signalLog = log,
            conformance = conf)
        Reconciler(j, d,
            DeathAttributor(j, b.deathSource ?: SystemProcessDeathSource(appContext), b.clock),
            ForceStopDetector(appContext), gw, b.clock).reconcile()
        pokeHub()   // reconciliation is a scheduling decision
    }

    /** Diagnostics must never break scheduling. */
    private fun pokeHub() {
        try { runtime?.signalHub?.snapshot(Trigger.SCHEDULING_DECISION) } catch (_: Exception) { }
    }

    fun enqueue(request: WorkRequest): String {
        val rt = requireNotNull(runtime) { "Bridge.initialize() not called" }
        val j = rt.journal
        val existing = j.state(request.name)
        if (existing != null && existing.runState in
            setOf(RunState.ENQUEUED, RunState.DISPATCHED, RunState.RUNNING)) {
            return request.name   // KEEP
        }
        val generation = (existing?.generation ?: 0) + 1
        j.append(WorkEvent.Enqueued.of(request, generation, rt.clock.now()))
        rt.dispatcher.dispatch(request.name)
        pokeHub()
        return request.name
    }

    private fun unknownVerdict(name: String, note: String) = Verdict(
        workId = name, state = RunState.UNKNOWN, diagnosis = Diagnosis.UnknownWork,
        contributing = emptyList(), evidence = emptyList(), basis = Basis.INFERRED,
        pendingSinceMs = null, notes = listOf(note))

    /** Why hasn't this work run? Total: unknown names get an UnknownWork verdict. */
    fun whyPending(name: String): Verdict {
        val rt = runtime ?: return unknownVerdict(name, "Bridge not initialized")
        val j = rt.journal
        val state = j.state(name) ?: return unknownVerdict(name, "no work named '$name'")
        val events = j.events(name)
        val snapshot = try { rt.signalHub.snapshot(Trigger.DIAGNOSIS) } catch (e: Exception) {
            SignalSnapshot(rt.clock.now(), emptyMap())
        }
        val enqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>()
            .lastOrNull { it.generation == state.generation }?.at ?: 0L
        val slice = try { rt.signalLog.slice(enqueuedAt, rt.clock.now()) } catch (e: Exception) { null }
        return Diagnoser.diagnose(state, events, snapshot, slice)
            ?: unknownVerdict(name, "no work named '$name'")
    }

    /** Full per-attempt run history with device context. Total: unknown names → empty runs. */
    fun ledger(name: String): Ledger {
        val rt = runtime ?: return Ledger(name, emptyList())
        val events = rt.journal.events(name)
        if (events.isEmpty()) return Ledger(name, emptyList())
        return LedgerFold.fold(name, events) { from, to ->
            try { rt.signalLog.slice(from, to) } catch (e: Exception) { null }
        }
    }

    /** One line per known work item; process-level conformance + signal-log health. */
    fun report(): BridgeReport {
        val rt = runtime ?: return BridgeReport(emptyList(), "UNKNOWN", 0 to null)
        val j = rt.journal
        val lines = j.allWork().map { st ->
            ReportLine(st.workId, st.runState,
                diagnosis = if (st.runState in setOf(RunState.ENQUEUED, RunState.DISPATCHED))
                    whyPending(st.workId).diagnosis else null)
        }
        val flags = try {
            // Aggregate per worker: several work items can share a worker implementation.
            CostFlags.compute(j.allWork().groupBy { it.workerName }.mapValues { (name, states) ->
                val runs = states.flatMap { ledger(it.workId).runs }
                states.maxOf { it.importance } to Ledger(name, runs)
            })
        } catch (e: Exception) { emptyList() }
        return BridgeReport(lines,
            conformanceMode = rt.conformance.mode.name,
            signalLogHealth = try { rt.signalLog.health() } catch (e: Exception) { 0 to null },
            costFlags = flags)
    }

    /** Starts an instance of a durable block registered via the config builder. */
    fun durable(name: String): String = enqueue(workRequest(name, name))

    /** Coroutine-shaped durable entry point: `Bridge.scope().launch("name") { ... }`. */
    fun scope(): BridgeScope = BridgeScope()

    /** Registers (or re-registers) a durable block after initialize — used by BridgeScope. */
    fun registerDurable(name: String, block: DurableBlock) {
        val rt = requireNotNull(runtime) { "Bridge.initialize() not called" }
        rt.registry.register(name) { DurableWorker(block, rt.durableDeps) }
    }

    /** Late worker registration (used by bridge-compat, which learns classes at enqueue time). */
    fun registerWorker(name: String, factory: () -> BridgeWorker) {
        requireNotNull(runtime) { "Bridge.initialize() not called" }
            .registry.register(name, factory)
    }

    /** Journal completion signal for [io.github.iamjosephmj.bridge.api.DurableHandle.join]. */
    internal fun addJournalListener(listener: (WorkEvent) -> Unit): AutoCloseable =
        requireNotNull(runtime) { "Bridge.initialize() not called" }.journal.addListener(listener)

    /** Test hook: how many journal listeners are currently registered. */
    internal fun journalListenerCount(): Int =
        (runtime?.journal as? Journal)?.listenerCount() ?: 0

    fun state(name: String): WorkState? = runtime?.journal?.state(name)
    fun events(name: String): List<WorkEvent> = runtime?.journal?.events(name) ?: emptyList()

    fun cancel(name: String) {
        val rt = runtime ?: return
        rt.journal.append(WorkEvent.Cancelled(name, rt.clock.now()))
        try { rt.gateway.cancel(name) } catch (_: Exception) { }   // ends a periodic series
    }

    fun reconcileIfInitialized() { runtime?.dispatcher?.dispatchAll() }

    /** Test hook: tears the singleton down so a fresh initialize() can run. */
    @Synchronized
    fun reset() {
        runtime?.journal?.close()
        runtime = null
        BridgeServices.runner = null
    }
}
