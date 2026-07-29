package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.store.RunState

data class ReportLine(
    val workId: String,
    val runState: RunState,
    val diagnosis: Diagnosis?,          // non-null only for pending work
)

data class BridgeReport(
    val lines: List<ReportLine>,
    val conformanceMode: String,        // "MULTIPLEXED" | "ONE_TO_ONE" | "UNKNOWN"
    val signalLogHealth: Pair<Int, Long?>,
) {
    fun render(now: Long): String = buildString {
        for (l in lines) {
            append(l.workId.padEnd(20)).append(l.runState.name.padEnd(11))
            l.diagnosis?.let { append(Verdict.renderDiagnosis(it)) }
            append('\n')
        }
        append("conformance: ").append(conformanceMode)
        append(" · signal log: ").append(signalLogHealth.first).append(" transitions")
        signalLogHealth.second?.let { oldest ->
            append(" / oldest ").append((now - oldest) / (24 * 60 * 60 * 1000L)).append('d')
        }
    }
}
