package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobWorkItem
import io.github.iamjosephmj.bridge.exec.RunOutcome
import io.github.iamjosephmj.bridge.exec.WorkRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Wiring seam: Bridge.initialize() (Task 11) populates this before any job can run. */
object BridgeServices {
    @Volatile var runner: WorkRunner? = null
}

/**
 * Drains a work queue by repeatedly dequeuing an item, running it, and completing it
 * on a terminal outcome. Extracted from [BridgeJobService] so the loop is testable without
 * a real [JobService] / Robolectric JobScheduler round-trip.
 *
 * Returns `true` (wantsReschedule) as soon as a [RunOutcome.RETRY] is hit; draining stops
 * immediately in that case so the item is redelivered by the platform rather than re-run here.
 */
class WorkQueueDrainer(
    private val runner: WorkRunner,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    suspend fun drain(
        dequeue: () -> Pair<WorkItemPayload, Int>?,
        complete: (WorkItemPayload) -> Unit,
        isStopped: () -> Boolean,
    ): Boolean {
        var wantsReschedule = false
        while (!isStopped()) {
            val (payload, deliveryCount) = dequeue() ?: break
            when (runner.run(payload.workId, payload.generation, deliveryCount, isStopped)) {
                RunOutcome.COMPLETED, RunOutcome.FAILED -> complete(payload)
                RunOutcome.RETRY -> {
                    wantsReschedule = true
                    break
                }
            }
        }
        return wantsReschedule
    }
}

/**
 * All per-job-dispatch mutable state: its own coroutine scope (so cancelling one job's drain
 * can never cancel another job's drain), its own stop flag, and its own in-flight
 * workId -> JobWorkItem map (so completeWork always receives the instance dequeueWork gave it,
 * scoped to the job that dequeued it).
 */
class JobExecution(
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val stopped = AtomicBoolean(false)
    val inFlight = ConcurrentHashMap<String, JobWorkItem>()
}

/**
 * Registry of in-flight [JobExecution]s keyed by `JobParameters.jobId`. A single
 * [BridgeJobService] component instance can receive concurrent onStartJob/onStopJob calls for
 * distinct job ids (e.g. multiple host job classes all resolving to the same component); this
 * registry ensures state for one job id is never shared with, or torn down by, another.
 */
class JobExecutions {
    private val executions = ConcurrentHashMap<Int, JobExecution>()

    /** Creates and registers a fresh execution for [jobId], replacing (and cancelling) any stale one. */
    fun start(jobId: Int): JobExecution {
        val execution = JobExecution()
        executions.put(jobId, execution)?.scope?.cancel()
        return execution
    }

    fun get(jobId: Int): JobExecution? = executions[jobId]

    /** Marks [jobId]'s execution stopped, cancels its scope, and removes it from the registry. */
    fun stop(jobId: Int): JobExecution? {
        val execution = executions.remove(jobId) ?: return null
        execution.stopped.set(true)
        execution.scope.cancel()
        return execution
    }

    /** Removes [jobId]'s execution after it drains to completion normally (not via stop()). */
    fun finish(jobId: Int) {
        executions.remove(jobId)
    }
}

/**
 * Real dequeueWork drain loop. Kept thin: all branching semantics live in [WorkQueueDrainer],
 * this class only adapts JobScheduler's [JobWorkItem] API to the drainer's plain-data seam
 * (dequeue/complete/isStopped) and manages per-jobId coroutine lifecycles via [JobExecutions].
 */
class BridgeJobService : JobService() {
    private val executions = JobExecutions()

    override fun onStartJob(params: JobParameters): Boolean {
        val runner = BridgeServices.runner ?: return false // not initialized; drop the job
        val execution = executions.start(params.jobId)
        execution.scope.launch {
            try {
                val wantsReschedule = WorkQueueDrainer(runner, execution.scope).drain(
                    dequeue = { nextItem(params, execution) },
                    complete = { payload -> completePending(params, execution, payload) },
                    isStopped = { execution.stopped.get() },
                )
                if (!execution.stopped.get()) {
                    executions.finish(params.jobId)
                    jobFinished(params, wantsReschedule)
                }
            } catch (e: CancellationException) {
                // This job's own cancellation only (its scope, not any other job's): either
                // onStopJob already removed it from the registry and requested reschedule via
                // its own return true, or the worker itself threw cancellation. Either way,
                // jobFinished must NOT be called here, and no other job's state is touched.
            }
        }
        return true
    }

    private fun nextItem(params: JobParameters, execution: JobExecution): Pair<WorkItemPayload, Int>? {
        val item = try {
            params.dequeueWork()
        } catch (e: Exception) {
            null
        } ?: return null

        val workId = item.intent.getStringExtra(EXTRA_WORK_ID)
        if (workId == null) {
            // Malformed item: nothing we can run against. Complete it immediately so it
            // doesn't jam the queue, and skip it.
            try { params.completeWork(item) } catch (e: Exception) { /* job already gone */ }
            return null
        }

        execution.inFlight[workId] = item
        val generation = item.intent.getIntExtra(EXTRA_GENERATION, 0)
        return WorkItemPayload(workId, generation) to item.deliveryCount
    }

    private fun completePending(params: JobParameters, execution: JobExecution, payload: WorkItemPayload) {
        execution.inFlight.remove(payload.workId)?.let {
            try { params.completeWork(it) } catch (e: Exception) { /* job already gone */ }
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        executions.stop(params.jobId)
        return true // reschedule: undelivered items redeliver with a bumped deliveryCount
    }
}
