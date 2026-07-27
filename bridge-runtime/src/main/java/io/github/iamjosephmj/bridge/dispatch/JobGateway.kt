package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

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
