package io.github.iamjosephmj.bridge.signals

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager

/**
 * The platform signal sources. Every source returns [SignalValue.Unknown] below its
 * API floor; exceptions are caught by [SignalHub], not here.
 */
object AndroidSignalSources {
    /** @param jobNamespaces JobScheduler namespaces to scan for pending-job reasons;
     *  null means the default namespace (where WorkManager's jobs live). */
    fun all(context: Context,
            jobNamespaces: List<String?> = listOf("bridge", "bridge-1to1")): List<SignalSource> {
        val app = context.applicationContext
        return listOf(
            PendingReasonsSource(app, jobNamespaces), StandbyBucketSource(app), BgRestrictedSource(app),
            DataSaverSource(app), DozeSource(app), MaintenanceWindowSource(),
            NetworkValidatedSource(app), BattOptExemptSource(app), ExitInfoSignalSource(app),
            ThermalSource(app), ChargeTimeSource(app), ThreadPressureSource(),
        )
    }
}

class PendingReasonsSource(
    private val context: Context,
    private val jobNamespaces: List<String?> = listOf(null),
) : SignalSource {
    override val kind = SignalKind.PENDING_REASONS
    override fun read(): SignalValue {
        if (Build.VERSION.SDK_INT < 34) return SignalValue.Unknown
        val js = context.getSystemService(JobScheduler::class.java) ?: return SignalValue.Unknown
        val reasons = jobNamespaces.flatMap { ns ->
            val scoped = if (ns == null) js else js.forNamespace(ns)
            scoped.allPendingJobs.flatMap { job ->
                if (Build.VERSION.SDK_INT >= 36) scoped.getPendingJobReasons(job.id).toList()
                else listOf(scoped.getPendingJobReason(job.id))
            }
        }.distinct().filter { it != JobScheduler.PENDING_JOB_REASON_UNDEFINED }
        return SignalValue.PendingReasons(reasons)
    }
}

internal class StandbyBucketSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.STANDBY_BUCKET
    override fun read(): SignalValue {
        if (Build.VERSION.SDK_INT < 28) return SignalValue.Unknown
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return SignalValue.Unknown
        return SignalValue.Bucket(usm.appStandbyBucket)
    }
}

internal class BgRestrictedSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.BG_RESTRICTED
    override fun read(): SignalValue {
        if (Build.VERSION.SDK_INT < 28) return SignalValue.Unknown
        val am = context.getSystemService(ActivityManager::class.java) ?: return SignalValue.Unknown
        return SignalValue.Flag(am.isBackgroundRestricted)
    }
}

internal class DataSaverSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.DATA_SAVER
    override fun read(): SignalValue {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return SignalValue.Unknown
        return SignalValue.Flag(
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
    }
}

internal class DozeSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.DOZE
    override fun read(): SignalValue {
        val pm = context.getSystemService(PowerManager::class.java) ?: return SignalValue.Unknown
        return SignalValue.Doze(when {
            pm.isDeviceIdleMode -> DozeMode.DEEP
            Build.VERSION.SDK_INT >= 33 && pm.isDeviceLightIdleMode -> DozeMode.LIGHT
            else -> DozeMode.NONE
        })
    }
}

/**
 * Derived signal: flipped true by [SignalBroadcasts] for a short window after deep idle
 * exits (the classic maintenance-window shape). Default false.
 */
internal class MaintenanceWindowSource : SignalSource {
    override val kind = SignalKind.MAINTENANCE_WINDOW
    @Volatile var inWindow: Boolean = false
    override fun read(): SignalValue = SignalValue.Flag(inWindow)
}

internal class NetworkValidatedSource(context: Context) : SignalSource {
    override val kind = SignalKind.NETWORK_VALIDATED
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    override fun read(): SignalValue {
        val cm = cm ?: return SignalValue.Unknown
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return SignalValue.Flag(false)
        return SignalValue.Flag(
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }
}

internal class BattOptExemptSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.BATT_OPT_EXEMPT
    override fun read(): SignalValue {
        val pm = context.getSystemService(PowerManager::class.java) ?: return SignalValue.Unknown
        return SignalValue.Flag(pm.isIgnoringBatteryOptimizations(context.packageName))
    }
}

internal class ThermalSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.THERMAL
    override fun read(): SignalValue {
        if (Build.VERSION.SDK_INT < 29) return SignalValue.Unknown
        val pm = context.getSystemService(PowerManager::class.java) ?: return SignalValue.Unknown
        return SignalValue.Count(pm.currentThermalStatus)
    }
}

internal class ChargeTimeSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.CHARGE_TIME
    override fun read(): SignalValue {
        if (Build.VERSION.SDK_INT < 28) return SignalValue.Unknown
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
            ?: return SignalValue.Unknown
        val ms = bm.computeChargeTimeRemaining()
        return if (ms < 0) SignalValue.Unknown else SignalValue.Count((ms / 60_000L).toInt())
    }
}

internal class ThreadPressureSource(
    private val taskDir: java.io.File = java.io.File("/proc/self/task"),
) : SignalSource {
    override val kind = SignalKind.THREAD_PRESSURE
    override fun read(): SignalValue = parseRunnableThreads(taskDir)

    companion object {
        /**
         * Counts this process's threads in state R (running/runnable) — actual CPU
         * contention, not total thread count (a process can idle at 80 threads with 2
         * runnable). Own-process /proc/self/task is readable on all supported APIs.
         */
        fun parseRunnableThreads(taskDir: java.io.File): SignalValue {
            val tasks = taskDir.listFiles() ?: return SignalValue.Unknown
            var runnable = 0
            var parsedAny = false
            for (task in tasks) {
                val stat = try { java.io.File(task, "stat").readText() }
                catch (_: Exception) { continue }
                // /proc/<tid>/stat: "pid (comm) S ..." — comm may itself contain spaces
                // and parens, so the state field is 2 chars past the LAST ')'.
                val close = stat.lastIndexOf(')')
                if (close < 0 || close + 2 >= stat.length) continue
                parsedAny = true
                if (stat[close + 2] == 'R') runnable++
            }
            return if (parsedAny) SignalValue.Count(runnable) else SignalValue.Unknown
        }
    }
}

internal class ExitInfoSignalSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.PROCESS_DEATH
    override fun read(): SignalValue {
        if (Build.VERSION.SDK_INT < 30) return SignalValue.Unknown
        val am = context.getSystemService(ActivityManager::class.java) ?: return SignalValue.Unknown
        val latest: ApplicationExitInfo = am
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull() ?: return SignalValue.Flag(false)
        return SignalValue.Death(latest.reason, latest.timestamp)
    }
}
