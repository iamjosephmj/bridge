---
title: Getting started
nav_order: 2
---

# Getting started

## Modules

| Module | Use it for |
|---|---|
| `bridge-glassbox` | Diagnostics only — works in any app, including apps on plain WorkManager |
| `bridge-compat` | Running existing `androidx.work`-style workers on Bridge via an import change |
| `bridge-runtime` | The native API: constraint DSL, chunked workers, durable coroutines, diagnostics |
| `bridge-sim` | JVM tests that script device regimes (Doze, buckets, thermal, thread pressure) |

Artifacts are not yet published to Maven Central; consume the modules as included builds from the [repository](https://github.com/iamjosephmj/bridge).

## Initialize

Register worker factories at every process start. Relaunching is the recovery path, so this must run in `Application.onCreate` — the same reachability rule WorkManager places on its worker classes.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Bridge.initializeAsync(this) {
            worker("sync") { SyncWorker() }
        }
    }
}
```

`initializeAsync` keeps journal-open and reconciliation off the main thread. Early callers suspend on `Bridge.awaitReady()`; `scope().launch` and `handle.await()` gate on readiness internally.

## A first worker

```kotlin
class SyncWorker : BridgeWorker {
    override suspend fun run(ctx: RunContext): RunResult {
        api.sync()
        return RunResult.Success
    }
}
```

## A first enqueue

```kotlin
Bridge.enqueue(workRequest("nightly-sync", "sync") {
    network()
    charging()
})
```

Enqueue has KEEP semantics per unique name: enqueueing an existing live name is a no-op, so unconditional enqueue-on-startup is safe.

## Ask why it isn't running

```kotlin
Log.i(TAG, Bridge.whyPending("nightly-sync").render(now))
// ENQUEUED 2h 10m — DeferredByDoze(deep) [REPORTED]
```

From here:

- Constraints, chunked work, and periodic work: [Tier 2 — Runtime](runtime.html)
- Diagnosing without migrating anything: [Tier 0 — Glassbox](glassbox.html)
- Keeping your existing WorkManager workers: [Tier 1 — Compat](compat.html)
