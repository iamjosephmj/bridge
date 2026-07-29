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
                extras: PersistableBundle? = null,
                itemConstraints: ItemConstraints? = null): JobInfo {
        val b = JobInfo.Builder(jobId, serviceComponent)
            .setPersisted(false)   // reconciler reschedules; WorkManager-proven pattern
        // Platform rule: an idle-mode job ignores backoff and build() rejects the combo.
        if (itemConstraints?.deviceIdle != true) {
            b.setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        }
        if (extras != null) b.setExtras(extras)
        if (itemConstraints != null) {
            // 1:1 path with an exact per-item constraint set — host-class shape ignored.
            when (itemConstraints.network) {
                1 -> b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                2 -> b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            }
            if (itemConstraints.charging) b.setRequiresCharging(true)
            if (itemConstraints.batteryNotLow) b.setRequiresBatteryNotLow(true)
            if (itemConstraints.storageNotLow) b.setRequiresStorageNotLow(true)
            if (itemConstraints.deviceIdle) b.setRequiresDeviceIdle(true)
            val empty = itemConstraints.network == 0 && !itemConstraints.charging &&
                !itemConstraints.batteryNotLow && !itemConstraints.storageNotLow &&
                !itemConstraints.deviceIdle
            if (empty) b.setMinimumLatency(1L)   // build() rejects zero constraints
            if (Build.VERSION.SDK_INT >= 35) b.setTraceTag("bridge:exact")
            return b.build()
        }
        when (hostClass) {
            HostJobClass.DEFAULT ->
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            HostJobClass.NO_NETWORK ->
                // Deliberately constraint-free — but JobInfo.build() (real devices, not
                // Robolectric) rejects a job with no constraint at all, so declare a 1ms
                // minimum latency: counts as an "early constraint", delays nothing.
                b.setMinimumLatency(1L)
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
