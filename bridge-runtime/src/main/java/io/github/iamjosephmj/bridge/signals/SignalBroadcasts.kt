package io.github.iamjosephmj.bridge.signals

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Cheap event-driven pokes for the hub: deep-idle transitions, standby-bucket changes
 * (via the usage-stats broadcast on supporting OEMs), and network callbacks. Each fires
 * `hub.snapshot(Trigger.BROADCAST)`; the hub's diffing decides what gets journaled.
 */
class SignalBroadcasts internal constructor(
    private val hub: SignalHub,
    private val maintenanceSource: MaintenanceWindowSource?,
) {
    constructor(hub: SignalHub, sources: List<SignalSource>) :
        this(hub, sources.filterIsInstance<MaintenanceWindowSource>().firstOrNull())

    private var lastDeepIdle = false

    fun start(context: Context) {
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
            }
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                var burstDrain = false
                if (intent.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                    val pm = c.getSystemService(PowerManager::class.java)
                    val deep = pm?.isDeviceIdleMode ?: false
                    // Deep idle just exited → classic maintenance-window shape for ~30s.
                    if (lastDeepIdle && !deep) { markMaintenanceWindow(); burstDrain = true }
                    lastDeepIdle = deep
                }
                try { hub.snapshot(Trigger.BROADCAST) } catch (_: Exception) { /* never crash host app */ }
                // M3 Doze strategy: drain every eligible item the moment a window opens.
                if (burstDrain) {
                    try { io.github.iamjosephmj.bridge.Bridge.reconcileIfInitialized() }
                    catch (_: Exception) { }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        registerNetworkCallback(app)
    }

    private fun markMaintenanceWindow() {
        val src = maintenanceSource ?: return
        src.inWindow = true
        val openedAt = SystemClock.elapsedRealtime()
        windowOpenedAt = openedAt
        // Closed lazily: next snapshot >30s later reads false.
        Thread {
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { return@Thread }
            if (windowOpenedAt == openedAt) src.inWindow = false
        }.apply { isDaemon = true }.start()
    }
    @Volatile private var windowOpenedAt = 0L

    private fun registerNetworkCallback(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { poke() }
                override fun onLost(network: Network) { poke() }
                override fun onCapabilitiesChanged(
                    network: Network, caps: android.net.NetworkCapabilities) { poke() }
                private fun poke() {
                    try { hub.snapshot(Trigger.BROADCAST) } catch (_: Exception) { }
                }
            })
        } catch (_: Exception) { /* callback limit exceeded on some OEMs — hub stays lazy-poll only */ }
    }
}
