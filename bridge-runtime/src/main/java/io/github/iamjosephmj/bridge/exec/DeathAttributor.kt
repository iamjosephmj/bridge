package io.github.iamjosephmj.bridge.exec

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.store.EventJournal
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
    private val journal: EventJournal,
    private val source: ProcessDeathSource,
    private val clock: BridgeClock,
) {
    private fun parseBlackBoxSummary(summary: String): Triple<String, String, Int>? {
        val parts = summary.split("|")
        if (parts.size < 3) return null
        val attempt = parts.last().toIntOrNull() ?: return null
        val step = parts[parts.size - 2]
        val workId = parts.dropLast(2).joinToString("|")
        return Triple(workId, step, attempt)
    }

    /** Call on init, before any new dispatch: settles work left RUNNING by a dead process. */
    fun attributeDeaths() {
        val running = journal.runningWork()
        if (running.isEmpty()) return
        val deaths = source.recentDeaths()
        for (work in running) {
            val match = deaths.firstOrNull { death ->
                death.summary?.let { summary ->
                    val parsed = parseBlackBoxSummary(summary)
                    parsed?.first == work.workId
                } ?: false
            }
            if (match != null) {
                val parsed = parseBlackBoxSummary(match.summary!!)!!
                journal.append(WorkEvent.Died(
                    work.workId, clock.now(), exitReason = match.reason, rssKb = match.rssKb,
                    step = parsed.second,
                    attempt = parsed.third))
            } else {
                journal.append(WorkEvent.Stopped(work.workId, clock.now(), STOP_REASON_UNKNOWN))
            }
        }
    }
}
