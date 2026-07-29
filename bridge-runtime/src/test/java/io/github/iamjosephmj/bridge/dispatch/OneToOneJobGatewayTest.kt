package io.github.iamjosephmj.bridge.dispatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneToOneJobGatewayTest {
    // Note: this deliberately does not inspect JobScheduler.forNamespace(...)'s pending-job
    // list. Robolectric's JobScheduler shadow does not share storage across separate
    // forNamespace() calls returning the same namespace name (each call yields an independent
    // shadow instance), so a test-side forNamespace("bridge-1to1") call never sees what the
    // gateway's own internal instance scheduled. The jobId mapping is verified directly via
    // the exposed lookup instead; enqueue/cancelAll are verified by return value / not
    // throwing.
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = OneToOneJobGateway(context)

    @Test fun `enqueue schedules successfully`() {
        assertThat(gateway.enqueue(HostJobClass.DEFAULT, WorkItemPayload("w1", 3))).isTrue()
    }

    @Test fun `cancelAll does not throw after scheduling`() {
        gateway.enqueue(HostJobClass.DEFAULT, WorkItemPayload("w1", 1))
        gateway.cancelAll()
    }

    @Test fun `oneToOneJobId is stable and outside the multiplexed host-class jobId range`() {
        val id = gateway.oneToOneJobId("w1")
        assertThat(gateway.oneToOneJobId("w1")).isEqualTo(id)
        assertThat(HostJobClass.entries.map { it.jobId }).doesNotContain(id)
        assertThat(id).isAtLeast(720_000)
    }

    @Test fun `distinct workIds always map to distinct jobIds`() {
        val ids = (0 until 1000).map { gateway.oneToOneJobId("work-$it") }
        assertThat(ids.toSet()).hasSize(1000)
    }

    @Test fun `assigned jobIds persist across gateway instances`() {
        val id = gateway.oneToOneJobId("w1")
        assertThat(OneToOneJobGateway(context).oneToOneJobId("w1")).isEqualTo(id)
    }

    @Test fun `cancel of unknown workId does not allocate a mapping`() {
        gateway.cancel("never-seen")
        val first = gateway.oneToOneJobId("w1")
        assertThat(first).isEqualTo(720_000)
    }

    @Test fun `cancel does not throw for a mapped workId`() {
        gateway.enqueue(HostJobClass.DEFAULT, WorkItemPayload("w1", 1))
        gateway.cancel("w1")
    }
}
