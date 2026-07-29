package io.github.iamjosephmj.bridge.api

import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.dispatch.FakeAlarmGateway
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.InMemoryJournal
import io.github.iamjosephmj.bridge.store.WorkEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTest {

    private val journal = InMemoryJournal()
    private val clock = FakeClock(1000L)
    private val alarms = FakeAlarmGateway()
    private var snapshot = SignalSnapshot(1000L, emptyMap())
    private val deps = DurableDeps(journal, clock, { snapshot }, alarms)

    private fun enqueue(id: String = "w") =
        journal.append(WorkEvent.Enqueued(id, clock.now(), id, 1, importance = 2))

    private fun runCtx(id: String = "w") = RunContext(id, 1) { false }

    @Test fun `steps execute once and replay from journal`() = runTest {
        enqueue()
        var executions = 0
        val block: DurableBlock = {
            val v = step("compute") { executions++; 41 + 1 }
            assertEquals(42, v)
        }
        assertEquals(RunResult.Success, DurableWorker(block, deps).run(runCtx()))
        // "Process death": a fresh worker + context replays from the journal.
        assertEquals(RunResult.Success, DurableWorker(block, deps).run(runCtx()))
        assertEquals(1, executions)
    }

    @Test fun `positional structure mismatch fails explicitly`() = runTest {
        enqueue()
        DurableWorker({ step("a") { 1 } }, deps).run(runCtx())
        val renamed: DurableBlock = { step("b") { 2 } }
        assertEquals(RunResult.Failure, DurableWorker(renamed, deps).run(runCtx()))
        val pd = journal.events("w").filterIsInstance<WorkEvent.PolicyDecision>().last()
        assertEquals("structure-mismatch", pd.decision)
        assertTrue(pd.why.contains("'a'") && pd.why.contains("'b'"))
    }

    @Test fun `delay parks with alarm, elapsed delay replays through`() = runTest {
        enqueue()
        var afterDelay = false
        val block: DurableBlock = {
            step("before") { "x" }
            delay(2 * 60 * 60 * 1000L)     // 2h
            afterDelay = true
        }
        val parked = DurableWorker(block, deps).run(runCtx())
        assertTrue(parked is RunResult.Parked)
        assertEquals(1000L + 2 * 60 * 60 * 1000L, (parked as RunResult.Parked).wakeAtMs)
        assertEquals(parked.wakeAtMs, alarms.scheduled.single().first)
        assertTrue(!afterDelay)
        // Re-run before the timer: parks again, step "before" not re-executed.
        clock.advance(60 * 60 * 1000L)
        assertTrue(DurableWorker(block, deps).run(runCtx()) is RunResult.Parked)
        // After the timer (possibly post-death): replays straight past the delay.
        clock.advance(61 * 60 * 1000L)
        assertEquals(RunResult.Success, DurableWorker(block, deps).run(runCtx()))
        assertTrue(afterDelay)
        assertEquals(1, journal.events("w").count {
            it is WorkEvent.StepCompleted && it.name == "before" })
    }

    @Test fun `await parks then passes when the snapshot satisfies`() = runTest {
        enqueue()
        val block: DurableBlock = {
            await("validated-net") {
                it.values[SignalKind.NETWORK_VALIDATED] == SignalValue.Flag(true)
            }
            step("send") { "ok" }
        }
        assertTrue(DurableWorker(block, deps).run(runCtx()) is RunResult.Parked)
        snapshot = SignalSnapshot(2000L,
            mapOf(SignalKind.NETWORK_VALIDATED to SignalValue.Flag(true)))
        assertEquals(RunResult.Success, DurableWorker(block, deps).run(runCtx()))
        // Satisfaction was journaled: replay completes even if the signal flips back.
        snapshot = SignalSnapshot(3000L, emptyMap())
        assertEquals(RunResult.Success, DurableWorker(block, deps).run(runCtx()))
    }

    @Test fun `now and random are stable across replay`() = runTest {
        enqueue()
        val seen = mutableListOf<Pair<Long, Long>>()
        val block: DurableBlock = { seen += now() to random() }
        DurableWorker(block, deps).run(runCtx())
        clock.advance(999_999L)
        DurableWorker(block, deps).run(runCtx())
        assertEquals(seen[0], seen[1])
    }

    @Test fun `parks never burn attempts - maxAttempts 1 work parks repeatedly`() = runTest {
        // Through the real WorkRunner: a Parked result must not consume the attempt budget.
        journal.append(WorkEvent.Enqueued("p", clock.now(), "p", 1,
            importance = 2, maxAttempts = 1))
        val registry = WorkerRegistry()
        registry.register("p") {
            DurableWorker({ delay(10_000L) }, deps)
        }
        val runner = io.github.iamjosephmj.bridge.exec.WorkRunner(
            journal, registry,
            object : io.github.iamjosephmj.bridge.exec.BlackBox {
                override fun stamp(workId: String, step: String, attempt: Int) = Unit
                override fun clear() = Unit
            },
            object : io.github.iamjosephmj.bridge.exec.CostMeter {
                override fun snapshot() = io.github.iamjosephmj.bridge.exec.CostSnapshot(0, 0, 0, 0)
            }, clock)
        repeat(3) { attempt ->
            val outcome = runner.run("p", 1, attempt + 1) { false }
            assertEquals(io.github.iamjosephmj.bridge.exec.RunOutcome.RETRY, outcome)
        }
        // Still ENQUEUED (parked), never FAILED; park stop reason journaled.
        assertEquals("ENQUEUED", journal.state("p")!!.runState.name)
        assertTrue(journal.events("p").filterIsInstance<WorkEvent.Stopped>()
            .all { it.stopReason == io.github.iamjosephmj.bridge.exec.STOP_REASON_PARKED })
    }
}
