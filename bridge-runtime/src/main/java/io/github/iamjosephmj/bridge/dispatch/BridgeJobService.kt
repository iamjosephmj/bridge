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
 * Real dequeueWork drain loop. Kept thin: all branching semantics live in [WorkQueueDrainer],
 * this class only adapts JobScheduler's [JobWorkItem] API to the drainer's plain-data seam
 * (dequeue/complete/isStopped) and manages the coroutine lifecycle.
 */
class BridgeJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopped = AtomicBoolean(false)

    // dequeueWork() returns a JobWorkItem instance that completeWork() must receive back
    // unchanged; track the in-flight items by workId so complete() (a WorkItemPayload lambda)
    // can look up the original item.
    private val inFlight = ConcurrentHashMap<String, JobWorkItem>()

    override fun onStartJob(params: JobParameters): Boolean {
        val runner = BridgeServices.runner ?: return false // not initialized; drop the job
        stopped.set(false)
        scope.launch {
            try {
                val wantsReschedule = WorkQueueDrainer(runner, scope).drain(
                    dequeue = { nextItem(params) },
                    complete = { payload -> completePending(params, payload) },
                    isStopped = { stopped.get() },
                )
                if (!stopped.get()) jobFinished(params, wantsReschedule)
            } catch (e: CancellationException) {
                // Normal shutdown: onStopJob already cancelled the scope and returned true
                // (reschedule) to the platform. jobFinished must NOT be called here.
            }
        }
        return true
    }

    private fun nextItem(params: JobParameters): Pair<WorkItemPayload, Int>? {
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

        inFlight[workId] = item
        val generation = item.intent.getIntExtra(EXTRA_GENERATION, 0)
        return WorkItemPayload(workId, generation) to item.deliveryCount
    }

    private fun completePending(params: JobParameters, payload: WorkItemPayload) {
        inFlight.remove(payload.workId)?.let {
            try { params.completeWork(it) } catch (e: Exception) { /* job already gone */ }
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true)
        scope.cancel()
        return true // reschedule: undelivered items redeliver with a bumped deliveryCount
    }
}
