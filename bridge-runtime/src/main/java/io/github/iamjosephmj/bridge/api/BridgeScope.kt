package io.github.iamjosephmj.bridge.api

import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.diagnostics.Verdict
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Coroutine-shaped entry point for durable work: `Bridge.scope().launch("name") { ... }`.
 *
 * `launch` registers (or re-registers) the block and starts an instance with KEEP
 * semantics — launching a name whose work is already live is a no-op enqueue but still
 * re-registers the block, which is exactly what replay-after-death needs. For that
 * recovery to work, the launch call must live on a path that runs at every process
 * start (e.g. `Application.onCreate`), the same reachability rule WorkManager places
 * on its worker classes.
 *
 * The scope is also a plain [CoroutineScope] so callers can `scope.launch { handle.join() }`
 * style-compose around durables. Its context is `SupervisorJob() + Dispatchers.Default`,
 * deliberately *not* [BridgeDispatcher]: BridgeDispatcher needs per-run context (a workId,
 * a black box, and the host's stop signal) that only exists inside a running durable
 * attempt — coroutines launched directly on this scope are ordinary in-process helpers
 * with no durability of their own.
 *
 * Reattachment model, honestly: a [DurableHandle] is a *view* onto journal state, not an
 * owner of the work. The durable itself lives in the journal and survives process death;
 * every in-memory object here (this scope, its Job, its handles) does not. After a
 * restart, calling `launch` with the same name reattaches — KEEP finds the live journal
 * entry and the returned handle observes the same work.
 */
class BridgeScope internal constructor() : CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default

    private val launched = LinkedHashSet<String>()   // guarded by itself

    fun launch(
        name: String,
        constraints: WorkRequestBuilder.() -> Unit = {},
        block: DurableBlock,
    ): DurableHandle {
        if (Bridge.isInitialized) {
            Bridge.registerDurable(name, block)
            Bridge.enqueue(workRequest(name, name, constraints))
        } else {
            // Pre-init tolerance for Bridge.initializeAsync(): defer register + enqueue
            // until the runtime is ready. The handle stays valid immediately — it is only
            // a view onto journal state, and its queries degrade gracefully until then.
            CoroutineScope(coroutineContext).launch {
                Bridge.awaitReady()
                Bridge.registerDurable(name, block)
                Bridge.enqueue(workRequest(name, name, constraints))
            }
        }
        synchronized(launched) { launched += name }
        return DurableHandle(name)
    }

    /**
     * Cancels every durable this scope launched (journal-side, via [Bridge.cancel]) and
     * cancels the scope's in-memory [Job], so any coroutines launched on the scope —
     * `join()` waiters and the like — unwind too. The scope is dead afterwards, matching
     * structured-concurrency `cancel()` semantics; get a fresh one from [Bridge.scope].
     * Note the asymmetry this implies: the journal-side cancellation is durable, the
     * Job cancellation is process-local only.
     */
    fun cancelAll() {
        val names = synchronized(launched) { launched.toList() }
        for (name in names) Bridge.cancel(name)
        coroutineContext[Job]?.cancel(CancellationException("BridgeScope.cancelAll()"))
    }
}

private val TERMINAL = setOf(RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED)

/**
 * Handle to a launched durable — a view onto journal state, not an owner of the work
 * (see [BridgeScope] for the reattachment model). Queries delegate to the Bridge facade.
 */
class DurableHandle internal constructor(val name: String) {
    fun state(): WorkState? = Bridge.state(name)
    fun whyPending(): Verdict = Bridge.whyPending(name)
    fun cancel() = Bridge.cancel(name)

    /** Suspends until the durable reaches a terminal state. Returns immediately if it
     * already has. Cancellation-safe: cancelling the waiter removes its journal listener. */
    suspend fun join() { await() }

    /** Like [join], but returns the terminal [RunState] (SUCCEEDED / FAILED / CANCELLED). */
    suspend fun await(): RunState {
        Bridge.awaitReady()   // tolerate initializeAsync(): don't touch the journal pre-init
        terminalOrNull()?.let { return it }
        return suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            val registration = AtomicReference<AutoCloseable?>(null)
            fun finish(rs: RunState) {
                if (resumed.compareAndSet(false, true)) {
                    registration.getAndSet(null)?.close()
                    cont.resume(rs)
                }
            }
            val reg = Bridge.addJournalListener { event ->
                if (event.workId != name) return@addJournalListener
                terminalOrNull()?.let(::finish)
            }
            registration.set(reg)
            // The listener may already have fired (and found registration unset) —
            // and the work may have completed between the fast path and registration.
            // Both races collapse into: re-check now that we are subscribed.
            if (resumed.get()) reg.close() else terminalOrNull()?.let(::finish)
            cont.invokeOnCancellation { registration.getAndSet(null)?.close() }
        }
    }

    private fun terminalOrNull(): RunState? =
        Bridge.state(name)?.runState?.takeIf { it in TERMINAL }
}
