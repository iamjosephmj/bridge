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

## Data payloads

Attach input at enqueue; read it as `ctx.input`; produce output with `ctx.setOutput`:

```kotlin
Bridge.enqueue(workRequest("greet", "echo") { input("msg" to "hello", "count" to 3) })

class EchoWorker : BridgeWorker {
    override suspend fun run(ctx: RunContext): RunResult {
        val msg = ctx.input.getString("msg")          // typed getters parse on read
        ctx.setOutput(bridgeDataOf("echoed" to msg))
        return RunResult.Success
    }
}
```

Input is journaled on the `Enqueued` event and output on `Finished` (or per
`ChunkCompleted` for chunked workers — so a chain resumed after process death still sees
its completed links' outputs). The ledger therefore shows what every attempt received and
produced, not just the latest. Payloads cap at 10 KB — the journal is for coordinates,
not cargo. Latest output: `Bridge.state(name)?.lastOutput`.

## Tags

```kotlin
Bridge.enqueue(workRequest("telemetry-1", "sync") { tag("telemetry") })
Bridge.namesByTag("telemetry")      // → [telemetry-1]
Bridge.cancelAllByTag("telemetry")  // cancels every live item carrying the tag
```

## Multi-branch chains (prerequisite DAG)

`after(names...)` gates dispatch until every named work has SUCCEEDED; branch outputs
overwrite-merge into the dependent's `ctx.input` in declaration order:

```kotlin
Bridge.enqueue(workRequest("resize", "imageWorker") { input("src" to uri) })
Bridge.enqueue(workRequest("caption", "mlWorker") { input("src" to uri) })
Bridge.enqueue(workRequest("publish", "publishWorker") {
    after("resize", "caption")    // dispatches only after both SUCCEED
})
```

While gated, `whyPending()` answers `WaitingForPrerequisites(pending = [...])`. A FAILED
or CANCELLED prerequisite fails the dependent with a journaled reason (`"prerequisite
'resize' FAILED"`) without burning an attempt — WorkManager's propagation semantics,
diagnosable. `after()` cannot combine with `periodic()`.

## Flow observers

```kotlin
Bridge.stateFlow("publish")     // Flow<WorkState?>: current fold, re-emitted per event
    .collect { render(it?.runState) }

Bridge.eventsFlow()             // Flow<WorkEvent>: every journal commit, app-wide
```

Both are cold flows over the journal's listener hook — no polling, no invalidation
machinery. For LiveData, apply `asLiveData()`.

## Retries and backoff

A worker that returns `RunResult.Retry` is rescheduled by the platform, not by a Bridge timer: by default every compiled job declares `JobInfo.setBackoffCriteria(30s, EXPONENTIAL)`, so re-deliveries arrive at 30s, 60s, 120s, … up to the platform's ceiling. (Device-idle and periodic jobs don't declare backoff — the platform forbids it for both.)

The default is overridable per request:

```kotlin
Bridge.enqueue(workRequest("poll-orders", "sync") {
    network()
    backoff(60_000L, BackoffPolicy.LINEAR)   // 60s, 120s, 180s, ...
})
```

`backoff(initialMs, policy)` maps directly to `JobInfo.setBackoffCriteria`; `initialMs` floors at 10 s (`JobInfo.MIN_BACKOFF_MILLIS`), and the builder rejects combining it with `deviceIdle()` (platform rule) or `periodic()` (the period itself paces re-runs) at enqueue time.

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
