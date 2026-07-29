package io.github.iamjosephmj.bridge.signals

import io.github.iamjosephmj.bridge.BridgeClock

/**
 * Process-wide signal owner. Passive: reads sources only when snapshot() is invoked
 * (scheduling decisions, broadcasts, diagnosis) — no timers, no wakeups.
 */
class SignalHub(
    private val sources: List<SignalSource>,
    private val log: SignalLog,
    private val clock: BridgeClock,
) {
    private var last: Map<SignalKind, SignalValue>? = null

    @Synchronized
    fun snapshot(trigger: Trigger): SignalSnapshot {
        val now = clock.now()
        val values = sources.associate { src ->
            src.kind to try { src.read() } catch (_: Exception) { SignalValue.Unknown }
        }
        val prev = last
        for ((kind, value) in values) {
            val before = prev?.get(kind)
            when {
                before == null ->      // first observation this process: baseline
                    log.append(SignalTransition(kind, SignalValue.Unknown, value, now, Trigger.BASELINE))
                before != value ->
                    log.append(SignalTransition(kind, before, value, now, trigger))
            }
        }
        last = values
        return SignalSnapshot(now, values)
    }
}
