package io.github.iamjosephmj.bridge.dispatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iamjosephmj.bridge.Bridge

class BridgeRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // App process was started for this broadcast; the app's Bridge.initialize()
        // (Application.onCreate) has already reconciled. This nudges dispatch anyway.
        Bridge.reconcileIfInitialized()
    }
}
