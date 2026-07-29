package io.github.iamjosephmj.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.dispatch.FakeJobGateway
import io.github.iamjosephmj.bridge.store.RunState
import java.util.concurrent.Executor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeInitializeAsyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeJobGateway()

    @After fun tearDown() = Bridge.reset()

    private fun config(): BridgeConfigBuilder.() -> Unit = {
        worker("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        this.gateway = this@BridgeInitializeAsyncTest.gateway
        ioExecutor = Executor { it.run() }
        dbName = "a-${System.nanoTime()}.db"
    }

    @Test fun `initializeAsync then awaitReady then enqueue works`() = runBlocking {
        Bridge.initializeAsync(context, config())
        withTimeout(10_000) { Bridge.awaitReady() }
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(Bridge.state("sync")!!.runState).isEqualTo(RunState.DISPATCHED)
        assertThat(gateway.enqueued.single().second.workId).isEqualTo("sync")
    }

    @Test fun `deferred completes and graceful reads are safe pre-ready`() = runBlocking {
        // Pre-init: the graceful facade must not throw.
        assertThat(Bridge.state("nope")).isNull()
        assertThat(Bridge.report().conformanceMode).isEqualTo("UNKNOWN")
        val deferred = Bridge.initializeAsync(context, config())
        withTimeout(10_000) { deferred.await() }
        assertThat(Bridge.report().conformanceMode).isEqualTo("MULTIPLEXED")
    }

    @Test fun `scope launch before initialize defers until ready`() = runBlocking {
        val handle = Bridge.scope().launch("early") { step("s") { "done" } }
        Bridge.initializeAsync(context, config())
        withTimeout(10_000) { Bridge.awaitReady() }
        // The deferred register+enqueue runs on the scope's dispatcher; poll for arrival.
        withTimeout(10_000) {
            while (Bridge.state("early") == null) delay(10)
        }
        assertThat(handle.state()!!.runState).isEqualTo(RunState.DISPATCHED)
        assertThat(gateway.enqueued.single().second.workId).isEqualTo("early")
    }
}
