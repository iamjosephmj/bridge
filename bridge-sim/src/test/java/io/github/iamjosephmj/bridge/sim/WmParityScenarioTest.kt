package io.github.iamjosephmj.bridge.sim

import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.bridgeDataOf
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.diagnostics.Diagnosis
import org.junit.Test

/** Echoes its input into a workId-keyed output, proving the payload made the round trip. */
internal class EchoWorker : BridgeWorker {
    override suspend fun run(ctx: RunContext): RunResult {
        ctx.setOutput(bridgeDataOf(
            "out:${ctx.workId}" to (ctx.input.getString("msg") ?: "none")))
        return RunResult.Success
    }
}

/** Copies its entire (merged) input into its output — the join-node probe. */
internal class RelayWorker : BridgeWorker {
    override suspend fun run(ctx: RunContext): RunResult {
        ctx.setOutput(io.github.iamjosephmj.bridge.api.BridgeData.of(ctx.input.asMap))
        return RunResult.Success
    }
}

internal class AlwaysFailWorker : BridgeWorker {
    override suspend fun run(ctx: RunContext): RunResult = RunResult.Failure
}

class WmParityScenarioTest {

    @Test fun `(h) input reaches the worker and output lands in the journal`() = simulate {
        worker("echo") { EchoWorker() }
        val work = enqueue(workRequest("greet", "echo") { input("msg" to "hello") })
        assertThat(work.completedWithin(1.h)).isTrue()
        assertThat(device.journal.state("greet")!!.lastOutput["out:greet"]).isEqualTo("hello")
    }

    @Test fun `(i) DAG join waits for both branches and receives their merged outputs`() = simulate {
        worker("echo") { EchoWorker() }
        worker("relay") { RelayWorker() }
        enqueue(workRequest("branch-a", "echo") { input("msg" to "A") })
        enqueue(workRequest("branch-b", "echo") { input("msg" to "B") })
        val join = enqueue(workRequest("join", "relay") {
            input("own" to "kept")
            after("branch-a", "branch-b")
        })
        assertThat(join.completedWithin(2.h)).isTrue()
        val out = device.journal.state("join")!!.lastOutput
        assertThat(out["out:branch-a"]).isEqualTo("A")
        assertThat(out["out:branch-b"]).isEqualTo("B")
        assertThat(out["own"]).isEqualTo("kept")
    }

    @Test fun `(j) a pending prerequisite is the diagnosis, by name`() = simulate {
        worker("echo") { EchoWorker() }
        val join = enqueue(workRequest("join", "echo") { after("never-enqueued") })
        val verdict = join.verdictAt(1.h)
        assertThat(verdict.diagnosis).isInstanceOf(Diagnosis.WaitingForPrerequisites::class.java)
        assertThat((verdict.diagnosis as Diagnosis.WaitingForPrerequisites).pending)
            .containsExactly("never-enqueued")
    }

    @Test fun `(k) a failed prerequisite fails the dependent with a journaled reason`() = simulate {
        worker("fail") { AlwaysFailWorker() }
        worker("echo") { EchoWorker() }
        enqueue(workRequest("bad", "fail"))
        val dep = enqueue(workRequest("dep", "echo") { after("bad") })
        advanceTo(1.h)
        assertThat(device.journal.state("dep")!!.runState.name).isEqualTo("FAILED")
        assertThat(device.journal.events("dep")
            .filterIsInstance<io.github.iamjosephmj.bridge.store.WorkEvent.Finished>()
            .single().failureMessage).contains("prerequisite 'bad'")
        // dep's failure must not have burned a run: it never Started.
        assertThat(device.journal.events("dep")
            .filterIsInstance<io.github.iamjosephmj.bridge.store.WorkEvent.Started>()).isEmpty()
    }
}
