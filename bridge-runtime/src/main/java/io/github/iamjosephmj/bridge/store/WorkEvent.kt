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
    ) : WorkEvent

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
    ) : WorkEvent

    @Serializable @SerialName("cancelled")
    data class Cancelled(override val workId: String, override val at: Long) : WorkEvent

    /** L4 judgment record: "hold" / "shed" / "admit:EXPEDITED" / "escalate:ALARM" / "skip:EXPEDITED". */
    @Serializable @SerialName("policyDecision")
    data class PolicyDecision(
        override val workId: String, override val at: Long,
        val decision: String, val why: String,
    ) : WorkEvent
}
