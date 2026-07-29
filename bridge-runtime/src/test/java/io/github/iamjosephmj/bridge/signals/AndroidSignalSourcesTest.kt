package io.github.iamjosephmj.bridge.signals

import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AndroidSignalSourcesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [26])
    fun `28-plus and 30-plus sources degrade to Unknown on 26`() {
        assertEquals(SignalValue.Unknown, StandbyBucketSource(app).read())
        assertEquals(SignalValue.Unknown, BgRestrictedSource(app).read())
        assertEquals(SignalValue.Unknown, ExitInfoSignalSource(app).read())
        assertEquals(SignalValue.Unknown, PendingReasonsSource(app).read())
    }

    @Test
    @Config(sdk = [31])
    fun `doze source maps idle state`() {
        val src = DozeSource(app)
        assertEquals(SignalValue.Doze(DozeMode.NONE), src.read())
        val pm = app.getSystemService(PowerManager::class.java)
        shadowOf(pm).setIsDeviceIdleMode(true)
        assertEquals(SignalValue.Doze(DozeMode.DEEP), src.read())
    }

    @Test
    @Config(sdk = [31])
    fun `battery optimization exemption maps to flag`() {
        val pm = app.getSystemService(PowerManager::class.java)
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, true)
        assertEquals(SignalValue.Flag(true), BattOptExemptSource(app).read())
    }

    @Test
    @Config(sdk = [28])
    fun `bucket source reads standby bucket`() {
        // Robolectric default bucket is ACTIVE (10)
        assertEquals(SignalValue.Bucket(10), StandbyBucketSource(app).read())
    }

    @Test
    @Config(sdk = [31])
    fun `maintenance window source defaults false and flips`() {
        val src = MaintenanceWindowSource()
        assertEquals(SignalValue.Flag(false), src.read())
        src.inWindow = true
        assertEquals(SignalValue.Flag(true), src.read())
    }

    @Test
    @Config(sdk = [31])
    fun `all() returns the nine sources`() {
        assertEquals(SignalKind.entries.toSet(),
            AndroidSignalSources.all(app).map { it.kind }.toSet())
    }
}
