package io.github.iamjosephmj.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.policy.PolicyEngine
import io.github.iamjosephmj.bridge.policy.PressureLevel
import io.github.iamjosephmj.bridge.signals.AndroidSignalSources
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device verification for THREAD_PRESSURE: /proc/self/task must be readable under this
 * API level's SELinux policy (a JVM test can't prove that), and the count must respond
 * to real load so the policy tiers are reachable on hardware.
 */
@RunWith(AndroidJUnit4::class)
class ThreadPressureDeviceTest {

    private val source = AndroidSignalSources
        .all(InstrumentationRegistry.getInstrumentation().targetContext)
        .first { it.kind == SignalKind.THREAD_PRESSURE }

    @Test
    fun procSelfTaskIsReadableOnThisDevice() {
        val value = source.read()
        assertThat(value).isInstanceOf(SignalValue.Count::class.java)
        // The reading thread itself is runnable while it reads.
        assertThat((value as SignalValue.Count).value).isAtLeast(1)
    }

    @Test
    fun spinLoadDrivesTheLevelToHighAndReleaseRecovers() {
        val cores = Runtime.getRuntime().availableProcessors()
        val spin = java.util.concurrent.atomic.AtomicBoolean(true)
        val spinners = List(cores * 3) {
            Thread {
                @Suppress("ControlFlowWithEmptyBody")
                while (spin.get()) { /* busy */ }
            }.apply { start() }
        }
        try {
            Thread.sleep(200)   // let the scheduler mark them runnable
            val loaded = source.read()
            assertThat(loaded).isInstanceOf(SignalValue.Count::class.java)
            val runnable = (loaded as SignalValue.Count).value
            assertThat(runnable).isGreaterThan(cores * 2)
            assertThat(PolicyEngine.pressureLevel(runnable, cores)).isEqualTo(PressureLevel.HIGH)
        } finally {
            spin.set(false)
            spinners.forEach { it.join(2_000) }
        }
        Thread.sleep(200)
        val settled = (source.read() as SignalValue.Count).value
        assertThat(PolicyEngine.pressureLevel(settled, cores)).isNotEqualTo(PressureLevel.HIGH)
    }
}
