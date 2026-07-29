package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.signals.Trigger
import io.github.iamjosephmj.bridge.store.RunState

sealed interface Diagnosis {
    data class DeferredByStandbyBucket(val bucket: Int) : Diagnosis
    data class DeferredByDoze(val deep: Boolean) : Diagnosis
    object BackgroundRestricted : Diagnosis { override fun toString() = "BackgroundRestricted" }
    object DataSaverBlocked : Diagnosis { override fun toString() = "DataSaverBlocked" }
    data class AwaitingConstraint(val constraint: String) : Diagnosis   // "charging" | "unmetered"
    object AwaitingConformanceFallback : Diagnosis { override fun toString() = "AwaitingConformanceFallback" }
    data class ThrottledAfterCrashes(val crashes: Int) : Diagnosis
    data class NotDispatched(val reason: String) : Diagnosis
    object Running : Diagnosis { override fun toString() = "Running" }
    object Finished : Diagnosis { override fun toString() = "Finished" }
    data class Unexplained(val note: String) : Diagnosis
}

enum class Basis { REPORTED, INFERRED }

data class Evidence(
    val kind: SignalKind,
    val value: SignalValue,
    val at: Long,
    val trigger: Trigger,
)

data class Verdict(
    val workId: String,
    val state: RunState,
    val diagnosis: Diagnosis,
    val contributing: List<Diagnosis>,
    val evidence: List<Evidence>,
    val basis: Basis,
    val pendingSinceMs: Long?,
    val notes: List<String> = emptyList(),
) {
    fun render(now: Long): String = buildString {
        val pendingFor = pendingSinceMs?.let { formatDuration(now - it) }
        append(state.name)
        if (pendingFor != null && state in setOf(RunState.ENQUEUED, RunState.DISPATCHED)) {
            append(" ").append(pendingFor)
        }
        append(" — ").append(renderDiagnosis(diagnosis))
        append(" [").append(basis.name).append("]")
        if (contributing.isNotEmpty()) {
            append("\n  contributing: ")
            append(contributing.joinToString(", ") { renderDiagnosis(it) })
        }
        for (n in notes) append("\n  note: ").append(n)
        if (evidence.isNotEmpty()) {
            append("\n  evidence:")
            for (e in evidence) {
                append("\n    ").append(e.kind.name.padEnd(18))
                append(renderValue(e.value).padEnd(14))
                append("t=").append(e.at).append("  ").append(e.trigger.name)
            }
        }
    }

    companion object {
        internal fun bucketName(bucket: Int): String = when (bucket) {
            10 -> "ACTIVE"; 20 -> "WORKING_SET"; 30 -> "FREQUENT"
            40 -> "RARE"; 45 -> "RESTRICTED"; else -> "BUCKET_$bucket"
        }

        internal fun renderDiagnosis(d: Diagnosis): String = when (d) {
            is Diagnosis.DeferredByStandbyBucket -> "DeferredByStandbyBucket(${bucketName(d.bucket)})"
            is Diagnosis.DeferredByDoze -> "DeferredByDoze(${if (d.deep) "deep" else "light"})"
            is Diagnosis.AwaitingConstraint -> "AwaitingConstraint(${d.constraint})"
            is Diagnosis.ThrottledAfterCrashes -> "ThrottledAfterCrashes(${d.crashes})"
            is Diagnosis.NotDispatched -> "NotDispatched(${d.reason})"
            is Diagnosis.Unexplained -> "Unexplained(${d.note})"
            else -> d.toString()
        }

        internal fun renderValue(v: SignalValue): String = when (v) {
            is SignalValue.Bucket -> "Bucket(${bucketName(v.bucket)})"
            is SignalValue.Flag -> if (v.on) "on" else "off"
            is SignalValue.Doze -> "Doze(${v.mode.name})"
            is SignalValue.PendingReasons -> "Reasons${v.reasons}"
            is SignalValue.Death -> "Death(reason=${v.exitReason})"
            is SignalValue.Count -> v.value.toString()
            SignalValue.Unknown -> "Unknown"
        }

        internal fun formatDuration(ms: Long): String {
            val m = ms / 60_000
            return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
        }
    }
}
