package io.github.iamjosephmj.bridge.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CostMeterTest {
    @Test fun `snapshot delta is field-wise and clamped at zero`() {
        val before = CostSnapshot(cpuUserMs = 100, cpuSystemMs = 50, txBytes = 1000, rxBytes = 500)
        val after = CostSnapshot(cpuUserMs = 300, cpuSystemMs = 40, txBytes = 6000, rxBytes = 500)
        val delta = after - before
        assertThat(delta).isEqualTo(
            CostSnapshot(cpuUserMs = 200, cpuSystemMs = 0, txBytes = 5000, rxBytes = 0))
    }

    @Test fun `fake meter returns snapshots in order then repeats the last`() {
        val m = FakeCostMeter(
            CostSnapshot(1, 1, 1, 1), CostSnapshot(2, 2, 2, 2))
        assertThat(m.snapshot()).isEqualTo(CostSnapshot(1, 1, 1, 1))
        assertThat(m.snapshot()).isEqualTo(CostSnapshot(2, 2, 2, 2))
        assertThat(m.snapshot()).isEqualTo(CostSnapshot(2, 2, 2, 2))
    }
}
