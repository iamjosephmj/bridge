package io.github.iamjosephmj.bridge.sim

import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test

class TimingScenarioTest {

    @Test fun `initial delay - work waits out the latency then runs`() = simulate {
        worker("upload") { OkWorker() }
        val work = enqueue(workRequest("later", "upload") { initialDelay(2.h) })
        assertThat(work.completedWithin(90.min)).isFalse()
        assertThat(work.completedWithin(2.h + 5.min)).isTrue()
    }

    @Test fun `periodic - cycles roll generations at the interval`() = simulate {
        var runs = 0
        worker("sync") { object : io.github.iamjosephmj.bridge.api.BridgeWorker {
            override suspend fun run(ctx: io.github.iamjosephmj.bridge.api.RunContext) =
                io.github.iamjosephmj.bridge.api.RunResult.Success.also { runs++ }
        } }
        enqueue(workRequest("heartbeat", "sync") { periodic(30.min) })
        advanceTo(95.min)   // cycle 1 ~immediately, then +30m, +30m, +30m
        assertThat(runs).isEqualTo(4)
        val state = device.journal.state("heartbeat")!!
        assertThat(state.generation).isEqualTo(4)
        assertThat(state.runState).isEqualTo(RunState.SUCCEEDED)
        // Each cycle journaled its own Enqueued + Finished pair.
        assertThat(device.journal.events("heartbeat")
            .count { it is WorkEvent.Finished }).isEqualTo(4)
    }

    @Test fun `periodic - cancel ends the series`() = simulate {
        var runs = 0
        worker("sync") { object : io.github.iamjosephmj.bridge.api.BridgeWorker {
            override suspend fun run(ctx: io.github.iamjosephmj.bridge.api.RunContext) =
                io.github.iamjosephmj.bridge.api.RunResult.Success.also { runs++ }
        } }
        enqueue(workRequest("heartbeat", "sync") { periodic(30.min) })
        advanceTo(40.min)                       // 2 cycles in
        val after = runs
        device.journal.append(WorkEvent.Cancelled("heartbeat", device.clock.now()))
        advanceTo(4.h)
        assertThat(runs).isEqualTo(after)       // no further cycles
        assertThat(device.journal.state("heartbeat")!!.runState).isEqualTo(RunState.CANCELLED)
    }
}
