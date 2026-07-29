package tech.ssemaj.bridge.demos

import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.DurableBlock
import io.github.iamjosephmj.bridge.api.DurableHandle

/**
 * A durable coroutine (M5): a suspend block that survives process death by journaling
 * every `step` result and replaying deterministically. The contract:
 *
 * - side effects only inside `step { }` — each step executes once, ever;
 * - code between steps must be deterministic (time via `now()`, randomness via `random()`);
 * - `delay` is a journaled timer: the block *parks* (unwinds without burning an attempt),
 *   an alarm is scheduled, and replay fast-forwards through completed steps on wake-up.
 *
 * While parked, `Bridge.whyPending(name)` reports `DurableParked(...)` — the demo UI
 * polls exactly that to show the park and then the completion.
 */
private val durableDemoBlock: DurableBlock = {
    val startedAt = now()                              // deterministic: journaled once
    val token = step("fetch-token") { "token-${startedAt % 100_000}" }
    delay(15_000)                                      // parks here; survives death
    step("commit") { "committed $token" }
}

/** Registration only (no enqueue) — must run at every process start for death replay. */
fun registerDurableDemo() = Bridge.registerDurable(Names.DURABLE, durableDemoBlock)

/** Starts (or KEEPs, if already live) an instance of the durable block. */
fun launchDurableDemo(): DurableHandle =
    Bridge.scope().launch(Names.DURABLE, block = durableDemoBlock)
