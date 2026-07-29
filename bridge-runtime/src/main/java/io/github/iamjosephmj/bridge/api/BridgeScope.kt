package io.github.iamjosephmj.bridge.api

import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.diagnostics.Verdict
import io.github.iamjosephmj.bridge.store.WorkState

/**
 * Coroutine-shaped entry point for durable work: `Bridge.scope().launch("name") { ... }`.
 *
 * `launch` registers (or re-registers) the block and starts an instance with KEEP
 * semantics — launching a name whose work is already live is a no-op enqueue but still
 * re-registers the block, which is exactly what replay-after-death needs. For that
 * recovery to work, the launch call must live on a path that runs at every process
 * start (e.g. `Application.onCreate`), the same reachability rule WorkManager places
 * on its worker classes.
 */
class BridgeScope internal constructor() {

    fun launch(
        name: String,
        constraints: WorkRequestBuilder.() -> Unit = {},
        block: DurableBlock,
    ): DurableHandle {
        Bridge.registerDurable(name, block)
        Bridge.enqueue(workRequest(name, name, constraints))
        return DurableHandle(name)
    }
}

/** Handle to a launched durable — queries delegate to the Bridge facade. */
class DurableHandle internal constructor(val name: String) {
    fun state(): WorkState? = Bridge.state(name)
    fun whyPending(): Verdict? = Bridge.whyPending(name)
    fun cancel() = Bridge.cancel(name)
}
