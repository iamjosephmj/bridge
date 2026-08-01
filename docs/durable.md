---
title: Tier 3 — Durable coroutines
nav_order: 7
---

# Tier 3 — Durable coroutines

![A heartbeat trace flatlines at a force-stop tick, then resumes at exactly the same point and finishes SUCCEEDED — each step ran once.](assets/tier3-durable.svg)

Background logic as ordinary suspend functions that survive process death — a deterministic-replay model in the style of Temporal, on-device, with no continuation serialization.

```kotlin
// Must run on a path that executes at every process start (Application.onCreate) —
// relaunching IS the recovery path. launch() has KEEP semantics: if the work is
// already live in the journal, this re-registers the block and reattaches.
val handle = Bridge.scope().launch("publish-post") {
    // step(): the only place for effects. Executes once ever — after a process
    // death, replay returns the journaled result instantly without re-running it.
    val media = step("upload") { uploader.upload(draft.attachments) }

    // Journaled timer backed by an alarm. The block parks; the process can die
    // here and the alarm still fires. On wake, replay fast-forwards through
    // "upload" and resumes at this exact point.
    delay(2.hours)

    // Parks until the signal hub satisfies the predicate; satisfaction is journaled.
    await("validated-net") { it.values[SignalKind.NETWORK_VALIDATED] == SignalValue.Flag(true) }

    // Runs only after relaunch if the process died above — exactly once.
    step("commit") { db.markPublished(media) }
}

handle.join()                    // suspends until terminal state
val end = handle.await()         // same, returning SUCCEEDED / FAILED / CANCELLED
handle.whyPending()              // e.g. DurableParked(delay until 1785561720000) — raw wake time in epoch ms
```

## The contract

- **Effects live inside `step()`.** A step executes once ever; after death, replay returns its journaled result without re-running it.
- **Code between steps stays deterministic.** Time via `now()`, randomness via `random()` — both journaled.
- **Shape changes fail loudly.** A positional structure guard detects a block whose step sequence changed mid-flight and fails the work rather than corrupting it silently.
- **Parks are first-class.** `delay` and `await` unwind the block with `RunResult.Parked`: parks never burn attempts and never read as crashes.

## Device-verified

Force-stopped mid-`delay(20s)` and relaunched after the timer elapsed while the process was dead: state SUCCEEDED, first step executed once (before the kill, replayed after), second step executed once (only after relaunch), two step events journaled, one park.

![Durable acceptance panel: SUCCEEDED, each step exactly once.](assets/panel-durable.svg)

The simulator's signature test additionally survives death at +30 min and deep Doze for 1–3 h mid-`delay(2h)`. Raw numbers: [Results](RESULTS.html).
