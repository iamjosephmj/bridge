package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.signals.SignalSlice
import io.github.iamjosephmj.bridge.store.WorkEvent

data class CostDelta(val cpuUserMs: Long, val cpuSystemMs: Long, val txBytes: Long, val rxBytes: Long)

sealed interface LedgerOutcome {
    data class Completed(val success: Boolean) : LedgerOutcome
    data class Stopped(val stopReason: Int) : LedgerOutcome
    data class Died(val exitReason: Int) : LedgerOutcome
    object Cancelled : LedgerOutcome { override fun toString() = "Cancelled" }
    object InFlight : LedgerOutcome { override fun toString() = "InFlight" }
}

data class Run(
    val attempt: Int,
    val generation: Int,
    val dispatchedAt: Long?,
    val startedAt: Long?,
    val endedAt: Long?,
    val outcome: LedgerOutcome,
    val chunksExecuted: IntRange?,
    val cost: CostDelta?,
    val deviceContext: SignalSlice?,
)

data class Ledger(val workId: String, val runs: List<Run>)

/**
 * Read-time projection: no persistence of its own. Each closed run carries the signal-log
 * slice overlapping its interval — how "died mid-run" becomes "died mid-run during deep Doze".
 */
object LedgerFold {

    fun fold(
        workId: String,
        events: List<WorkEvent>,
        sliceFor: (fromMs: Long, toMs: Long) -> SignalSlice?,
    ): Ledger {
        val runs = mutableListOf<Run>()
        var dispatchedAt: Long? = null
        var startedAt: Long? = null
        var attempt = 0
        var generation = 0
        var firstChunk: Int? = null
        var lastChunk: Int? = null

        fun close(endedAt: Long?, outcome: LedgerOutcome, cost: CostDelta?) {
            if (dispatchedAt == null && startedAt == null) return
            val from = dispatchedAt ?: startedAt!!
            runs += Run(attempt, generation, dispatchedAt, startedAt, endedAt, outcome,
                if (firstChunk != null) firstChunk!!..lastChunk!! else null,
                cost,
                deviceContext = endedAt?.let { sliceFor(from, it) })
            dispatchedAt = null; startedAt = null; firstChunk = null; lastChunk = null
        }

        for (e in events) {
            when (e) {
                is WorkEvent.Enqueued -> generation = e.generation
                is WorkEvent.Dispatched -> {
                    close(null, LedgerOutcome.InFlight, null)   // dangling dispatch → phantom run
                    dispatchedAt = e.at
                }
                is WorkEvent.Started -> { startedAt = e.at; attempt = e.attempt }
                is WorkEvent.ChunkCompleted -> {
                    if (firstChunk == null) firstChunk = e.chunkIndex
                    lastChunk = e.chunkIndex
                }
                is WorkEvent.Stopped -> close(e.at, LedgerOutcome.Stopped(e.stopReason), null)
                is WorkEvent.Died -> close(e.at, LedgerOutcome.Died(e.exitReason), null)
                is WorkEvent.Finished -> close(e.at, LedgerOutcome.Completed(e.success),
                    if (e.cpuUserMs + e.cpuSystemMs + e.txBytes + e.rxBytes > 0)
                        CostDelta(e.cpuUserMs, e.cpuSystemMs, e.txBytes, e.rxBytes) else null)
                is WorkEvent.Cancelled -> close(e.at, LedgerOutcome.Cancelled, null)
                is WorkEvent.PolicyDecision -> Unit   // judgment records aren't run boundaries
                is WorkEvent.StepCompleted -> Unit    // durable replay records aren't either
            }
        }
        close(null, LedgerOutcome.InFlight, null)
        return Ledger(workId, runs)
    }
}
