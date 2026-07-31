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
not cargo. Latest output: `Bridge.state(name).lastOutput`.

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
Bridge.stateFlow("publish")     // Flow<WorkState>: current fold, re-emitted per event
    .collect { render(it.runState) }

Bridge.eventsFlow()             // Flow<WorkEvent>: every journal commit, app-wide
```

Both are cold flows over the journal's listener hook — no polling, no invalidation
machinery. For LiveData, apply `asLiveData()`.

Like `whyPending()` and `ledger()`, `state()`/`stateFlow()` are **total reads** — no
nulls anywhere. An unknown name yields a query-only fold with
`runState == RunState.UNKNOWN` (never journaled), so
`Bridge.state(name).nextChunk` reads clean and honestly returns 0.

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
        return RunResult.Success             // <- the checkpoint commits HERE
    }
}

Bridge.enqueue(workRequest("backup", "photo-backup") {
    chunks(40, estimatedUpBytes = 200_000_000L)
    unmetered(); charging()
    importance(Importance.LOW)
})
```

Every completed chunk is journaled. After a stop, crash, or force-stop mid-run, the next attempt starts at `WorkState.nextChunk`, not chunk 0. This is the configuration behind the measured 1-vs-20 replay result — see [Results](RESULTS.html).

### `chunks(n)` is one task, not n tasks

`chunks(40)` does **not** enqueue 40 work items. It is still one task — one name, one
`WorkState`, one platform job, one set of constraints, one retry budget. The number
tells Bridge how *your* work divides ("my upload has 40 slices"), and Bridge drives one
worker instance through `runChunk(ctx, 0) … runChunk(ctx, 39)` in a loop, in the same
process, back-to-back. Bridge never splits the work itself — it can't know how to cut
your file. It supplies the loop, the per-chunk checkpoint, and the resume index; the
`chunkIndex` argument is how each call knows which slice to do.

If you instead want independently scheduled units with their own constraints and retry
counters, that's a prerequisite DAG (`after(...)`), not chunks.

### The checkpoint contract

You never write checkpoint code — **returning `RunResult.Success` from `runChunk` *is*
the checkpoint.** At that moment Bridge appends a durable `ChunkCompleted(chunkIndex,
output)` event to the on-disk journal; the resume index is derived by folding those
events, never tracked by your worker. Three consequences:

1. **You choose the boundaries by how you slice.** Chunk = checkpoint interval. Each
   `runChunk(ctx, i)` should be a unit that is complete in itself and cheap to not
   repeat. Finer checkpoints → more chunks; coarser → fewer.
2. **Nothing inside a chunk is checkpointed.** If the process dies mid-chunk, that
   whole chunk re-runs from its start on the next attempt. Bridge guarantees
   **at-least-once per chunk, exactly-once per *completed* chunk** — so keep each
   chunk's side effects idempotent (an upload the server dedupes, a DB write keyed so
   a repeat is harmless).
3. **Any other return breaks the loop.** `Retry` reschedules the whole item (completed
   chunks stay completed — the retry resumes at `nextChunk`); `Failure` is terminal;
   a system stop between chunks journals `Stopped` and resumes the same way.

### Checkpointing state, not just position

Call `ctx.setOutput(...)` *before* returning `Success` and the data is journaled inside
that chunk's `ChunkCompleted` event. On resume — even in a brand-new process — all
completed chunks' outputs come back overwrite-merged into `ctx.input`. That's how a
chunk hands a cursor, session id, or running total to its successors across death:

```kotlin
class PagedSyncWorker : ChunkedWorker {
    override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
        val cursor = ctx.input.getString("cursor")        // previous chunk's checkpoint
        val page = api.fetchPage(cursor)
        db.save(page.items)
        ctx.setOutput(bridgeDataOf("cursor" to page.nextCursor))  // part of this checkpoint
        return RunResult.Success
    }
}
```

### Rules the runtime enforces

- The worker must implement `ChunkedWorker` **iff** the request declares `chunks(n)`.
  A mismatch in either direction hard-fails with a journaled `structure mismatch`
  reason instead of silently running chunked work un-chunked.
- `chunks(n)` also feeds admission: restrictive standby buckets grant ~10-minute
  execution windows, and un-chunked work estimated longer than a window is held —
  chunked work passes, because it can make journaled progress across several short
  windows.

### Watching progress

```kotlin
Bridge.state("backup").nextChunk         // resume point / chunks completed so far
Bridge.stateFlow("backup")               // re-emits on every ChunkCompleted
Bridge.ledger("backup")                  // per-attempt chunk ranges: attempt 1 ran 0..5, attempt 2 ran 6..39
```

Continue to [Diagnostics](diagnostics.html) for `whyPending`, `ledger`, and `report`, or [Tier 3](durable.html) for durable coroutines.
