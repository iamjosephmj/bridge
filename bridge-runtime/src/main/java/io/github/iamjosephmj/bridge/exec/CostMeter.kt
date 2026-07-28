package io.github.iamjosephmj.bridge.exec

import android.content.Context
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats

data class CostSnapshot(
    val cpuUserMs: Long, val cpuSystemMs: Long,
    val txBytes: Long, val rxBytes: Long,
) {
    operator fun minus(earlier: CostSnapshot) = CostSnapshot(
        (cpuUserMs - earlier.cpuUserMs).coerceAtLeast(0),
        (cpuSystemMs - earlier.cpuSystemMs).coerceAtLeast(0),
        (txBytes - earlier.txBytes).coerceAtLeast(0),
        (rxBytes - earlier.rxBytes).coerceAtLeast(0),
    )
    companion object { val ZERO = CostSnapshot(0, 0, 0, 0) }
}

interface CostMeter { fun snapshot(): CostSnapshot }

class HealthStatsCostMeter(context: Context) : CostMeter {
    private val shm = context.getSystemService(SystemHealthManager::class.java)
    override fun snapshot(): CostSnapshot = try {
        val hs = shm.takeMyUidSnapshot()
        fun m(key: Int) = if (hs.hasMeasurement(key)) hs.getMeasurement(key) else 0L
        CostSnapshot(
            cpuUserMs = m(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS),
            cpuSystemMs = m(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS),
            txBytes = m(UidHealthStats.MEASUREMENT_MOBILE_TX_BYTES) +
                m(UidHealthStats.MEASUREMENT_WIFI_TX_BYTES),
            rxBytes = m(UidHealthStats.MEASUREMENT_MOBILE_RX_BYTES) +
                m(UidHealthStats.MEASUREMENT_WIFI_RX_BYTES),
        )
    } catch (e: Exception) { CostSnapshot.ZERO }   // some OEMs throw from batterystats parceling
}

class FakeCostMeter(private vararg val snapshots: CostSnapshot) : CostMeter {
    private var i = 0
    override fun snapshot(): CostSnapshot =
        snapshots[minOf(i++, snapshots.size - 1)]
}
