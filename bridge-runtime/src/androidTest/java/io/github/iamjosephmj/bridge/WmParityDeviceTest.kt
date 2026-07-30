package io.github.iamjosephmj.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.BridgeData
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.bridgeDataOf
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.store.RunState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device verification for the WM-parity surface: Data payloads round-trip through real
 * JobScheduler dispatch, tags cancel, Flow observers see real journal writes, and a DAG
 * join waits for both branches then receives their merged outputs. Unique names carry a
 * timestamp so re-runs never collide with a previous run's journal.
 */
@RunWith(AndroidJUnit4::class)
class WmParityDeviceTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpBridge() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Bridge.initialize is idempotent per process, and another test class may have
            // initialized first — so register this class's workers via late registration,
            // which works regardless of who initialized (EndToEndTest documents this trap).
            Bridge.initialize(context) { }
            Bridge.registerWorker("echo") { object : BridgeWorker {
                override suspend fun run(ctx: RunContext): RunResult {
                    ctx.setOutput(bridgeDataOf(
                        "out:${ctx.workId}" to (ctx.input.getString("msg") ?: "none")))
                    return RunResult.Success
                }
            } }
            Bridge.registerWorker("relay") { object : BridgeWorker {
                override suspend fun run(ctx: RunContext): RunResult {
                    ctx.setOutput(BridgeData.of(ctx.input.asMap))
                    return RunResult.Success
                }
            } }
        }
    }

    private fun uniq(prefix: String) = "$prefix-${System.currentTimeMillis()}"

    @Test
    fun dataRoundTripsThroughRealDispatch() = runBlocking {
        val name = uniq("greet")
        Bridge.enqueue(workRequest(name, "echo") { input("msg" to "hello") })
        // The Flow observer is itself under test: it must see the real journal write.
        withTimeout(60_000) {
            Bridge.stateFlow(name).first { it?.runState == RunState.SUCCEEDED }
        }
        assertThat(Bridge.state(name)!!.lastOutput["out:$name"]).isEqualTo("hello")
    }

    @Test
    fun tagsCancelAcrossItems() {
        val a = uniq("tag-a"); val b = uniq("tag-b"); val other = uniq("tag-c")
        val batch = uniq("batch")
        // deviceIdle keeps them pending during an interactive test run.
        Bridge.enqueue(workRequest(a, "echo") { deviceIdle(); tag(batch) })
        Bridge.enqueue(workRequest(b, "echo") { deviceIdle(); tag(batch) })
        Bridge.enqueue(workRequest(other, "echo") { deviceIdle() })
        assertThat(Bridge.namesByTag(batch)).containsExactly(a, b)
        Bridge.cancelAllByTag(batch)
        assertThat(Bridge.state(a)!!.runState).isEqualTo(RunState.CANCELLED)
        assertThat(Bridge.state(b)!!.runState).isEqualTo(RunState.CANCELLED)
        assertThat(Bridge.state(other)!!.runState).isNotEqualTo(RunState.CANCELLED)
        Bridge.cancel(other)
    }

    @Test
    fun dagJoinWaitsForBranchesAndMergesOutputs() = runBlocking {
        val a = uniq("branch-a"); val b = uniq("branch-b"); val join = uniq("join")
        Bridge.enqueue(workRequest(a, "echo") { input("msg" to "A") })
        Bridge.enqueue(workRequest(b, "echo") { input("msg" to "B") })
        Bridge.enqueue(workRequest(join, "relay") { input("own" to "kept"); after(a, b) })
        withTimeout(120_000) {
            Bridge.stateFlow(join).first { it?.runState == RunState.SUCCEEDED }
        }
        val out = Bridge.state(join)!!.lastOutput
        assertThat(out["out:$a"]).isEqualTo("A")
        assertThat(out["out:$b"]).isEqualTo("B")
        assertThat(out["own"]).isEqualTo("kept")
    }
}
