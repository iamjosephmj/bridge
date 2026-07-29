package io.github.iamjosephmj.bridge.glassbox

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.diagnostics.Basis
import io.github.iamjosephmj.bridge.diagnostics.Diagnosis
import io.github.iamjosephmj.bridge.signals.FakeSignalSource
import io.github.iamjosephmj.bridge.signals.InMemoryTransitionStore
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassBoxTest {

    @After fun tearDown() = GlassBox.reset()

    @Test fun `explains device causes and platform-reported job causes`() {
        val bucket = FakeSignalSource(SignalKind.STANDBY_BUCKET, SignalValue.Bucket(40))
        val reasons = FakeSignalSource(SignalKind.PENDING_REASONS,
            SignalValue.PendingReasons(listOf(12)))   // DEVICE_STATE
        val doze = FakeSignalSource(SignalKind.DOZE,
            SignalValue.Doze(io.github.iamjosephmj.bridge.signals.DozeMode.DEEP))
        GlassBox.install(ApplicationProvider.getApplicationContext(),
            sources = listOf(bucket, reasons, doze), store = InMemoryTransitionStore())

        val e = GlassBox.explain()
        assertThat(e.deviceCauses).containsExactly(
            Diagnosis.DeferredByDoze(true), Diagnosis.DeferredByStandbyBucket(40))
        assertThat(e.jobCauses).containsExactly(Diagnosis.DeferredByDoze(true))
        assertThat(e.basis).isEqualTo(Basis.REPORTED)
        assertThat(e.render()).contains("DeferredByStandbyBucket")
    }

    @Test fun `clear device reads as nothing blocking`() {
        GlassBox.install(ApplicationProvider.getApplicationContext(),
            sources = emptyList(), store = InMemoryTransitionStore())
        val e = GlassBox.explain()
        assertThat(e.deviceCauses).isEmpty()
        assertThat(e.render()).contains("nothing blocking")
    }
}
