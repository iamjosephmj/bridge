package io.github.iamjosephmj.bridge.exec

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.WorkEvent

const val STOP_REASON_UNKNOWN = -1

data class ProcessDeath(
    val timestampMs: Long, val reason: Int, val rssKb: Long, val summary: String?)

interface ProcessDeathSource { fun recentDeaths(): List<ProcessDeath> }

class SystemProcessDeathSource(private val context: Context) : ProcessDeathSource {
    override fun recentDeaths(): List<ProcessDeath> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java)
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 16).map {
            ProcessDeath(
                timestampMs = it.timestamp,
                reason = it.reason,
                rssKb = it.rss,
                summary = it.processStateSummary?.toString(Charsets.UTF_8))
        }
    }
}

class DeathAttributor(
    private val journal: Journal,
    private val source: ProcessDeathSource,
    private val clock: BridgeClock,
) {
    /** Call on init, before any new dispatch: settles work left RUNNING by a dead process. */
    fun attributeDeaths() {
        val running = journal.runningWork()
        if (running.isEmpty()) return
        val deaths = source.recentDeaths()
        for (work in running) {
            val match = deaths.firstOrNull { it.summary?.startsWith("${work.workId}|") == true }
            if (match != null) {
                val parts = match.summary!!.split("|")
                journal.append(WorkEvent.Died(
                    work.workId, clock.now(), exitReason = match.reason, rssKb = match.rssKb,
                    step = parts.getOrElse(1) { "?" },
                    attempt = parts.getOrNull(2)?.toIntOrNull() ?: work.attempt))
            } else {
                journal.append(WorkEvent.Stopped(work.workId, clock.now(), STOP_REASON_UNKNOWN))
            }
        }
    }
}
