package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build

object JobPlanCompiler {
    fun jobInfo(context: Context, hostClass: HostJobClass,
                serviceComponent: ComponentName): JobInfo {
        val b = JobInfo.Builder(hostClass.jobId, serviceComponent)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(false)   // reconciler reschedules; WorkManager-proven pattern
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
        }
        if (Build.VERSION.SDK_INT >= 35) b.setTraceTag("bridge:${hostClass.name.lowercase()}")
        return b.build()
    }
}
