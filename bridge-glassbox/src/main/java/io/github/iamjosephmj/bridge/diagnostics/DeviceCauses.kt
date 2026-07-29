package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.signals.DozeMode
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalSnapshot
import io.github.iamjosephmj.bridge.signals.SignalValue

/**
 * Pure device-cause rules shared by GlassBox.explain() and Diagnoser's device-state block.
 * Ordered most-blocking first: bg-restricted, data-saver, doze, standby bucket.
 *
 * [requiresUnmetered] scopes the data-saver rule to the work being diagnosed: pass the
 * work's unmetered constraint (Diagnoser) so data saver only matches work it can actually
 * block, or null (GlassBox) for the unconditional device-level view.
 */
object DeviceCauses {

    fun from(snapshot: SignalSnapshot, requiresUnmetered: Boolean? = null): List<Diagnosis> {
        val out = mutableListOf<Diagnosis>()
        if (snapshot.values[SignalKind.BG_RESTRICTED] == SignalValue.Flag(true)) {
            out += Diagnosis.BackgroundRestricted
        }
        if (snapshot.values[SignalKind.DATA_SAVER] == SignalValue.Flag(true) &&
            requiresUnmetered != false) {
            out += Diagnosis.DataSaverBlocked
        }
        val doze = snapshot.values[SignalKind.DOZE]
        if (doze is SignalValue.Doze && doze.mode != DozeMode.NONE) {
            out += Diagnosis.DeferredByDoze(doze.mode == DozeMode.DEEP)
        }
        val bucket = snapshot.values[SignalKind.STANDBY_BUCKET]
        if (bucket is SignalValue.Bucket && bucket.bucket >= 30) {   // FREQUENT or worse
            out += Diagnosis.DeferredByStandbyBucket(bucket.bucket)
        }
        return out
    }
}
