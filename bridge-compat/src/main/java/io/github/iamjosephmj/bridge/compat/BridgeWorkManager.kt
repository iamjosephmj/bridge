package io.github.iamjosephmj.bridge.compat

import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.ChunkedWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.store.RunState

/**
 * Compat links execute as chunks of one Bridge work item, so a chain interrupted at
 * link N resumes at link N via the chunk ledger — the native vocabulary paying for the
 * façade (M4 spec §1).
 */
internal class CompatChainWorker(private val links: List<Class<out Worker>>) : ChunkedWorker {
    override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult =
        when (links[chunkIndex].getDeclaredConstructor().newInstance().doWork()) {
            is Worker.Result.Success -> RunResult.Success
            is Worker.Result.Retry -> RunResult.Retry
            is Worker.Result.Failure -> RunResult.Failure
        }
}

class WorkContinuation internal constructor(
    private val manager: BridgeWorkManager,
    private val name: String,
    private val policy: ExistingWorkPolicy,
    private val requests: MutableList<OneTimeWorkRequest>,
) {
    fun then(request: OneTimeWorkRequest): WorkContinuation = apply { requests += request }
    fun enqueue() = manager.enqueueChain(name, policy, requests)
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

    internal fun enqueueChain(name: String, policy: ExistingWorkPolicy,
                              requests: List<OneTimeWorkRequest>): String {
        require(requests.isNotEmpty())
        if (policy == ExistingWorkPolicy.REPLACE) Bridge.cancel(name)
        val links = requests.map { it.workerClass }
        val workerName = "compat:" + links.joinToString(",") { it.name }
        Bridge.registerWorker(workerName) { CompatChainWorker(links) }
        val all = requests.map { it.constraints }
        return Bridge.enqueue(workRequest(name, workerName) {
            if (all.any { it.requiresCharging }) charging()
            if (all.any { it.requiredNetworkType == NetworkType.UNMETERED }) unmetered()
            else if (all.any { it.requiredNetworkType == NetworkType.CONNECTED }) network()
            if (all.any { it.requiresBatteryNotLow }) batteryNotLow()
            if (all.any { it.requiresStorageNotLow }) storageNotLow()
            if (all.any { it.requiresDeviceIdle }) deviceIdle()
            chunks(links.size)
        })
    }

    fun getWorkInfoState(name: String): WorkInfoState? = when (Bridge.state(name)?.runState) {
        RunState.ENQUEUED, RunState.DISPATCHED -> WorkInfoState.ENQUEUED
        RunState.RUNNING -> WorkInfoState.RUNNING
        RunState.SUCCEEDED -> WorkInfoState.SUCCEEDED
        RunState.FAILED -> WorkInfoState.FAILED
        RunState.CANCELLED -> WorkInfoState.CANCELLED
        null -> null
    }

    fun cancelUniqueWork(name: String) = Bridge.cancel(name)
}
