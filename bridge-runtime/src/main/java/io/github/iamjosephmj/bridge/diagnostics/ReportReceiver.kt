package io.github.iamjosephmj.bridge.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.iamjosephmj.bridge.Bridge

/**
 * adb entry point: `adb shell am broadcast -a io.github.iamjosephmj.bridge.REPORT -n <pkg>/io.github.iamjosephmj.bridge.diagnostics.ReportReceiver`
 * Dumps the app-wide report to logcat tag `BridgeReport`. Exported so the adb shell uid can
 * trigger it; it only writes to the app's own logcat, so triggering is harmless.
 */
class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.i("BridgeReport", "\n" + Bridge.report().render(System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.w("BridgeReport", "report failed", e)
        }
    }
}
