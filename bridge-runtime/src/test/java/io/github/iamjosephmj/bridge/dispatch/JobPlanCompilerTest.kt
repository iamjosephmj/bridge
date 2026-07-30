package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JobPlanCompilerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val component = ComponentName(context, "io.github.iamjosephmj.bridge.dispatch.BridgeJobService")

    private fun state(importance: Int = 2, charging: Boolean = false, unmetered: Boolean = false) =
        WorkState("w1", "w", 1, RunState.ENQUEUED, 0, 0, 0, 3,
            importance, charging, unmetered, 0L, null, null)

    @Test fun `unmetered plus charging work maps to UNMETERED_CHARGING host`() {
        assertThat(HostJobClass.forWork(state(charging = true, unmetered = true)))
            .isEqualTo(HostJobClass.UNMETERED_CHARGING)
    }

    @Test fun `low importance maps to DEFERRABLE`() {
        assertThat(HostJobClass.forWork(state(importance = 1)))
            .isEqualTo(HostJobClass.DEFERRABLE)
    }

    @Test fun `default work maps to DEFAULT`() {
        // Constraint-free work no longer waits for network (constraints-parity fix).
        assertThat(HostJobClass.forWork(state())).isEqualTo(HostJobClass.NO_NETWORK)
    }

    @Test fun `UNMETERED_CHARGING JobInfo requires unmetered network and charging`() {
        val info = JobPlanCompiler.jobInfo(context, HostJobClass.UNMETERED_CHARGING, component)
        assertThat(info.networkType).isEqualTo(JobInfo.NETWORK_TYPE_UNMETERED)
        assertThat(info.isRequireCharging).isTrue()
        assertThat(info.id).isEqualTo(HostJobClass.UNMETERED_CHARGING.jobId)
    }

    @Test fun `DEFERRABLE JobInfo carries low priority on API 34`() {
        val info = JobPlanCompiler.jobInfo(context, HostJobClass.DEFERRABLE, component)
        assertThat(info.priority).isEqualTo(JobInfo.PRIORITY_LOW)
    }

    @Test fun `all host JobInfos are not persisted`() {
        for (hc in HostJobClass.entries) {
            assertThat(JobPlanCompiler.jobInfo(context, hc, component).isPersisted).isFalse()
        }
    }

    @Test fun `host JobInfos default to 30s exponential backoff`() {
        val info = JobPlanCompiler.jobInfo(context, HostJobClass.DEFAULT, component)
        assertThat(info.initialBackoffMillis).isEqualTo(30_000L)
        assertThat(info.backoffPolicy).isEqualTo(JobInfo.BACKOFF_POLICY_EXPONENTIAL)
    }

    @Test fun `custom backoff criteria reach the exact JobInfo`() {
        val info = JobPlanCompiler.jobInfo(context, HostJobClass.DEFAULT, component, jobId = 1,
            itemConstraints = ItemConstraints(NetworkNeed.ANY, charging = false,
                batteryNotLow = false, storageNotLow = false, deviceIdle = false,
                backoffMs = 120_000L, backoffLinear = true))
        assertThat(info.initialBackoffMillis).isEqualTo(120_000L)
        assertThat(info.backoffPolicy).isEqualTo(JobInfo.BACKOFF_POLICY_LINEAR)
    }

    @Test fun `custom backoff routes to the exact path`() {
        assertThat(state().copy(backoffMs = 60_000L).needsExactConstraints).isTrue()
        assertThat(state().copy(requiresNetwork = true).needsExactConstraints).isFalse()
    }
}
