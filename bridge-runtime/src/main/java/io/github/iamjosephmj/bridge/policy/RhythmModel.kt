package io.github.iamjosephmj.bridge.policy

import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSlice
import io.github.iamjosephmj.bridge.signals.SignalValue

/**
 * v1 rhythm model: descriptive statistics over journaled windows, no ML (parent design
 * §4.4.5). Predicts the next maintenance window from the median gap between observed
 * window opens. <3 observed windows → null; callers fall back to fixed rechecks.
 */
object RhythmModel {

    fun predictNextMaintenance(slice: SignalSlice, now: Long): Long? {
        val opens = slice.transitions
            .filter { it.kind == SignalKind.MAINTENANCE_WINDOW &&
                it.to == SignalValue.Flag(true) }
            .map { it.at }
            .sorted()
        if (opens.size < 3) return null
        val gaps = opens.zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (gaps.isEmpty()) return null
        val median = gaps.sorted()[gaps.size / 2]
        var next = opens.last() + median
        while (next <= now) next += median          // never predict into the past
        return next
    }
}
