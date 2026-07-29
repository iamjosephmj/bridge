package io.github.iamjosephmj.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.diagnostics.Diagnosis
import io.github.iamjosephmj.bridge.dispatch.FakeJobGateway
import io.github.iamjosephmj.bridge.signals.FakeSignalSource
import io.github.iamjosephmj.bridge.signals.InMemoryTransitionStore
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.store.RunState
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeDiagnosticsFacadeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeJobGateway()
    private val bucket = FakeSignalSource(SignalKind.STANDBY_BUCKET, SignalValue.Bucket(10))
    private val doze = FakeSignalSource(SignalKind.DOZE,
        SignalValue.Doze(io.github.iamjosephmj.bridge.signals.DozeMode.NONE))

    @After fun tearDown() = Bridge.reset()

    private fun init() = Bridge.initialize(context) {
        worker("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        clock = FakeClock(1000L)
        this.gateway = this@BridgeDiagnosticsFacadeTest.gateway
        ioExecutor = Executor { it.run() }
        dbName = "d-${System.nanoTime()}.db"
        signalSources = listOf(bucket, doze)
        transitionStore = InMemoryTransitionStore()
    }

    @Test fun `whyPending explains charging constraint`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok") { charging() })
        val v = Bridge.whyPending("sync")
        assertThat(v.diagnosis).isEqualTo(Diagnosis.AwaitingConstraint("charging"))
        assertThat(v.state).isEqualTo(RunState.DISPATCHED)
    }

    @Test fun `whyPending explains demoted bucket`() {
        init()
        bucket.value = SignalValue.Bucket(40)
        Bridge.enqueue(workRequest("sync", "ok"))
        val v = Bridge.whyPending("sync")
        assertThat(v.diagnosis).isEqualTo(Diagnosis.DeferredByStandbyBucket(40))
        assertThat(v.render(now = 61_000L)).contains("DeferredByStandbyBucket(RARE)")
    }

    @Test fun `ledger folds runs, report lists work with diagnoses`() {
        init()
        bucket.value = SignalValue.Bucket(40)
        Bridge.enqueue(workRequest("sync", "ok"))
        val ledger = Bridge.ledger("sync")
        assertThat(ledger.runs).hasSize(1)   // dispatched, in flight

        val report = Bridge.report()
        assertThat(report.lines).hasSize(1)
        assertThat(report.lines[0].diagnosis).isEqualTo(Diagnosis.DeferredByStandbyBucket(40))
        assertThat(report.conformanceMode).isEqualTo("MULTIPLEXED")
        assertThat(report.render(2000L)).contains("sync")
    }

    @Test fun `unknown names are total - UnknownWork verdict, empty ledger`() {
        init()
        val v = Bridge.whyPending("nope")
        assertThat(v.diagnosis).isEqualTo(Diagnosis.UnknownWork)
        assertThat(v.state.name).isEqualTo("UNKNOWN")
        assertThat(Bridge.ledger("nope").runs).isEmpty()
    }
}
