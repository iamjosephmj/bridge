package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

data class WorkItemPayload(val workId: String, val generation: Int)

const val EXTRA_WORK_ID = "bridge.EXTRA_WORK_ID"
const val EXTRA_GENERATION = "bridge.EXTRA_GENERATION"

interface JobGateway {
    fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean
    fun cancelAll()
}

class SystemJobGateway(private val context: Context) : JobGateway {
    private val scheduler: JobScheduler = run {
        val js = context.getSystemService(JobScheduler::class.java)
        if (Build.VERSION.SDK_INT >= 34) js.forNamespace("bridge") else js
    }
    private val component = ComponentName(context, BridgeJobService::class.java)

    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        val intent = Intent()
            .putExtra(EXTRA_WORK_ID, payload.workId)
            .putExtra(EXTRA_GENERATION, payload.generation)
        val info = JobPlanCompiler.jobInfo(context, hostClass, component)
        return try {
            scheduler.enqueue(info, JobWorkItem(intent)) == JobScheduler.RESULT_SUCCESS
        } catch (e: Exception) {   // OEM IllegalStateException / limit exceeded → conformance fallback signal
            false
        }
    }

    override fun cancelAll() { scheduler.cancelAll() }
}

/**
 * 1:1 fallback path for devices whose JobScheduler can't sustain the multiplexed
 * (JobWorkItem/dequeueWork) style: one JobInfo per work item instead of one JobInfo shared by
 * many items. jobId is `720_000 + a stable hash of workId` so distinct work items never collide
 * on the same job slot; workId/generation travel in the JobInfo's extras (there is no
 * JobWorkItem to carry them) and BridgeJobService reads them back out in onStartJob.
 */
class OneToOneJobGateway(private val context: Context) : JobGateway {
    private val scheduler: JobScheduler = run {
        val js = context.getSystemService(JobScheduler::class.java)
        if (Build.VERSION.SDK_INT >= 34) js.forNamespace("bridge-1to1") else js
    }
    private val component = ComponentName(context, BridgeJobService::class.java)

    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        val extras = PersistableBundle().apply {
            putString(EXTRA_WORK_ID, payload.workId)
            putInt(EXTRA_GENERATION, payload.generation)
        }
        val info = JobPlanCompiler.jobInfo(context, hostClass, component,
            oneToOneJobId(payload.workId), extras)
        return try {
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    override fun cancelAll() { scheduler.cancelAll() }

    companion object {
        private const val ONE_TO_ONE_JOB_ID_BASE = 720_000

        /**
         * Deterministic per-workId job slot for the 1:1 path, exposed for testing without a
         * JobScheduler round-trip. `workId.hashCode()` is stable (specified by the String
         * javadoc), so the same workId always maps to the same slot.
         */
        fun oneToOneJobId(workId: String): Int =
            ONE_TO_ONE_JOB_ID_BASE + (workId.hashCode() and 0xFFFF)
    }
}

class FakeJobGateway : JobGateway {
    val enqueued = mutableListOf<Pair<HostJobClass, WorkItemPayload>>()
    var failNext = false
    var cancelledAll = false
    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        if (failNext) { failNext = false; return false }
        enqueued += hostClass to payload
        return true
    }
    override fun cancelAll() { cancelledAll = true; enqueued.clear() }
}
