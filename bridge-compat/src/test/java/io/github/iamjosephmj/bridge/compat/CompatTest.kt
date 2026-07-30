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
class ProducerWorker : Worker() {
    override fun doWork(): Result =
        Result.success(workDataOf("token" to "T-${inputData.getString("seed")}"))
}
class SecondProducerWorker : Worker() {
    override fun doWork(): Result = Result.success(workDataOf("token2" to "U"))
}
class ConsumerWorker : Worker() {
    override fun doWork(): Result {
        seen = inputData.getString("token")
        seenKeys = inputData.let { d ->
            listOf("token", "token2", "own").filter { d.getString(it) != null } }
        return Result.success(workDataOf("final" to "done"))
    }
    companion object {
        var seen: String? = null
        var seenKeys: List<String> = emptyList()
    }
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

    @Test fun `content uri triggers map through, unioned across a chain`() {
        BridgeWorkManager.beginUniqueWork("watch", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java, Constraints.Builder()
                .addContentUriTrigger("content://media/photos", true)
                .setTriggerContentUpdateDelay(500L).build()))
            .then(request(SecondWorker::class.java, Constraints.Builder()
                .addContentUriTrigger("content://media/videos", false)
                .setTriggerContentMaxDelay(5_000L).build()))
            .enqueue()
        val state = Bridge.state("watch")!!
        assertThat(state.contentUris)
            .containsExactly("content://media/photos", "content://media/videos")
        assertThat(state.contentDescendants).isTrue()
        assertThat(state.contentUpdateDelayMs).isEqualTo(500L)
        assertThat(state.contentMaxDelayMs).isEqualTo(5_000L)
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

    @Test fun `periodic and initial delay map through`() {
        BridgeWorkManager.enqueueUniquePeriodicWork("beat", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(RecordingWorker::class.java, 30 * 60_000L).build())
        assertThat(Bridge.state("beat")!!.periodicMs).isEqualTo(30 * 60_000L)

        BridgeWorkManager.enqueueUniqueWork("later", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(RecordingWorker::class.java)
                .setInitialDelay(60_000L).build())
        assertThat(Bridge.state("later")!!.initialDelayMs).isEqualTo(60_000L)
    }

    @Test fun `cancelUniqueWork maps to CANCELLED`() {
        BridgeWorkManager.enqueueUniqueWork("c", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java))
        BridgeWorkManager.cancelUniqueWork("c")
        assertThat(BridgeWorkManager.getWorkInfoState("c")).isEqualTo(WorkInfoState.CANCELLED)
    }

    @Test fun `data flows input to link, link output to next link, and out as outputData`() {
        ConsumerWorker.seen = null
        BridgeWorkManager.beginUniqueWork("data-chain", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(ProducerWorker::class.java)
                .setInputData(workDataOf("seed" to 42)).build())
            .then(request(ConsumerWorker::class.java))
            .enqueue()
        runParked()
        assertThat(ConsumerWorker.seen).isEqualTo("T-42")
        assertThat(BridgeWorkManager.getOutputData("data-chain").getString("final"))
            .isEqualTo("done")
    }

    @Test fun `tags map through and cancelAllWorkByTag cancels the tagged set`() {
        BridgeWorkManager.enqueueUniqueWork("tag-1", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(RecordingWorker::class.java).addTag("batch").build())
        BridgeWorkManager.enqueueUniqueWork("tag-2", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(SecondWorker::class.java).addTag("batch").build())
        BridgeWorkManager.enqueueUniqueWork("tag-3", ExistingWorkPolicy.KEEP,
            request(RecordingWorker::class.java))
        BridgeWorkManager.cancelAllWorkByTag("batch")
        assertThat(BridgeWorkManager.getWorkInfoState("tag-1")).isEqualTo(WorkInfoState.CANCELLED)
        assertThat(BridgeWorkManager.getWorkInfoState("tag-2")).isEqualTo(WorkInfoState.CANCELLED)
        assertThat(BridgeWorkManager.getWorkInfoState("tag-3")).isEqualTo(WorkInfoState.ENQUEUED)
    }

    @Test fun `combine joins branches and merges their outputs into the joined work`() {
        ConsumerWorker.seen = null; ConsumerWorker.seenKeys = emptyList()
        val a = BridgeWorkManager.beginUniqueWork("br-a", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(ProducerWorker::class.java)
                .setInputData(workDataOf("seed" to 1)).build())
        val b = BridgeWorkManager.beginUniqueWork("br-b", ExistingWorkPolicy.KEEP,
            request(SecondProducerWorker::class.java))
        WorkContinuation.combine(listOf(a, b))
            .then(OneTimeWorkRequest.Builder(ConsumerWorker::class.java)
                .setInputData(workDataOf("own" to "kept")).build())
            .enqueue()
        // The join must not reach the platform while branches are pending.
        assertThat(gateway.enqueued.map { it.second.workId }).doesNotContain("br-a+br-b:join")
        runParked()   // branches complete; the DAG wake dispatches the join
        runParked()   // join runs
        assertThat(BridgeWorkManager.getWorkInfoState("br-a+br-b:join"))
            .isEqualTo(WorkInfoState.SUCCEEDED)
        assertThat(ConsumerWorker.seen).isEqualTo("T-1")
        assertThat(ConsumerWorker.seenKeys).containsExactly("token", "token2", "own")
    }
}
