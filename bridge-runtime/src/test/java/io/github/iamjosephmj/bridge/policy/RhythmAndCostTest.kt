package io.github.iamjosephmj.bridge.policy

import io.github.iamjosephmj.bridge.diagnostics.CostDelta
import io.github.iamjosephmj.bridge.diagnostics.CostFlags
import io.github.iamjosephmj.bridge.diagnostics.Ledger
import io.github.iamjosephmj.bridge.diagnostics.LedgerOutcome
import io.github.iamjosephmj.bridge.diagnostics.Run
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSlice
import io.github.iamjosephmj.bridge.signals.SignalTransition
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.signals.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmAndCostTest {

    private fun windowOpens(vararg atMs: Long) = SignalSlice(emptyMap(), atMs.map {
        SignalTransition(SignalKind.MAINTENANCE_WINDOW, SignalValue.Flag(false),
            SignalValue.Flag(true), it, Trigger.BROADCAST)
    })

    @Test fun `median gap predicts next window`() {
        // opens at 1h, 4h, 7h → gap 3h → next 10h
        val h = 3_600_000L
        assertEquals(10 * h,
            RhythmModel.predictNextMaintenance(windowOpens(1 * h, 4 * h, 7 * h), now = 8 * h))
    }

    @Test fun `prediction walks forward past now`() {
        val h = 3_600_000L
        assertEquals(13 * h,
            RhythmModel.predictNextMaintenance(windowOpens(1 * h, 4 * h, 7 * h), now = 11 * h))
    }

    @Test fun `fewer than three windows predicts nothing`() {
        assertNull(RhythmModel.predictNextMaintenance(windowOpens(1000, 2000), now = 3000))
    }

    private fun runs(n: Int, cpu: Long) = (1..n).map {
        Run(it, 1, 0L, 1L, 2L, LedgerOutcome.Completed(true), null,
            CostDelta(cpu, 0, 0, 0), null)
    }

    @Test fun `expensive LOW worker flags, expensive HIGH does not, thin history does not`() {
        val flags = CostFlags.compute(mapOf(
            "cheap-a" to (2 to Ledger("cheap-a", runs(3, 100))),
            "cheap-b" to (2 to Ledger("cheap-b", runs(3, 120))),
            "cheap-c" to (2 to Ledger("cheap-c", runs(3, 110))),
            "pig-low" to (1 to Ledger("pig-low", runs(3, 5_000))),
            "pig-high" to (3 to Ledger("pig-high", runs(3, 5_000))),
            "pig-thin" to (1 to Ledger("pig-thin", runs(2, 5_000)))))
        assertEquals(listOf("pig-low"), flags.map { it.workerName })
        assertTrue(flags[0].render().contains("declared unimportant"))
    }

    @Test fun `empty pool flags nothing`() {
        assertTrue(CostFlags.compute(emptyMap()).isEmpty())
    }
}
