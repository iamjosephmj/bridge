package io.github.iamjosephmj.bridge

import android.content.Context
import io.github.iamjosephmj.bridge.api.BridgeScope
import io.github.iamjosephmj.bridge.api.DurableBlock
import io.github.iamjosephmj.bridge.api.DurableDeps
import io.github.iamjosephmj.bridge.api.DurableWorker
import io.github.iamjosephmj.bridge.api.WorkRequest
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.WorkerRegistry
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

object Bridge {
    private var journal: EventJournal? = null
    private var dispatcher: Dispatcher? = null
    private var clock: BridgeClock = SystemBridgeClock()
    private var registry: WorkerRegistry? = null
    private var durableDeps: DurableDeps? = null
    private var gateway: JobGateway? = null
    private var signalHub: SignalHub? = null
    private var signalLog: SignalLog? = null
    private var conformance: Conformance? = null

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
        if (journal != null) return   // idempotent
        val b = BridgeConfigBuilder().apply(block)
        val appContext = context.applicationContext
        clock = b.clock
        val j = Journal(appContext, b.dbName,
            b.ioExecutor ?: Executors.newSingleThreadExecutor())
        val conf = Conformance(appContext.getSharedPreferences(
            "bridge.conformance", Context.MODE_PRIVATE))
        conformance = conf
        val gw = b.gateway ?: SelectingJobGateway(
            SystemJobGateway(appContext), OneToOneJobGateway(appContext), conf)
        val log = SignalLog(b.transitionStore ?: SqliteTransitionStore(appContext))
        val sources = b.signalSources ?: AndroidSignalSources.all(appContext)
        val hub = SignalHub(sources, log, b.clock)
        signalLog = log; signalHub = hub
        registry = b.registry
        gateway = gw
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
        durableDeps = deps
        for ((name, block) in b.durables) {
            b.registry.register(name) { DurableWorker(block, deps) }
        }
        val runner = WorkRunner(j, b.registry, SystemBlackBox(appContext),
            b.costMeter ?: HealthStatsCostMeter(appContext), b.clock)
        BridgeServices.runner = runner
        journal = j; dispatcher = d
        Reconciler(j, d,
            DeathAttributor(j, b.deathSource ?: SystemProcessDeathSource(appContext), b.clock),
            ForceStopDetector(appContext), gw, b.clock).reconcile()
        pokeHub()   // reconciliation is a scheduling decision
    }

    /** Diagnostics must never break scheduling. */
    private fun pokeHub() {
        try { signalHub?.snapshot(Trigger.SCHEDULING_DECISION) } catch (_: Exception) { }
    }

    fun enqueue(request: WorkRequest): String {
        val j = requireNotNull(journal) { "Bridge.initialize() not called" }
        val existing = j.state(request.name)
        if (existing != null && existing.runState in
            setOf(RunState.ENQUEUED, RunState.DISPATCHED, RunState.RUNNING)) {
            return request.name   // KEEP
        }
        val generation = (existing?.generation ?: 0) + 1
        j.append(WorkEvent.Enqueued(request.name, clock.now(), request.workerName, generation,
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
        dispatcher!!.dispatch(request.name)
        pokeHub()
        return request.name
    }

    private fun unknownVerdict(name: String, note: String) = Verdict(
        workId = name, state = RunState.UNKNOWN, diagnosis = Diagnosis.UnknownWork,
        contributing = emptyList(), evidence = emptyList(), basis = Basis.INFERRED,
        pendingSinceMs = null, notes = listOf(note))

    /** Why hasn't this work run? Total: unknown names get an UnknownWork verdict. */
    fun whyPending(name: String): Verdict {
        val j = journal ?: return unknownVerdict(name, "Bridge not initialized")
        val hub = signalHub ?: return unknownVerdict(name, "Bridge not initialized")
        val state = j.state(name) ?: return unknownVerdict(name, "no work named '$name'")
        val events = j.events(name)
        val snapshot = try { hub.snapshot(Trigger.DIAGNOSIS) } catch (e: Exception) {
            SignalSnapshot(clock.now(), emptyMap())
        }
        val enqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>()
            .lastOrNull { it.generation == state.generation }?.at ?: 0L
        val slice = try { signalLog?.slice(enqueuedAt, clock.now()) } catch (e: Exception) { null }
        return Diagnoser.diagnose(state, events, snapshot, slice)
            ?: unknownVerdict(name, "no work named '$name'")
    }

    /** Full per-attempt run history with device context. Total: unknown names → empty runs. */
    fun ledger(name: String): Ledger {
        val j = journal ?: return Ledger(name, emptyList())
        val events = j.events(name)
        if (events.isEmpty()) return Ledger(name, emptyList())
        return LedgerFold.fold(name, events) { from, to ->
            try { signalLog?.slice(from, to) } catch (e: Exception) { null }
        }
    }

    /** One line per known work item; process-level conformance + signal-log health. */
    fun report(): BridgeReport {
        val j = journal ?: return BridgeReport(emptyList(), "UNKNOWN", 0 to null)
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
            conformanceMode = conformance?.mode?.name ?: "UNKNOWN",
            signalLogHealth = try { signalLog?.health() ?: (0 to null) } catch (e: Exception) { 0 to null },
            costFlags = flags)
    }

    /** Starts an instance of a durable block registered via the config builder. */
    fun durable(name: String): String = enqueue(workRequest(name, name))

    /** Coroutine-shaped durable entry point: `Bridge.scope().launch("name") { ... }`. */
    fun scope(): BridgeScope = BridgeScope()

    /** Registers (or re-registers) a durable block after initialize — used by BridgeScope. */
    fun registerDurable(name: String, block: DurableBlock) {
        val deps = requireNotNull(durableDeps) { "Bridge.initialize() not called" }
        requireNotNull(registry).register(name) { DurableWorker(block, deps) }
    }

    /** Late worker registration (used by bridge-compat, which learns classes at enqueue time). */
    fun registerWorker(name: String, factory: () -> BridgeWorker) {
        requireNotNull(registry) { "Bridge.initialize() not called" }.register(name, factory)
    }

    fun state(name: String): WorkState? = journal?.state(name)
    fun events(name: String): List<WorkEvent> = journal?.events(name) ?: emptyList()

    fun cancel(name: String) {
        journal?.append(WorkEvent.Cancelled(name, clock.now()))
        try { gateway?.cancel(name) } catch (_: Exception) { }   // ends a periodic series
    }

    fun reconcileIfInitialized() { dispatcher?.dispatchAll() }

    /** Test hook: tears the singleton down so a fresh initialize() can run. */
    @Synchronized
    fun reset() {
        journal?.close(); journal = null; dispatcher = null
        signalHub = null; signalLog = null; conformance = null; registry = null
        durableDeps = null; gateway = null
        BridgeServices.runner = null
    }
}
