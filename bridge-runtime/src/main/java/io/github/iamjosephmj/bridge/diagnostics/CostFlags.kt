package io.github.iamjosephmj.bridge.diagnostics

/**
 * M4 cost flagging (parent design §4.4.6, v1): flag workers whose measured cost is far
 * out of line with their declared importance. Relative rule — score > 3× the pool
 * median AND importance <= LOW. Flags only; auto-demotion stays opt-in v1.1.
 */
data class CostFlag(
    val workerName: String,
    val meanScore: Long,
    val poolMedian: Long,
    val importance: Int,
) {
    fun render(): String =
        "cost flag: $workerName mean=$meanScore median=$poolMedian importance=$importance " +
            "— expensive work declared unimportant"
}

object CostFlags {

    private const val MIN_RUNS = 3
    private const val FLAG_FACTOR = 3
    private const val IMPORTANCE_LOW = 1

    /** @param workers workerName → (importance, ledger) */
    fun compute(workers: Map<String, Pair<Int, Ledger>>): List<CostFlag> {
        val means = workers.mapNotNull { (name, pair) ->
            val (importance, ledger) = pair
            val scores = ledger.runs
                .filter { it.outcome is LedgerOutcome.Completed && it.cost != null }
                .map { score(it.cost!!) }
            if (scores.size < MIN_RUNS) null
            else Triple(name, importance, scores.sum() / scores.size)
        }
        if (means.isEmpty()) return emptyList()
        val median = means.map { it.third }.sorted()[means.size / 2]
        if (median <= 0) return emptyList()
        return means.filter { (_, importance, mean) ->
            importance <= IMPORTANCE_LOW && mean > FLAG_FACTOR * median
        }.map { (name, importance, mean) -> CostFlag(name, mean, median, importance) }
    }

    private fun score(c: CostDelta): Long =
        c.cpuUserMs + c.cpuSystemMs + (c.txBytes + c.rxBytes) / 1000
}
