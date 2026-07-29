package io.github.iamjosephmj.bridge.signals

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import io.github.iamjosephmj.bridge.dispatch.HostJobClass

/**
 * The nine platform signal sources. Every source returns [SignalValue.Unknown] below its
 * API floor; exceptions are caught by [SignalHub], not here.
 */
object AndroidSignalSources {
    fun all(context: Context): List<SignalSource> {
        val app = context.applicationContext
        return listOf(
            PendingReasonsSource(app), StandbyBucketSource(app), BgRestrictedSource(app),
            DataSaverSource(app), DozeSource(app), MaintenanceWindowSource(),
            NetworkValidatedSource(app), BattOptExemptSource(app), ProcessDeathSource(app),
        )
    }
}

internal class PendingReasonsSource(private val context: Context) : SignalSource {
    override val kind = SignalKind.PENDING_REASONS
    override fun read(): SignalValue {
        if (Build.VERSION.SDK_INT < 34) return SignalValue.Unknown
        val js = context.getSystemService(JobScheduler::class.java) ?: return SignalValue.Unknown
        val scoped = js.forNamespace("bridge")
        val reasons = HostJobClass.entries.flatMap { host ->
            if (scoped.getPendingJob(host.jobId) == null) emptyList()
            else if (Build.VERSION.SDK_INT >= 36) scoped.getPendingJobReasons(host.jobId).toList()
            else listOf(scoped.getPendingJobReason(host.jobId))
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

internal class ProcessDeathSource(private val context: Context) : SignalSource {
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
