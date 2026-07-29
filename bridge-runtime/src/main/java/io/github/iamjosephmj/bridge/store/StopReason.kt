package io.github.iamjosephmj.bridge.store

/**
 * Canonical stop-reason codes for [WorkEvent.Stopped.stopReason]. The serialized field
 * stays an Int for journal back-compat; every writer/reader goes through this enum so
 * the codes can never collide again.
 *
 * COMPAT NOTE: journals written before this enum existed conflate force-stop and durable
 * park at code 2 — Reconciler journaled STOP_REASON_FORCE_STOP=2 while WorkRunner journaled
 * STOP_REASON_PARKED=2. From this version on, FORCE_STOP=2 and PARKED=3 are distinct;
 * a code-2 event from a pre-fix journal may be either.
 */
enum class StopReason(val code: Int) {
    /** Worker asked for a retry (or threw) with attempts remaining. Counts as a crash. */
    RETRY(0),
    /** The platform stopped the job (timeout/constraint change); Bridge will retry. */
    SYSTEM_STOP(1),
    /** App force-stop detected by the reconciler's sentinel. */
    FORCE_STOP(2),
    /** Durable timer/await park — excluded from crash counting and attempt limits. */
    PARKED(3);

    companion object {
        fun from(code: Int): StopReason? = entries.firstOrNull { it.code == code }
    }
}
