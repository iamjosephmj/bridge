package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
        // Platform rules: idle-mode jobs reject backoff; periodic jobs reject latency.
        // A request's backoff(initialMs, policy) overrides the 30s-exponential default;
        // the builder already forbids combining it with deviceIdle or periodic.
        if (itemConstraints?.deviceIdle != true && (itemConstraints?.periodicMs ?: 0L) <= 0L) {
            val initialMs = itemConstraints?.backoffMs?.takeIf { it > 0 } ?: 30_000L
            val policy = if (itemConstraints?.backoffLinear == true)
                JobInfo.BACKOFF_POLICY_LINEAR else JobInfo.BACKOFF_POLICY_EXPONENTIAL
            b.setBackoffCriteria(initialMs, policy)
        }
        if (extras != null) b.setExtras(extras)
        if (itemConstraints != null) {
            // 1:1 path with an exact per-item constraint set — host-class shape ignored.
            when (itemConstraints.network) {
                NetworkNeed.NONE -> Unit
                NetworkNeed.ANY -> b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                NetworkNeed.UNMETERED -> b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            }
            if (itemConstraints.charging) b.setRequiresCharging(true)
            if (itemConstraints.batteryNotLow) b.setRequiresBatteryNotLow(true)
            if (itemConstraints.storageNotLow) b.setRequiresStorageNotLow(true)
            if (itemConstraints.deviceIdle) b.setRequiresDeviceIdle(true)
            for (uri in itemConstraints.contentUris) {
                // Trigger jobs can't be persisted (we never persist) nor periodic (the
                // builder forbids the combination upstream). Backoff is legal with triggers.
                b.addTriggerContentUri(JobInfo.TriggerContentUri(Uri.parse(uri),
                    if (itemConstraints.contentDescendants)
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS else 0))
            }
            if (itemConstraints.contentUpdateDelayMs > 0)
                b.setTriggerContentUpdateDelay(itemConstraints.contentUpdateDelayMs)
            if (itemConstraints.contentMaxDelayMs > 0)
                b.setTriggerContentMaxDelay(itemConstraints.contentMaxDelayMs)
            if (itemConstraints.periodicMs > 0) {
                b.setPeriodic(itemConstraints.periodicMs)
            } else if (itemConstraints.minLatencyMs > 0) {
                b.setMinimumLatency(itemConstraints.minLatencyMs)
            } else {
                val empty = itemConstraints.network == NetworkNeed.NONE && !itemConstraints.charging &&
                    !itemConstraints.batteryNotLow && !itemConstraints.storageNotLow &&
                    !itemConstraints.deviceIdle &&
                    itemConstraints.contentUris.isEmpty()   // a trigger IS a constraint
                if (empty) b.setMinimumLatency(1L)   // build() rejects zero constraints
            }
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
