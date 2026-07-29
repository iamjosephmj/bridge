package io.github.iamjosephmj.bridge

interface BridgeClock { fun now(): Long }

class SystemBridgeClock : BridgeClock {
    override fun now(): Long = System.currentTimeMillis()
}

class FakeClock(var nowMs: Long = 0L) : BridgeClock {
    override fun now(): Long = nowMs
    fun advance(ms: Long) { nowMs += ms }
}
