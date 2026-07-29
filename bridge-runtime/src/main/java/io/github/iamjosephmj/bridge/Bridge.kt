package io.github.iamjosephmj.bridge

import android.content.Context
import io.github.iamjosephmj.bridge.api.WorkRequest
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.WorkerRegistry
import io.github.iamjosephmj.bridge.dispatch.*
import io.github.iamjosephmj.bridge.exec.*
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

    fun worker(name: String, factory: () -> BridgeWorker) = registry.register(name, factory)
}

object Bridge {
    private var journal: EventJournal? = null
    private var dispatcher: Dispatcher? = null
    private var clock: BridgeClock = SystemBridgeClock()

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
        val gw = b.gateway ?: SelectingJobGateway(
            SystemJobGateway(appContext),
            OneToOneJobGateway(appContext),
            Conformance(appContext.getSharedPreferences(
                "bridge.conformance", Context.MODE_PRIVATE)))
        val d = Dispatcher(j, gw, b.clock)
        val runner = WorkRunner(j, b.registry, SystemBlackBox(appContext),
            b.costMeter ?: HealthStatsCostMeter(appContext), b.clock)
        BridgeServices.runner = runner
        journal = j; dispatcher = d
        Reconciler(j, d,
            DeathAttributor(j, b.deathSource ?: SystemProcessDeathSource(appContext), b.clock),
            ForceStopDetector(appContext), gw, b.clock).reconcile()
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
        return request.name
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
        BridgeServices.runner = null
    }
}
