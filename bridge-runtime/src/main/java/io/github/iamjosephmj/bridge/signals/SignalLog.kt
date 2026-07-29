package io.github.iamjosephmj.bridge.signals

interface TransitionStore {
    fun append(at: Long, payload: String)
    /** Ordered by insertion (seq). */
    fun all(): List<Pair<Long, String>>
    fun count(): Int
    fun oldestAt(): Long?
    fun deleteOldest(n: Int)
}

class InMemoryTransitionStore : TransitionStore {
    private val rows = mutableListOf<Pair<Long, String>>()
    @Synchronized override fun append(at: Long, payload: String) { rows += at to payload }
    @Synchronized override fun all(): List<Pair<Long, String>> = rows.toList()
    @Synchronized override fun count(): Int = rows.size
    @Synchronized override fun oldestAt(): Long? = rows.firstOrNull()?.first
    @Synchronized override fun deleteOldest(n: Int) = repeat(minOf(n, rows.size)) { rows.removeAt(0) }
}

/**
 * Process-wide append-only log of signal transitions. Budgeted: on breach the oldest half
 * folds into one BASELINE transition per kind, so old history degrades to "state as of t"
 * instead of disappearing.
 */
class SignalLog(
    private val store: TransitionStore,
    private val maxEntries: Int = 4000,
    private val maxAgeMs: Long = 14L * 24 * 60 * 60 * 1000,
) {
    @Synchronized
    fun append(t: SignalTransition) {
        store.append(t.at, SignalCodec.encode(t))
        val oldest = store.oldestAt()
        if (store.count() > maxEntries) {
            foldEntries(store.all().size / 2)
        } else if (oldest != null && oldest < t.at - maxAgeMs) {
            val cutoff = t.at - maxAgeMs
            foldEntries(store.all().count { (at, _) -> at < cutoff })
        }
    }

    @Synchronized
    fun slice(fromMs: Long, toMs: Long): SignalSlice {
        val decoded = decodeAll()
        val baseline = mutableMapOf<SignalKind, SignalValue>()
        for (t in decoded) if (t.at < fromMs) baseline[t.kind] = t.to
        return SignalSlice(baseline, decoded.filter { it.at in fromMs..toMs })
    }

    /** (entry count, oldest at) for report(). */
    @Synchronized
    fun health(): Pair<Int, Long?> = store.count() to store.oldestAt()

    private fun decodeAll(): List<SignalTransition> =
        store.all().mapNotNull { (_, payload) ->
            try { SignalCodec.decode(payload) } catch (_: Exception) { null }  // skip corrupt rows
        }

    private fun foldEntries(n: Int) {
        val all = store.all()
        if (n <= 0) return
        val folded = all.take(n).mapNotNull { (_, p) ->
            try { SignalCodec.decode(p) } catch (_: Exception) { null }
        }
        val finalValues = mutableMapOf<SignalKind, SignalValue>()
        var lastAt = 0L
        for (t in folded) { finalValues[t.kind] = t.to; lastAt = maxOf(lastAt, t.at) }
        val remainder = all.drop(n)
        store.deleteOldest(all.size)
        for ((kind, v) in finalValues) {
            store.append(lastAt, SignalCodec.encode(
                SignalTransition(kind, SignalValue.Unknown, v, lastAt, Trigger.BASELINE)))
        }
        for ((at, payload) in remainder) store.append(at, payload)
    }
}
