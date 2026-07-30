---
title: Tier 2 — Runtime
nav_order: 5
---

# Tier 2 — Runtime: the full engine

![Constraint chips light up one by one — charging, unmetered, batteryNotLow, deviceIdle — then the work dispatches as a multiplexed JobWorkItem.](assets/tier2-runtime.svg)

The complete engine — the layer every measured result runs on.

## The constraint DSL

```kotlin
Bridge.enqueue(workRequest("nightly-sync", "sync") {
    network()                    // any connected network (unmetered() for Wi-Fi-class)
    charging()
    batteryNotLow()
    storageNotLow()
    deviceIdle()                 // JobInfo.setRequiresDeviceIdle
    contentTrigger("content://media/photos", descendants = true)  // JobInfo.TriggerContentUri
    importance(Importance.LOW)   // feeds the policy engine, not just the platform
    maxThreadPressure(PressureLevel.MEDIUM)  // dispatch only while runnable threads <= cores x 2
    initialDelay(10 * 60_000L)   // exact-path setMinimumLatency
    maxAttempts(5)
    mustCompleteBy(tomorrow6amMs)  // deadline escalation: DEFAULT -> EXPEDITED -> while-idle alarm
})
```

Enqueue has KEEP semantics per unique name.

### Importance

`importance()` feeds Bridge's policy engine, not just the platform:

- `MIN` / `LOW` work yields under bucket quota and under thread pressure.
- `DEFAULT` work yields only under HIGH thread pressure.
- `HIGH` importance and deadline work never wait on policy.

### Thread pressure

Before dispatching, the policy engine reads the process's runnable-thread count (from `/proc/self/task`) and classifies it against CPU cores: LOW (≤ cores), MEDIUM (≤ cores × 2), HIGH (beyond). MEDIUM defers MIN/LOW-importance work; HIGH also defers DEFAULT. `maxThreadPressure(level)` overrides the mapping per request in either direction. Every hold is journaled and visible in `whyPending()` with the arithmetic spelled out.

### Deadlines

`mustCompleteBy(atMs)` walks urgency tiers as the deadline approaches: the base tier while more than half the window remains, then a promoted tier, then EXPEDITED (API 31+), and finally a while-idle alarm. Each escalation step is journaled.

## Retries and backoff

A worker that returns `RunResult.Retry` is rescheduled by the platform, not by a Bridge timer: every compiled job declares `JobInfo.setBackoffCriteria(30s, EXPONENTIAL)`, so re-deliveries arrive at 30s, 60s, 120s, … up to the platform's ceiling. (Device-idle and periodic jobs don't declare backoff — the platform forbids it for both.)

On top of that ride two Bridge behaviors:

- **`maxAttempts` is the cap.** The attempt that exceeds it is journaled as terminal `FAILED` — the ledger shows which attempt died and why.
- **Crashes are not retries.** A process crash triggers the platform's own ~30-minute crash backoff; diagnostics surface it as `ThrottledAfterCrashes(n)` rather than leaving the gap unexplained. And parks (`RunResult.Parked`, durable timers/awaits) never burn attempts and never enter the backoff ladder at all.

## Periodic work

```kotlin
Bridge.enqueue(workRequest("heartbeat", "sync") {
    periodic(30 * 60_000L)       // >= 15 min, the platform floor
})
```

Each cycle is a journaled generation; cancelling ends the series. Periodic work cannot combine with `contentTrigger` — the platform forbids it, and the builder enforces it at enqueue time.

## Chunked resumption

```kotlin
class PhotoBackupWorker : ChunkedWorker {
    override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
        uploader.upload(part = chunkIndex)   // small, independently-committed unit
        return RunResult.Success
    }
}

Bridge.enqueue(workRequest("backup", "photo-backup") {
    chunks(40, estimatedUpBytes = 200_000_000L)
    unmetered(); charging()
    importance(Importance.LOW)
})
```

Every completed chunk is journaled. After a stop, crash, or force-stop mid-run, the next attempt starts at `WorkState.nextChunk`, not chunk 0. This is the configuration behind the measured 1-vs-20 replay result — see [Results](RESULTS.html).

Continue to [Diagnostics](diagnostics.html) for `whyPending`, `ledger`, and `report`, or [Tier 3](durable.html) for durable coroutines.
