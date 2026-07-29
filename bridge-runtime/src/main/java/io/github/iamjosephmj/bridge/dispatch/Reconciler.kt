package io.github.iamjosephmj.bridge.dispatch

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.exec.DeathAttributor
import io.github.iamjosephmj.bridge.store.EventJournal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.StopReason
import io.github.iamjosephmj.bridge.store.WorkEvent

/** WorkManager's ForceStopRunnable pattern: a sentinel broadcast PendingIntent the OS
 *  wipes on force-stop. Missing sentinel => force-stop (or first run) happened.
 *  Open so tests can subclass and override [wasForceStoppedOrFirstRun] with a fake result. */
open class ForceStopDetector(private val context: Context) {
    private val intent = Intent("io.github.iamjosephmj.bridge.SENTINEL")
        .setPackage(context.packageName)

    open fun wasForceStoppedOrFirstRun(): Boolean {
        val existing = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (existing != null) return false
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return true
    }
}

class Reconciler(
    private val journal: EventJournal,
    private val dispatcher: Dispatcher,
    private val deathAttributor: DeathAttributor,
    private val forceStopDetector: ForceStopDetector,
    private val gateway: JobGateway,
    private val clock: BridgeClock,
) {
    fun reconcile() {
        deathAttributor.attributeDeaths()
        if (forceStopDetector.wasForceStoppedOrFirstRun()) {
            gateway.cancelAll()
            val events = journal.liveWork()
                .filter { it.runState == RunState.DISPATCHED || it.runState == RunState.RUNNING }
                .map { WorkEvent.Stopped(it.workId, clock.now(), StopReason.FORCE_STOP.code) }
            if (events.isNotEmpty()) journal.appendAll(events)
        }
        dispatcher.dispatchAll()
    }
}
