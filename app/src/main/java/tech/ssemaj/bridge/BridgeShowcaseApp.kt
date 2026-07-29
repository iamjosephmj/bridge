package tech.ssemaj.bridge

import android.app.Application
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.glassbox.GlassBox
import tech.ssemaj.bridge.demos.DemoChunkedWorker
import tech.ssemaj.bridge.demos.DemoWorker
import tech.ssemaj.bridge.demos.Names
import tech.ssemaj.bridge.demos.registerDurableDemo

/**
 * Showcase entry point: everything Bridge needs at process start lives here.
 *
 * - [Bridge.initialize] builds the journal + dispatcher and reconciles any work that was
 *   live when the process last died. Worker factories are registered inside the config
 *   block so replayed work can always find its implementation — the same reachability
 *   rule WorkManager places on its worker classes.
 * - [GlassBox.install] starts the standalone "why isn't background work running?" signal
 *   hub. It has no dependency on the Bridge scheduler and would work in any app.
 */
class BridgeShowcaseApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Bridge.initialize(this) {
            // One plain suspend worker shared by the simple / constrained / deadline /
            // periodic demos — each demo enqueues it under a different work name.
            worker(Names.WORKER_SIMPLE) { DemoWorker() }

            // Resumable worker: the scheduler drives runChunk(0..9) and journals each
            // completed chunk, so an interrupted run resumes at the next chunk.
            worker(Names.WORKER_CHUNKED) { DemoChunkedWorker() }
        }

        // Durable blocks must be (re-)registered on every process start so a mid-flight
        // instance can replay after death. Registration alone never enqueues anything.
        registerDurableDemo()

        GlassBox.install(this)
    }
}
