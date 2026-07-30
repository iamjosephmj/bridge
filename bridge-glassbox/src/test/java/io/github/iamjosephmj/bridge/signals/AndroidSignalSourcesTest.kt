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
        assertEquals(SignalValue.Unknown, ThermalSource(app).read())
        assertEquals(SignalValue.Unknown, ChargeTimeSource(app).read())
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
    fun `all() covers every signal kind`() {
        assertEquals(SignalKind.entries.toSet(),
            AndroidSignalSources.all(app).map { it.kind }.toSet())
    }

    @Test
    @Config(sdk = [31])
    fun `thread pressure counts runnable threads only`() {
        val taskDir = java.nio.file.Files.createTempDirectory("task").toFile()
        fun stat(tid: Int, content: String) {
            java.io.File(taskDir, "$tid").mkdir()
            java.io.File(taskDir, "$tid/stat").writeText(content)
        }
        stat(1, "1 (main) R 0 1 1 0")
        stat(2, "2 (RenderThread) S 0 1 1 0")
        stat(3, "3 (weird (comm) name) R 0 1 1 0")   // parens+spaces in comm
        stat(4, "4 (truncated)")                      // malformed: skipped, not fatal
        assertEquals(SignalValue.Count(2),
            ThreadPressureSource.parseRunnableThreads(taskDir))
    }

    @Test
    @Config(sdk = [31])
    fun `thread pressure is Unknown when proc is unreadable`() {
        assertEquals(SignalValue.Unknown,
            ThreadPressureSource.parseRunnableThreads(java.io.File("/nonexistent-proc")))
        val empty = java.nio.file.Files.createTempDirectory("task").toFile()
        assertEquals(SignalValue.Unknown, ThreadPressureSource.parseRunnableThreads(empty))
    }
}
