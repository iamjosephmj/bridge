package io.github.iamjosephmj.bridge.compat

import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.BridgeData
import io.github.iamjosephmj.bridge.api.ChunkedWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.store.RunState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Compat links execute as chunks of one Bridge work item, so a chain interrupted at
 * link N resumes at link N via the chunk ledger — the native vocabulary paying for the
 * façade (M4 spec §1).
 *
 * Data relay: each link's inputData = its request's input, overwritten by upstream
 * outputs (OverwritingInputMerger semantics). Upstream means ctx.input — which carries
 * the item input, DAG prerequisite outputs, AND chunk outputs journaled before a death —
 * plus the in-memory carry of links completed in this run. Link outputs are journaled
 * per chunk (ctx.setOutput before Success), so resume-at-link-N still sees them.
 */
internal class CompatChainWorker(
    private val links: List<Class<out Worker>>,
    private val inputs: List<Data> = emptyList(),
) : ChunkedWorker {
    private var carry: Map<String, String> = emptyMap()

    override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
        val worker = links[chunkIndex].getDeclaredConstructor().newInstance()
        val requestInput = inputs.getOrNull(chunkIndex)?.map ?: emptyMap()
        worker.inputData = Data(requestInput + ctx.input.asMap + carry)
        return when (val r = worker.doWork()) {
            is Worker.Result.Success -> {
                carry = carry + r.outputData.map
                ctx.setOutput(BridgeData.of(r.outputData.map))
                RunResult.Success
            }
            is Worker.Result.Retry -> RunResult.Retry
            is Worker.Result.Failure -> {
                ctx.setOutput(BridgeData.of(r.outputData.map))
                RunResult.Failure
            }
        }
    }
}

class WorkContinuation internal constructor(
    private val manager: BridgeWorkManager,
    internal val name: String,
    private val policy: ExistingWorkPolicy,
    private val requests: MutableList<OneTimeWorkRequest>,
    private val branches: List<WorkContinuation> = emptyList(),
) {
    fun then(request: OneTimeWorkRequest): WorkContinuation = apply { requests += request }

    fun enqueue(): String {
        branches.forEach { it.enqueue() }
        return manager.enqueueChain(name, policy, requests,
            prereqs = branches.map { it.name })
    }

    companion object {
        /**
         * Multi-branch join (androidx.work.WorkContinuation.combine): the branches run
         * independently; work chained after the combined continuation dispatches only
         * once every branch has SUCCEEDED, with branch outputs overwrite-merged into
         * its input. The join item is named `<branch names joined by '+'>:join`.
         */
        @JvmStatic
        fun combine(continuations: List<WorkContinuation>): WorkContinuation {
            require(continuations.size >= 2) { "combine needs at least two continuations" }
            val joinName = continuations.joinToString("+") { it.name } + ":join"
            return WorkContinuation(BridgeWorkManager, joinName,
                ExistingWorkPolicy.KEEP, mutableListOf(), branches = continuations)
        }
    }
}

object BridgeWorkManager {

    /** Compat entry point; Bridge.initialize must have run (compat registers its workers lazily). */
    @JvmStatic fun getInstance(): BridgeWorkManager = this

    fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy,
                          request: OneTimeWorkRequest): String =
        enqueueChain(name, policy, mutableListOf(request))

    fun beginUniqueWork(name: String, policy: ExistingWorkPolicy,
                        request: OneTimeWorkRequest): WorkContinuation =
        WorkContinuation(this, name, policy, mutableListOf(request))

    fun enqueueUniquePeriodicWork(name: String, policy: ExistingPeriodicWorkPolicy,
                                  request: PeriodicWorkRequest): String {
        if (policy == ExistingPeriodicWorkPolicy.UPDATE) Bridge.cancel(name)
        val workerName = "compat:" + request.workerClass.name
        Bridge.registerWorker(workerName) {
            CompatChainWorker(listOf(request.workerClass), listOf(request.inputData)) }
        return Bridge.enqueue(workRequest(name, workerName) {
            applyConstraints(listOf(request.constraints))
            periodic(request.intervalMs)
            chunks(1)
            if (request.tags.isNotEmpty()) tag(*request.tags.toTypedArray())
        })
    }

    internal fun enqueueChain(name: String, policy: ExistingWorkPolicy,
                              requests: List<OneTimeWorkRequest>,
                              prereqs: List<String> = emptyList()): String {
        require(requests.isNotEmpty()) { "a chain needs at least one request (call then())" }
        if (policy == ExistingWorkPolicy.REPLACE) Bridge.cancel(name)
        val links = requests.map { it.workerClass }
        val inputs = requests.map { it.inputData }
        val workerName = "compat:" + links.joinToString(",") { it.name }
        Bridge.registerWorker(workerName) { CompatChainWorker(links, inputs) }
        val delay = requests.maxOf { it.initialDelayMs }
        val allTags = requests.flatMap { it.tags }.distinct()
        return Bridge.enqueue(workRequest(name, workerName) {
            applyConstraints(requests.map { it.constraints })
            if (delay > 0) initialDelay(delay)
            chunks(links.size)
            if (allTags.isNotEmpty()) tag(*allTags.toTypedArray())
            if (prereqs.isNotEmpty()) after(*prereqs.toTypedArray())
        })
    }

    private fun io.github.iamjosephmj.bridge.api.WorkRequestBuilder.applyConstraints(
        all: List<Constraints>) {
        if (all.any { it.requiresCharging }) charging()
        if (all.any { it.requiredNetworkType == NetworkType.UNMETERED }) unmetered()
        else if (all.any { it.requiredNetworkType == NetworkType.CONNECTED }) network()
        if (all.any { it.requiresBatteryNotLow }) batteryNotLow()
        if (all.any { it.requiresStorageNotLow }) storageNotLow()
        if (all.any { it.requiresDeviceIdle }) deviceIdle()
        val triggers = all.flatMap { it.contentUriTriggers }
        if (triggers.isNotEmpty()) {
            contentTrigger(*triggers.map { it.uri }.distinct().toTypedArray(),
                descendants = triggers.any { it.triggerForDescendants },
                updateDelayMs = all.maxOf { it.contentTriggerUpdateDelayMs },
                maxDelayMs = all.maxOf { it.contentTriggerMaxDelayMs })
        }
    }

    fun getWorkInfoState(name: String): WorkInfoState? = mapState(Bridge.state(name)?.runState)

    /** The work's state as a Flow, re-emitted on every journal event (null = unknown name). */
    fun getWorkInfoStateFlow(name: String): Flow<WorkInfoState?> =
        Bridge.stateFlow(name).map { mapState(it?.runState) }.distinctUntilChanged()

    /** Latest worker output for the name (chains: the last completed link's output). */
    fun getOutputData(name: String): Data =
        Data(Bridge.state(name)?.lastOutput ?: emptyMap())

    fun cancelUniqueWork(name: String) = Bridge.cancel(name)

    fun cancelAllWorkByTag(tag: String) = Bridge.cancelAllByTag(tag)

    private fun mapState(s: RunState?): WorkInfoState? = when (s) {
        RunState.ENQUEUED, RunState.DISPATCHED -> WorkInfoState.ENQUEUED
        RunState.RUNNING -> WorkInfoState.RUNNING
        RunState.SUCCEEDED -> WorkInfoState.SUCCEEDED
        RunState.FAILED -> WorkInfoState.FAILED
        RunState.CANCELLED -> WorkInfoState.CANCELLED
        RunState.UNKNOWN, null -> null
    }
}
