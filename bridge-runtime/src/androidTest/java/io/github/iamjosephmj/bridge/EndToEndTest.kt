package io.github.iamjosephmj.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.store.RunState
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end smoke test through the REAL JobScheduler on a device.
 *
 * Bridge.initialize is idempotent per-process, and both test methods below run in the
 * same instrumentation process. If each test method called Bridge.initialize with only
 * its own worker registered, whichever test ran second would find the registry already
 * initialized (a no-op) and its worker would never be registered, causing its job to
 * silently never dispatch to a real worker. To avoid that, both workers ("smoke" and
 * "chunky") are registered together in a single initialize call that runs once for the
 * whole class (via a companion/@BeforeClass), and the test methods only enqueue + await.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpBridge() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            Bridge.initialize(context) {
                worker("smoke") { object : BridgeWorker {
                    override suspend fun run(ctx: RunContext): RunResult {
                        smokeLatch.countDown(); return RunResult.Success
                    } } }
                worker("chunky") { object : ChunkedWorker {
                    override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
                        chunkyLatch.countDown(); return RunResult.Success
                    } } }
                durable("durable-e2e") { ctx ->
                    ctx.step("first") { stepExecutions.incrementAndGet() }
                    ctx.delay(3_000L)   // parks; JobScheduler backoff re-delivers, replay resumes
                    ctx.step("second") { stepExecutions.incrementAndGet() }
                    durableLatch.countDown()
                }
            }
        }

        // Latches are recreated per test via reset(); declared here so the workers
        // (registered once) can always reach the "current" latch.
        @JvmStatic var smokeLatch = CountDownLatch(1)
        @JvmStatic var chunkyLatch = CountDownLatch(5)
        @JvmStatic var durableLatch = CountDownLatch(1)
        @JvmStatic val stepExecutions = java.util.concurrent.atomic.AtomicInteger(0)
    }

    @Test fun unconstrained_work_executes_via_real_jobscheduler() {
        smokeLatch = CountDownLatch(1)
        Bridge.enqueue(workRequest("smoke-${System.currentTimeMillis()}", "smoke"))
        // Unconstrained DEFAULT host job should run promptly on an unthrottled test device.
        assertThat(smokeLatch.await(60, TimeUnit.SECONDS)).isTrue()
    }

    @Test fun durable_block_parks_on_delay_and_resumes_via_real_jobscheduler() {
        durableLatch = CountDownLatch(1)
        stepExecutions.set(0)
        val name = "durable-e2e"   // durable instances are named by their block
        Bridge.durable(name)
        // Park + JobScheduler backoff re-delivery + replay: allow a generous window.
        assertThat(durableLatch.await(120, TimeUnit.SECONDS)).isTrue()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
               Bridge.state(name)?.runState != RunState.SUCCEEDED) Thread.sleep(200)
        assertThat(Bridge.state(name)!!.runState).isEqualTo(RunState.SUCCEEDED)
        // Replay contract on real hardware: each step executed exactly once even though
        // the run was delivered at least twice (pre-park and post-park).
        assertThat(stepExecutions.get()).isEqualTo(2)
        assertThat(Bridge.events(name).count {
            it is io.github.iamjosephmj.bridge.store.WorkEvent.Stopped &&
                it.stopReason == io.github.iamjosephmj.bridge.exec.STOP_REASON_PARKED
        }).isAtLeast(1)
    }

    @Test fun chunked_work_records_progress() {
        chunkyLatch = CountDownLatch(5)
        val name = "chunky-${System.currentTimeMillis()}"
        Bridge.enqueue(workRequest(name, "chunky") { chunks(count = 5) })
        assertThat(chunkyLatch.await(60, TimeUnit.SECONDS)).isTrue()
        // Poll briefly for the terminal state (Finished lands just after the last chunk).
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
               Bridge.state(name)?.runState != RunState.SUCCEEDED) Thread.sleep(200)
        assertThat(Bridge.state(name)!!.runState).isEqualTo(RunState.SUCCEEDED)
        assertThat(Bridge.state(name)!!.nextChunk).isEqualTo(5)
    }
}
