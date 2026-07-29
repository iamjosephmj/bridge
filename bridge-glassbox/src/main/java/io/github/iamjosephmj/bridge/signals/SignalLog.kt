package io.github.iamjosephmj.bridge.signals

interface TransitionStore {
    fun append(at: Long, payload: String)
    /** Ordered by insertion (seq). */
    fun all(): List<Pair<Long, String>>
    fun count(): Int
    fun oldestAt(): Long?
    fun deleteOldest(n: Int)
    /** Atomically replace the whole log with [rows] — all-or-nothing on process death. */
    fun replaceAll(rows: List<Pair<Long, String>>)
}

class InMemoryTransitionStore : TransitionStore {
    private val rows = mutableListOf<Pair<Long, String>>()
    @Synchronized override fun append(at: Long, payload: String) { rows += at to payload }
    @Synchronized override fun all(): List<Pair<Long, String>> = rows.toList()
    @Synchronized override fun count(): Int = rows.size
    @Synchronized override fun oldestAt(): Long? = rows.firstOrNull()?.first
    @Synchronized override fun deleteOldest(n: Int) = repeat(minOf(n, rows.size)) { rows.removeAt(0) }
    @Synchronized override fun replaceAll(rows: List<Pair<Long, String>>) {
        this.rows.clear(); this.rows += rows
    }
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
    // Cached count/oldest so append() doesn't issue count()+oldestAt() queries every time;
    // maintained on every mutation, primed lazily from the store.
    private var cachedCount: Int = -1
    private var cachedOldestAt: Long? = null

    private fun primeCache() {
        if (cachedCount < 0) {
            cachedCount = store.count()
            cachedOldestAt = store.oldestAt()
        }
    }

    @Synchronized
    fun append(t: SignalTransition) {
        primeCache()
        store.append(t.at, SignalCodec.encode(t))
        cachedCount++
        if (cachedOldestAt == null) cachedOldestAt = t.at
        val oldest = cachedOldestAt
        if (cachedCount > maxEntries) {
            foldEntries(cachedCount / 2)
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
    fun health(): Pair<Int, Long?> {
        primeCache()
        return cachedCount to cachedOldestAt
    }

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
        val baselines = finalValues.map { (kind, v) ->
            lastAt to SignalCodec.encode(
                SignalTransition(kind, SignalValue.Unknown, v, lastAt, Trigger.BASELINE))
        }
        val newRows = baselines + all.drop(n)
        // Single atomic swap: process death mid-fold keeps either the old or the new log.
        store.replaceAll(newRows)
        cachedCount = newRows.size
        cachedOldestAt = newRows.firstOrNull()?.first
    }
}
