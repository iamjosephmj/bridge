package io.github.iamjosephmj.bridge.dispatch

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.store.InMemoryJournal
import io.github.iamjosephmj.bridge.store.WorkEvent
import io.github.iamjosephmj.bridge.store.foldWorkState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConstraintsParityTest {

    private fun enqueued(
        charging: Boolean = false, unmetered: Boolean = false, network: Boolean = false,
        batteryNotLow: Boolean = false, storageNotLow: Boolean = false,
        deviceIdle: Boolean = false, importance: Int = 2,
    ) = foldWorkState(listOf(WorkEvent.Enqueued("w", 1L, "worker", 1,
        importance = importance, requiresCharging = charging, requiresUnmetered = unmetered,
        requiresNetwork = network, requiresBatteryNotLow = batteryNotLow,
        requiresStorageNotLow = storageNotLow, requiresDeviceIdle = deviceIdle)))!!

    @Test fun `routing - common shapes multiplex, exact tail goes 1-to-1`() {
        assertThat(HostJobClass.forWork(enqueued())).isEqualTo(HostJobClass.NO_NETWORK)
        assertThat(HostJobClass.forWork(enqueued(network = true))).isEqualTo(HostJobClass.DEFAULT)
        assertThat(HostJobClass.forWork(enqueued(unmetered = true, charging = true)))
            .isEqualTo(HostJobClass.UNMETERED_CHARGING)
        assertThat(enqueued().needsExactConstraints).isTrue()   // enqueue() can't host it
        assertThat(enqueued(network = true).needsExactConstraints).isFalse()
        // The M1 accidents, now caught: single-sided constraints need exact JobInfos.
        assertThat(enqueued(charging = true).needsExactConstraints).isTrue()
        assertThat(enqueued(unmetered = true).needsExactConstraints).isTrue()
        assertThat(enqueued(batteryNotLow = true).needsExactConstraints).isTrue()
        assertThat(enqueued(storageNotLow = true).needsExactConstraints).isTrue()
        assertThat(enqueued(deviceIdle = true).needsExactConstraints).isTrue()
    }

    @Test fun `exact constraints compile to an exact JobInfo`() {
        val info = JobPlanCompiler.jobInfo(
            ApplicationProvider.getApplicationContext(), HostJobClass.DEFAULT,
            ComponentName("p", "c"), jobId = 720_001,
            itemConstraints = ItemConstraints(network = 2, charging = true,
                batteryNotLow = true, storageNotLow = true, deviceIdle = true))
        assertThat(info.isRequireCharging).isTrue()
        assertThat(info.isRequireBatteryNotLow).isTrue()
        assertThat(info.isRequireStorageNotLow).isTrue()
        assertThat(info.isRequireDeviceIdle).isTrue()
        assertThat(info.requiredNetwork).isNotNull()
    }

    @Test fun `NO_NETWORK host compiles constraint-free`() {
        val info = JobPlanCompiler.jobInfo(
            ApplicationProvider.getApplicationContext(), HostJobClass.NO_NETWORK,
            ComponentName("p", "c"))
        assertThat(info.requiredNetwork).isNull()
        assertThat(info.isRequireCharging).isFalse()
    }

    @Test fun `periodic and latency compile to platform JobInfo shapes`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val periodic = JobPlanCompiler.jobInfo(ctx, HostJobClass.DEFAULT,
            ComponentName("p", "c"), jobId = 720_002,
            itemConstraints = ItemConstraints(0, false, false, false, false,
                periodicMs = 30 * 60_000L))
        assertThat(periodic.isPeriodic).isTrue()
        assertThat(periodic.intervalMillis).isEqualTo(30 * 60_000L)
        val delayed = JobPlanCompiler.jobInfo(ctx, HostJobClass.DEFAULT,
            ComponentName("p", "c"), jobId = 720_003,
            itemConstraints = ItemConstraints(0, false, false, false, false,
                minLatencyMs = 5_000L))
        assertThat(delayed.minLatencyMillis).isEqualTo(5_000L)
    }

    @Test fun `dispatcher attaches exact constraints and selecting gateway honors them`() {
        val journal = InMemoryJournal()
        journal.append(WorkEvent.Enqueued("w", 1L, "worker", 1, importance = 2,
            requiresCharging = true))   // charging-only: the classic lost constraint
        val fake = FakeJobGateway()
        Dispatcher(journal, fake, FakeClock(1L)).dispatch("w")
        val payload = fake.enqueued.single().second
        assertThat(payload.constraints).isEqualTo(ItemConstraints(
            network = 0, charging = true, batteryNotLow = false,
            storageNotLow = false, deviceIdle = false))

        // SelectingJobGateway must route exact-constraint payloads 1:1 even in MULTIPLEXED.
        val mux = FakeJobGateway(); val oneToOne = FakeJobGateway()
        val selecting = SelectingJobGateway(mux, oneToOne,
            Conformance(ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSharedPreferences("t-${System.nanoTime()}", 0)))
        selecting.enqueue(HostJobClass.DEFAULT, payload)
        assertThat(mux.enqueued).isEmpty()
        assertThat(oneToOne.enqueued).hasSize(1)
    }
}
