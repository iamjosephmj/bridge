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
        assertThat(HostJobClass.forWork(state())).isEqualTo(HostJobClass.DEFAULT)
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
}
