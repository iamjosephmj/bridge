package io.github.iamjosephmj.bridge.dispatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Final escalation tier: wake near the deadline even in Doze (inexact — see M3 spec §1). */
interface AlarmGateway {
    fun scheduleAt(atMs: Long, workId: String)
}

class SystemAlarmGateway(private val context: Context) : AlarmGateway {
    override fun scheduleAt(atMs: Long, workId: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, BridgeAlarmReceiver::class.java)
            .setAction("io.github.iamjosephmj.bridge.DEADLINE_ALARM")
            .putExtra(EXTRA_WORK_ID, workId)
        val pi = PendingIntent.getBroadcast(context, workId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (_: Exception) { /* alarm quota exhausted: reconcile paths still cover us */ }
    }
}

class BridgeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        io.github.iamjosephmj.bridge.Bridge.reconcileIfInitialized()
    }
}

class FakeAlarmGateway : AlarmGateway {
    val scheduled = mutableListOf<Pair<Long, String>>()
    override fun scheduleAt(atMs: Long, workId: String) { scheduled += atMs to workId }
}
