package io.github.iamjosephmj.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.dispatch.FakeJobGateway
import io.github.iamjosephmj.bridge.store.RunState
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeFacadeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeJobGateway()

    @After fun tearDown() = Bridge.reset()

    private fun init() = Bridge.initialize(context) {
        worker("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        clock = FakeClock(1L)
        this.gateway = this@BridgeFacadeTest.gateway
        ioExecutor = Executor { it.run() }
        dbName = "f-${System.nanoTime()}.db"
    }

    @Test fun `enqueue journals and dispatches immediately`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(Bridge.state("sync")!!.runState).isEqualTo(RunState.DISPATCHED)
        assertThat(gateway.enqueued.single().second.workId).isEqualTo("sync")
    }

    @Test fun `enqueue with same name keeps existing live work`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(gateway.enqueued).hasSize(1)
        assertThat(Bridge.state("sync")!!.generation).isEqualTo(1)
    }

    @Test fun `cancel makes work CANCELLED`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        Bridge.cancel("sync")
        assertThat(Bridge.state("sync")!!.runState).isEqualTo(RunState.CANCELLED)
    }

    @Test fun `initialize is idempotent`() {
        init(); init()
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(gateway.enqueued).hasSize(1)
    }
}
