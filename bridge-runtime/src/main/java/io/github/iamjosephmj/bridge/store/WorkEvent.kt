package io.github.iamjosephmj.bridge.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkEvent {
    val workId: String
    val at: Long

    @Serializable @SerialName("enqueued")
    data class Enqueued(
        override val workId: String, override val at: Long,
        val workerName: String, val generation: Int,
        val importance: Int,                 // 0=MIN 1=LOW 2=DEFAULT 3=HIGH
        val requiresCharging: Boolean = false,
        val requiresUnmetered: Boolean = false,
        val chunkCount: Int = 0,             // 0 = not chunked
        val estimatedUpBytes: Long = 0L,
        val maxAttempts: Int = 3,
        val deadlineMs: Long = 0L,           // 0 = no deadline (mustCompleteBy)
        val requiresNetwork: Boolean = false,
        val requiresBatteryNotLow: Boolean = false,
        val requiresStorageNotLow: Boolean = false,
        val requiresDeviceIdle: Boolean = false,
        val initialDelayMs: Long = 0L,
        val periodicMs: Long = 0L,
        // Content-URI trigger (defaults keep pre-trigger journals decoding).
        val contentUris: List<String> = emptyList(),
        val contentDescendants: Boolean = false,
        val contentUpdateDelayMs: Long = 0L,
        val contentMaxDelayMs: Long = 0L,
        // PressureLevel ordinal this work tolerates; -1 = importance-derived default.
        val maxPressure: Int = -1,
        // Retry backoff (JobInfo.setBackoffCriteria); 0 = default 30s exponential.
        val backoffMs: Long = 0L,
        val backoffLinear: Boolean = false,
        // WM-parity surface (defaults keep pre-parity journals decoding).
        val input: Map<String, String> = emptyMap(),
        val tags: List<String> = emptyList(),
        val prereqs: List<String> = emptyList(),
    ) : WorkEvent {
        /** Factories live as extensions in `api/EnqueuedEvents.kt` — putting them here
         *  would give store (the bottom layer) a dependency on api's WorkRequest. */
        companion object
    }

    @Serializable @SerialName("dispatched")
    data class Dispatched(
        override val workId: String, override val at: Long,
        val hostClass: String, val generation: Int,
    ) : WorkEvent

    @Serializable @SerialName("started")
    data class Started(
        override val workId: String, override val at: Long,
        val attempt: Int, val generation: Int,
    ) : WorkEvent

    @Serializable @SerialName("chunkCompleted")
    data class ChunkCompleted(
        override val workId: String, override val at: Long, val chunkIndex: Int,
        /** Chunk-set output (RunContext.setOutput inside runChunk); survives death for resume. */
        val output: Map<String, String> = emptyMap(),
    ) : WorkEvent

    @Serializable @SerialName("stopped")
    data class Stopped(
        override val workId: String, override val at: Long, val stopReason: Int,
    ) : WorkEvent

    @Serializable @SerialName("died")
    data class Died(
        override val workId: String, override val at: Long,
        val exitReason: Int, val rssKb: Long, val step: String, val attempt: Int,
    ) : WorkEvent

    @Serializable @SerialName("finished")
    data class Finished(
        override val workId: String, override val at: Long, val success: Boolean,
        val cpuUserMs: Long = 0, val cpuSystemMs: Long = 0,
        val txBytes: Long = 0, val rxBytes: Long = 0,
        /** Why the work failed (exception class + message, or a structural reason); null on success or when unknown. */
        val failureMessage: String? = null,
        /** Worker-set output (RunContext.setOutput); overwrite-merged into dependents' input. */
        val output: Map<String, String> = emptyMap(),
    ) : WorkEvent

    @Serializable @SerialName("cancelled")
    data class Cancelled(override val workId: String, override val at: Long) : WorkEvent

    /**
     * M5 durable replay record: the generalization of [ChunkCompleted] (which stays for
     * chunked work). Completed steps return [resultJson] instantly on replay.
     */
    @Serializable @SerialName("stepCompleted")
    data class StepCompleted(
        override val workId: String, override val at: Long,
        val name: String, val resultJson: String, val generation: Int,
    ) : WorkEvent

    /** L4 judgment record: "hold" / "shed" / "admit:EXPEDITED" / "escalate:ALARM" / "skip:EXPEDITED". */
    @Serializable @SerialName("policyDecision")
    data class PolicyDecision(
        override val workId: String, override val at: Long,
        val decision: String, val why: String,
    ) : WorkEvent {
        /** Typed view of [decision]; null for strings no known writer produces. */
        val kind: DecisionKind? get() = DecisionKind.from(decision)
    }
}

/**
 * Typed decision protocol for [WorkEvent.PolicyDecision]. The journal keeps the raw
 * [WorkEvent.PolicyDecision.decision] string for back-compat; readers should match on
 * [WorkEvent.PolicyDecision.kind] instead of string-comparing. [ADMIT] covers the
 * tiered "admit:TIER" form — the tier stays in the string.
 */
enum class DecisionKind(val wire: String) {
    HOLD("hold"),
    SHED("shed"),
    PARK("park"),
    STRUCTURE_MISMATCH("structure-mismatch"),
    ADMIT("admit");

    companion object {
        fun from(decision: String): DecisionKind? = when {
            decision.startsWith("${ADMIT.wire}:") -> ADMIT
            else -> entries.firstOrNull { it.wire == decision }
        }
    }
}
