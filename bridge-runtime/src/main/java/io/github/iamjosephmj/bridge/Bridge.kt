package io.github.iamjosephmj.bridge

import android.content.Context
import io.github.iamjosephmj.bridge.api.WorkRequest
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.WorkerRegistry
import io.github.iamjosephmj.bridge.diagnostics.*
import io.github.iamjosephmj.bridge.dispatch.*
import io.github.iamjosephmj.bridge.exec.*
import io.github.iamjosephmj.bridge.signals.*
import io.github.iamjosephmj.bridge.store.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class BridgeConfigBuilder internal constructor() {
    internal val registry = WorkerRegistry()
    internal var clock: BridgeClock = SystemBridgeClock()
    internal var gateway: JobGateway? = null
    internal var costMeter: CostMeter? = null
    internal var deathSource: ProcessDeathSource? = null
    internal var ioExecutor: Executor? = null
    internal var dbName: String = "bridge.db"
    internal var signalSources: List<SignalSource>? = null
    internal var transitionStore: TransitionStore? = null

    fun worker(name: String, factory: () -> BridgeWorker) = registry.register(name, factory)
}

object Bridge {
    private var journal: EventJournal? = null
    private var dispatcher: Dispatcher? = null
    private var clock: BridgeClock = SystemBridgeClock()
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
        val d = Dispatcher(j, gw, b.clock)
        val log = SignalLog(b.transitionStore ?: SqliteTransitionStore(appContext))
        val sources = b.signalSources ?: AndroidSignalSources.all(appContext)
        val hub = SignalHub(sources, log, b.clock)
        signalLog = log; signalHub = hub
        try { SignalBroadcasts(hub, sources).start(appContext) } catch (_: Exception) { }
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
            maxAttempts = request.maxAttempts))
        dispatcher!!.dispatch(request.name)
        pokeHub()
        return request.name
    }

    /** Why hasn't this work run? Typed diagnosis + evidence; null for unknown names. */
    fun whyPending(name: String): Verdict? {
        val j = journal ?: return null
        val hub = signalHub ?: return null
        val state = j.state(name) ?: return null
        val events = j.events(name)
        val snapshot = try { hub.snapshot(Trigger.DIAGNOSIS) } catch (e: Exception) {
            SignalSnapshot(clock.now(), emptyMap())
        }
        val enqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>()
            .lastOrNull { it.generation == state.generation }?.at ?: 0L
        val slice = try { signalLog?.slice(enqueuedAt, clock.now()) } catch (e: Exception) { null }
        return Diagnoser.diagnose(state, events, snapshot, slice)
    }

    /** Full per-attempt run history with device context. Null for unknown names. */
    fun ledger(name: String): Ledger? {
        val j = journal ?: return null
        val events = j.events(name)
        if (events.isEmpty()) return null
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
                    whyPending(st.workId)?.diagnosis else null)
        }
        return BridgeReport(lines,
            conformanceMode = conformance?.mode?.name ?: "UNKNOWN",
            signalLogHealth = try { signalLog?.health() ?: (0 to null) } catch (e: Exception) { 0 to null })
    }

    fun state(name: String): WorkState? = journal?.state(name)
    fun events(name: String): List<WorkEvent> = journal?.events(name) ?: emptyList()

    fun cancel(name: String) {
        journal?.append(WorkEvent.Cancelled(name, clock.now()))
    }

    fun reconcileIfInitialized() { dispatcher?.dispatchAll() }

    @Synchronized
    internal fun reset() {
        journal?.close(); journal = null; dispatcher = null
        signalHub = null; signalLog = null; conformance = null
        BridgeServices.runner = null
    }
}
