package io.github.iamjosephmj.bridge.glassbox

import android.content.Context
import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.SystemBridgeClock
import io.github.iamjosephmj.bridge.diagnostics.Basis
import io.github.iamjosephmj.bridge.diagnostics.DeviceCauses
import io.github.iamjosephmj.bridge.diagnostics.Diagnosis
import io.github.iamjosephmj.bridge.diagnostics.Evidence
import io.github.iamjosephmj.bridge.diagnostics.PlatformPendingReasons
import io.github.iamjosephmj.bridge.signals.AndroidSignalSources
import io.github.iamjosephmj.bridge.signals.InMemoryTransitionStore
import io.github.iamjosephmj.bridge.signals.SignalBroadcasts
import io.github.iamjosephmj.bridge.signals.SignalHub
import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalLog
import io.github.iamjosephmj.bridge.signals.SignalSource
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.signals.SqliteTransitionStore
import io.github.iamjosephmj.bridge.signals.Trigger
import io.github.iamjosephmj.bridge.signals.TransitionStore

/**
 * Standalone "why isn't my background work running?" — no Bridge scheduler required.
 * Works for ANY app, including WorkManager apps: WorkManager's jobs are the app's own
 * JobScheduler jobs, so their platform pending reasons (API 34+) are readable here.
 *
 * ```kotlin
 * GlassBox.install(application)          // Application.onCreate
 * Log.i(TAG, GlassBox.explain().render())
 * // device: DeferredByStandbyBucket(RARE), DeferredByDoze(deep)
 * // jobs:   3 pending — DeferredByDoze(deep) [REPORTED]
 * ```
 */
object GlassBox {
    private var hub: SignalHub? = null
    private var clock: BridgeClock = SystemBridgeClock()

    @Synchronized
    fun install(
        context: Context,
        sources: List<SignalSource>? = null,
        store: TransitionStore? = null,
        clock: BridgeClock = SystemBridgeClock(),
    ) {
        if (hub != null) return
        this.clock = clock
        val app = context.applicationContext
        val log = SignalLog(store ?: SqliteTransitionStore(app, "glassbox-signals.db"))
        // Default namespace: where WorkManager (and most apps) schedule their jobs.
        val src = sources ?: AndroidSignalSources.all(app, jobNamespaces = listOf(null))
        val h = SignalHub(src, log, clock)
        hub = h
        try { SignalBroadcasts(h, src).start(app) } catch (_: Exception) { }
    }

    /** One snapshot, explained: device-level causes + per-platform-reason job causes. */
    fun explain(): Explanation {
        val h = checkNotNull(hub) { "GlassBox.install() not called" }
        val snapshot = h.snapshot(Trigger.DIAGNOSIS)

        val device = DeviceCauses.from(snapshot)

        val reported = (snapshot.values[SignalKind.PENDING_REASONS]
            as? SignalValue.PendingReasons)?.reasons.orEmpty()
        val jobCauses = reported.map { PlatformPendingReasons.map(it, snapshot) }
        val evidence = snapshot.values.map { (k, v) ->
            Evidence(k, v, snapshot.at, Trigger.DIAGNOSIS) }
        return Explanation(device, jobCauses,
            if (reported.isNotEmpty()) Basis.REPORTED else Basis.INFERRED, evidence)
    }

    @Synchronized
    fun reset() { hub = null }
}

data class Explanation(
    val deviceCauses: List<Diagnosis>,
    val jobCauses: List<Diagnosis>,       // from the platform's own pending reasons (34+)
    val basis: Basis,
    val evidence: List<Evidence>,
) {
    fun render(): String = buildString {
        append("device: ")
        append(if (deviceCauses.isEmpty()) "nothing blocking" else deviceCauses.joinToString())
        if (jobCauses.isNotEmpty()) {
            append("\njobs:   ").append(jobCauses.joinToString()).append(" [").append(basis).append("]")
        }
        for (e in evidence) append("\n  ").append(e.kind).append(" = ").append(e.value)
    }
}
