package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

object JobPlanCompiler {
    /**
     * @param jobId defaults to [hostClass]'s fixed jobId (the multiplexed path); callers that
     * need one JobInfo per work item (the 1:1 fallback path) pass a per-item jobId instead.
     * @param extras attached verbatim to the built JobInfo (the 1:1 path uses this to carry
     * the workId/generation since it never touches JobWorkItem/dequeueWork).
     */
    fun jobInfo(context: Context, hostClass: HostJobClass,
                serviceComponent: ComponentName, jobId: Int = hostClass.jobId,
                extras: PersistableBundle? = null): JobInfo {
        val b = JobInfo.Builder(jobId, serviceComponent)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(false)   // reconciler reschedules; WorkManager-proven pattern
        if (extras != null) b.setExtras(extras)
        when (hostClass) {
            HostJobClass.DEFAULT ->
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            HostJobClass.DEFERRABLE -> {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                if (Build.VERSION.SDK_INT >= 33) b.setPriority(JobInfo.PRIORITY_LOW)
            }
            HostJobClass.UNMETERED_CHARGING -> {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                b.setRequiresCharging(true)
                if (Build.VERSION.SDK_INT >= 33) b.setPriority(JobInfo.PRIORITY_LOW)
            }
            HostJobClass.EXPEDITED -> {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                // Below 31 there is no expedited tier; the policy engine never picks
                // EXPEDITED there (it journals the skip), so this branch stays honest.
                if (Build.VERSION.SDK_INT >= 31) b.setExpedited(true)
            }
        }
        if (Build.VERSION.SDK_INT >= 35) b.setTraceTag("bridge:${hostClass.name.lowercase()}")
        return b.build()
    }
}
