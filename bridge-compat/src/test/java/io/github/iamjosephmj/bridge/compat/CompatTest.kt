package io.github.iamjosephmj.bridge.compat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.dispatch.FakeJobGateway
import io.github.iamjosephmj.bridge.store.WorkEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

// Compat workers must be public classes with no-arg constructors (reflection-instantiated).
class RecordingWorker : Worker() {
    override fun doWork(): Result { order += "A"; return Result.success() }
    companion object { val order = mutableListOf<String>() }
}
class SecondWorker : Worker() {
    override fun doWork(): Result { RecordingWorker.order += "B"; return Result.success() }
}
class FlakyWorker : Worker() {
    override fun doWork(): Result {
        RecordingWorker.order += "F"
        return if (RecordingWorker.order.count { it == "F" } < 2) Result.retry()
        else Result.success()
    }
}
class FailingWorker : Worker() {
    override fun doWork(): Result { RecordingWorker.order += "X"; return Result.failure() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompatTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeJobGateway()
    private val clock = FakeClock(1000L)

    @Before fun setUp() {
        RecordingWorker.order.clear()
        Bridge.initialize(context) {
            clock = this@CompatTest.clock
            gateway = this@CompatTest.gateway
            ioExecutor = Executor { it.run() }
            dbName = "compat-${System.nanoTime()}.db"
            signalSources = emptyList()
            transitionStore = io.github.iamjosephmj.bridge.signals.InMemoryTransitionStore()
        }
    }

    @After fun tearDown() = Bridge.reset()

    /** Drives parked payloads through the real WorkRunner, as BridgeJobService would. */
    private fun runParked() = runBlocking {
        for ((_, payload) in gateway.enqueued.toList()) {
            val state = Bridge.state(payload.workId) ?: continue
            val attempts = Bridge.events(payload.workId)
                .count { it is WorkEvent.Started && it.generation == state.generation } + 1
            io.github.iamjosephmj.bridge.dispatch.BridgeServices.runner!!
                .run(payload.workId, payload.generation, attempts) { false }
        }
    }

    private fun request(cls: Class<out Worker>, constraints: Constraints = Constraints.NONE) =
        OneTimeWorkRequest.Builder(cls).setConstraints(constraints).build()

    @Test fun `enqueueUniqueWork runs the worker and maps state`() {
        BridgeWorkManager.getInstance()
            .enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, request(RecordingWorker::class.java))
        assertThat(BridgeWorkManager.getWorkInfoState("sync")).isEqualTo(WorkInfoState.ENQUEUED)
        runParked()
        assertThat(RecordingWorker.order).containsExactly("A")
        assertThat(BridgeWorkManager.getWorkInfoState("sync")).isEqualTo(WorkInfoState.SUCCEEDED)
    }

    @Test fun `constraints map to bridge work flags`() {
        BridgeWorkManager.enqueueUniqueWork("constrained", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java, Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setRequiresDeviceIdle(true).build()))
        val state = Bridge.state("constrained")!!
        assertThat(state.requiresCharging).isTrue()
        assertThat(state.requiresUnmetered).isTrue()
        assertThat(state.requiresBatteryNotLow).isTrue()
        assertThat(state.requiresStorageNotLow).isTrue()
        assertThat(state.requiresDeviceIdle).isTrue()
    }

    @Test fun `CONNECTED network maps, NOT_REQUIRED leaves work offline-runnable`() {
        BridgeWorkManager.enqueueUniqueWork("net", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java, Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()))
        assertThat(Bridge.state("net")!!.requiresNetwork).isTrue()
        BridgeWorkManager.enqueueUniqueWork("offline", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java))
        val offline = Bridge.state("offline")!!
        assertThat(offline.requiresNetwork).isFalse()
        assertThat(offline.requiresUnmetered).isFalse()
    }

    @Test fun `chain runs links in order as chunks of one item`() {
        BridgeWorkManager.beginUniqueWork("chain", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java))
            .then(request(SecondWorker::class.java))
            .enqueue()
        assertThat(Bridge.state("chain")!!.chunkCount).isEqualTo(2)
        runParked()
        assertThat(RecordingWorker.order).containsExactly("A", "B").inOrder()
        assertThat(BridgeWorkManager.getWorkInfoState("chain")).isEqualTo(WorkInfoState.SUCCEEDED)
    }

    @Test fun `link retry resumes at the failed link, not link zero`() {
        BridgeWorkManager.beginUniqueWork("flaky-chain", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java))
            .then(request(FlakyWorker::class.java))
            .enqueue()
        runParked()   // A runs (chunk 0 complete), F retries → whole item Stopped for retry
        runParked()   // resume: chunk ledger says next=1 → F again, succeeds
        assertThat(RecordingWorker.order).containsExactly("A", "F", "F").inOrder()
        assertThat(Bridge.state("flaky-chain")!!.nextChunk).isEqualTo(2)
        assertThat(BridgeWorkManager.getWorkInfoState("flaky-chain"))
            .isEqualTo(WorkInfoState.SUCCEEDED)
    }

    @Test fun `link failure fails the chain, later links never run`() {
        BridgeWorkManager.beginUniqueWork("doomed", ExistingWorkPolicy.KEEP,
            request(FailingWorker::class.java))
            .then(request(SecondWorker::class.java))
            .enqueue()
        runParked()
        assertThat(RecordingWorker.order).containsExactly("X")
        assertThat(BridgeWorkManager.getWorkInfoState("doomed")).isEqualTo(WorkInfoState.FAILED)
    }

    @Test fun `REPLACE cancels and re-enqueues, KEEP keeps`() {
        BridgeWorkManager.enqueueUniqueWork("u", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java))
        val gen1 = Bridge.state("u")!!.generation
        BridgeWorkManager.enqueueUniqueWork("u", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java))
        assertThat(Bridge.state("u")!!.generation).isEqualTo(gen1)
        BridgeWorkManager.enqueueUniqueWork("u", ExistingWorkPolicy.REPLACE,
            request(SecondWorker::class.java))
        assertThat(Bridge.state("u")!!.generation).isEqualTo(gen1 + 1)
    }

    @Test fun `cancelUniqueWork maps to CANCELLED`() {
        BridgeWorkManager.enqueueUniqueWork("c", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java))
        BridgeWorkManager.cancelUniqueWork("c")
        assertThat(BridgeWorkManager.getWorkInfoState("c")).isEqualTo(WorkInfoState.CANCELLED)
    }
}
