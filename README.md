<div align="center">

<img src="docs/assets/hero.svg" alt="Bridge — Android background work that survives death, and explains itself." width="900">

<br>
<br>

<img alt="API 26+" src="https://img.shields.io/badge/API-26%2B-brightgreen"> <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white"> <img alt="Coroutines" src="https://img.shields.io/badge/coroutines-first--class-blue"> <img alt="Tests" src="https://img.shields.io/badge/tests-JVM%20sim%20%2B%20device%20suite-success"> <img alt="Modules" src="https://img.shields.io/badge/modules-glassbox%20%C2%B7%20compat%20%C2%B7%20runtime%20%C2%B7%20sim-informational"> <img alt="Status" src="https://img.shields.io/badge/status-stable-success">

<br>

<b>Force-stop it mid-upload — it resumes at the exact chunk.<br>
Ask it why nothing is running — it answers, with the platform's own evidence.<br>
Any scheduler survives a kill. This one remembers.</b>

<sub>Bridge rebuilds Android background work on what <code>system_server</code> actually offers: an append-only event journal,<br>
JobWorkItem-multiplexed dispatch, death forensics via <code>ApplicationExitInfo</code>, measured cost via <code>HealthStats</code>.</sub>

</div>

---

## Key results

Measured on physical hardware, <b>API 36</b> (2026-07). Identical workloads were run against both backends. Raw reports: [`bench/scripts/reports/`](bench/scripts/reports/).

| Scenario | Bridge | WorkManager |
|---|---|---|
| Force-stop mid-upload (20 chunks) | Resumed at the in-flight chunk — **1** chunk replayed | Restarted from chunk 0 — **20** chunks replayed |
| Time to complete after kill | **61,350 ms** | 72,346 ms |
| Stall diagnosis under forced idle | `DeferredByDoze(deep)` `[REPORTED]` — the platform's own testimony | `RUNNING` — stale; the job had already been stopped |
| Durable coroutine force-stopped mid-`delay(20s)` | **SUCCEEDED**, each step exactly once | Not supported |

<div align="center">

<img src="docs/assets/killdemo.svg" alt="Animated force-stop demo: both schedulers get killed at chunk 6. Bridge resumes at chunk 6; WorkManager starts over from chunk 0. Measured: 1 chunk replayed vs 20." width="920">

<sub>Measured on device: Bridge replayed <b>1</b> chunk; WorkManager replayed <b>20</b> — rescheduled, not resumed.</sub>

<br>
<br>

<img src="docs/assets/whyPending.svg" alt="Animated whyPending terminal: WorkManager answers RUNNING (a lie); Bridge answers DeferredByDoze(deep) [REPORTED] — the platform's own testimony." width="920">

<sub>The same stall, queried through both APIs. Bridge returns the platform-reported cause; WorkManager reports a stale <b>RUNNING</b> state.</sub>

</div>

### <b>The measurements</b> <sub>(raw markdown tables, citable: [`docs/RESULTS.md`](docs/RESULTS.md))</sub>
<br>

<div align="center">

<img src="docs/assets/panel-forcestop.svg" alt="Force-stop replay, measured: attempts 2 vs 2; chunks replayed 1 vs 20; time to complete 61,350 ms vs 72,346 ms (bridge vs workmanager)" width="920">

<sub>Bridge re-executed only the chunk in flight at the kill; every completed chunk's result survived. WorkManager, with no resume primitive, restarted from chunk 0. <a href="docs/RESULTS.md#1-vs-20--force-stop-replay">raw numbers →</a></sub>

<br>
<br>

<img src="docs/assets/panel-stall.svg" alt="Stall verdicts: for ping, medium_sync, large_chunked and large_chunked-uc, workmanager says RUNNING or SUCCEEDED while bridge says DeferredByDoze(deep) [REPORTED]" width="920">

<sub>The bold <b>RUNNING</b>s are stale — the forced idle had already stopped those jobs. Bridge's verdicts carry <code>basis=REPORTED</code>: <code>getPendingJobReasons</code>, the platform's own explanation, not inference. <a href="docs/RESULTS.md#the-stall-verdict">raw numbers →</a></sub>

</div>


<div align="center"><sub>The durable-coroutine result — force-stopped mid-<code>delay(20s)</code>, timer elapsing while the process was dead, <b>SUCCEEDED</b> with each step executed exactly once — is covered in TIER 3 below.</sub></div>

---

## Adoption tiers

Bridge is adopted incrementally. **Each tier is useful on its own, and every step is reversible.**

| Tier | Name | Module | What it provides | Adoption cost |
|---|---|---|---|---|
| 0 | Glassbox | `bridge-glassbox` | Diagnostics for any app's existing jobs | Two lines; nothing to migrate |
| 1 | Compat | `bridge-compat` | `androidx.work`-shaped façade; chains resume at the failed link | An import change |
| 2 | Runtime | `bridge-runtime` | Full engine: constraints, chunks, deadlines, periodic, diagnostics | Native API adoption |
| 3 | Durable coroutines | `bridge-runtime` | Suspend blocks that survive process death | Builds on Tier 2 |
| 4 | Simulator | `bridge-sim` | JVM device regimes in milliseconds for testing | Test-only dependency |

<div align="center">

<img src="docs/assets/ladder.svg" alt="The adoption route drawn as a small bridge: glassbox (diagnose from the shore) → compat (swap an import) → runtime (cross over), with sim (practice on dry land) on the far bank. A dot crosses it on loop." width="920">

<sub><code>bridge-glassbox</code> → <code>bridge-compat</code> → <code>bridge-runtime</code>, rehearsed against <code>bridge-sim</code>. Every step reversible.</sub>

</div>

<br>

### <b><kbd>TIER 0</kbd>&nbsp; Glassbox — diagnose ANY app (two lines, nothing to migrate)</b>
<br>

<div align="center"><img src="docs/assets/tier0-glassbox.svg" alt="A scan sweeps across pending jobs and a verdict appears: 7 pending — DeferredByDoze(deep) [REPORTED]" width="920"></div>

Glassbox requires no migration. **WorkManager jobs are the app's own JobScheduler jobs**, so the platform already reports on them (pending reasons, API 34+) — two lines of integration expose that report.

```kotlin
// Application.onCreate
GlassBox.install(this)

// Anywhere, later:
Log.i(TAG, GlassBox.explain().render())
// device: DeferredByStandbyBucket(RARE), DeferredByDoze(deep)
// jobs:   3 pending — DeferredByDoze(deep) [REPORTED]
```

`GlassBox.explain()` returns a typed `Explanation` — device-level causes, per-job platform-reason causes, basis (`REPORTED` vs `INFERRED`), and the raw signal evidence.


### <b><kbd>TIER 1</kbd>&nbsp; Compat — swap the import; chains resume at the failed link</b>
<br>

<div align="center"><img src="docs/assets/tier1-compat.svg" alt="The androidx.work import gets struck through and swapped for bridge.compat while a broken chain re-links at exactly the broken link" width="920"></div>

Existing workers are unchanged; only the import changes, and chains gain resumption.

An `androidx.work`-shaped façade — for the covered surface, **migration is an import change**:

```kotlin
// before: import androidx.work.*
import io.github.iamjosephmj.bridge.compat.*

class SyncWorker : Worker() {
    override fun doWork(): Result = try {
        api.sync(); Result.success()
    } catch (e: IOException) { Result.retry() }
}

BridgeWorkManager.enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP,
    OneTimeWorkRequest.Builder(SyncWorker::class.java)
        .setConstraints(Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED).build())
        .build())
```

Chains compile to <i>one</i> Bridge item whose links are chunks — so **an interrupted chain resumes at the failed link**, which WorkManager cannot do:

```kotlin
BridgeWorkManager.beginUniqueWork("publish", ExistingWorkPolicy.KEEP, uploadRequest)
    .then(createPostRequest)
    .then(commitRequest)
    .enqueue()
```

Covered surface:

| Area | Coverage |
|---|---|
| One-time work | `OneTimeWorkRequest`, `enqueueUniqueWork` (KEEP), `setInitialDelay` |
| Periodic work | `PeriodicWorkRequest`, `enqueueUniquePeriodicWork` (KEEP / UPDATE) |
| Constraints | Full `Constraints.Builder` surface: charging, network type, battery-not-low, storage-not-low, device-idle |
| Chains | `beginUniqueWork(...).then(...).enqueue()` — resumes at the failed link |
| Introspection & control | `getWorkInfoState`, `cancelUniqueWork` |

Full guide: [`docs/MIGRATION.md`](docs/MIGRATION.md).


### <b><kbd>TIER 2</kbd>&nbsp; Runtime — the full engine: constraints, chunks, deadlines, periodic, diagnostics</b>
<br>

<div align="center"><img src="docs/assets/tier2-runtime.svg" alt="Constraint chips light up one by one — charging, unmetered, batteryNotLow, deviceIdle — then the work dispatches as a multiplexed JobWorkItem" width="920"></div>

The complete engine — the layer every result above runs on.

**Enqueue with the full constraint DSL**

```kotlin
// Application.onCreate — register worker factories at every process start
Bridge.initializeAsync(this) {
    worker("sync") { SyncWorker() }          // BridgeWorker: suspend fun run(ctx): RunResult
}

Bridge.enqueue(workRequest("nightly-sync", "sync") {
    network()                    // any connected network (unmetered() for Wi-Fi-class)
    charging()
    batteryNotLow()
    storageNotLow()
    deviceIdle()                 // JobInfo.setRequiresDeviceIdle
    contentTrigger("content://media/photos", descendants = true)  // JobInfo.TriggerContentUri
    importance(Importance.LOW)   // feeds the policy engine, not just the platform
    initialDelay(10 * 60_000L)   // exact-path setMinimumLatency
    maxAttempts(5)
    mustCompleteBy(tomorrow6amMs)  // L4 escalation: DEFAULT → EXPEDITED → while-idle alarm
})

// Or repeating — each cycle is a journaled generation; cancel ends the series:
Bridge.enqueue(workRequest("heartbeat", "sync") {
    periodic(30 * 60_000L)       // >= 15 min, the platform floor
})
```

Enqueue has **KEEP semantics** per unique name. `initializeAsync` keeps journal-open + reconciliation off the main thread; early callers suspend on `Bridge.awaitReady()`.

**Chunked resumption — the 1-vs-20 primitive**

```kotlin
class PhotoBackupWorker : ChunkedWorker {
    override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
        uploader.upload(part = chunkIndex)   // small, independently-committed unit
        return RunResult.Success
    }
}

Bridge.initializeAsync(this) {
    worker("photo-backup") { PhotoBackupWorker() }
}

Bridge.enqueue(workRequest("backup", "photo-backup") {
    chunks(40, estimatedUpBytes = 200_000_000L)
    unmetered(); charging()
    importance(Importance.LOW)
})
```

Every completed chunk is journaled. Stop, crash, or force-stop mid-run — **the next attempt starts at `WorkState.nextChunk`, not chunk 0**. This is the exact configuration behind the 1-vs-20 result up top.

**Diagnostics — whyPending, ledger, report**

Three questions Bridge always answers: *why isn't it running*, *what happened last time*, *how is everything*. All three are **total functions** — no nulls to defend against; unknown names get an `UnknownWork` verdict:

```kotlin
Bridge.whyPending("photo-backup").render(now)
// ENQUEUED 4h 12m — DeferredByStandbyBucket(RARE) [INFERRED]
//   contributing: DeferredByDoze(deep)
//   evidence:
//     STANDBY_BUCKET    Bucket(RARE)  t=...  BROADCAST
//     DOZE              Doze(DEEP)    t=...  BROADCAST

Bridge.ledger("photo-backup")
// per-attempt history: dispatch/start/end times, outcome (Completed / Stopped /
// Died(exitReason) / Cancelled / InFlight), chunk range executed, HealthStats
// cost delta, and the signal-log slice — "died mid-run" becomes
// "died mid-run during deep Doze"

Bridge.report().render(now)
// backup              ENQUEUED   DeferredByDoze(deep)
// nightly-sync        SUCCEEDED
// publish-post        ENQUEUED   DurableParked(delay until 14:02)
// conformance: MULTIPLEXED · signal log: 412 transitions / oldest 3d
```

Also from adb, no code changes:

```
adb shell am broadcast -a io.github.iamjosephmj.bridge.REPORT \
    -n <pkg>/io.github.iamjosephmj.bridge.diagnostics.ReportReceiver
```

**Under the hood**

Inside `bridge-runtime`, layers stack strictly — each depends only on those below it:
**durable** (DurableScope: step / delay / await, deterministic replay) → **diagnostics** (Diagnoser · Verdict · Ledger · BridgeReport) → **policy** (PolicyEngine: admission, quota, deadline escalation, doze strategy, rhythm) → **signals** (SignalHub: 12 platform signals, budgeted transition log) → **dispatch** (Dispatcher · JobGateway, multiplexed / 1:1 · AlarmGateway · Reconciler) → **journal** (append-only WorkEvent log · SQLite · KvStore).

**Event-sourced journal** — every state change is an appended `WorkEvent` (`Enqueued`, `ChunkCompleted`, `StepCompleted`, `PolicyDecision`, …); current state is a fold over events. Nothing is ever updated in place, so "what happened" is always answerable. → [`bridge-runtime/.../store/`](bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/store/)

**Deterministic replay** — after death, a chunked worker resumes at `nextChunk`; a durable block re-executes from the top with completed `step()`s returning journaled results instantly, reattaching at the first live step, timer, or await. → [`api/Durable.kt`](bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/api/Durable.kt)

**Policy engine** — pure functions from (journal, signals, request) to decisions: thermal holds, bucket-quota admission, deadline escalation, doze burst-drain. Every decision is journaled and surfaced by `whyPending()` as `HeldByPolicy(why)` — nothing is ever silently deferred. → [`policy/`](bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/policy/)

**Signal hub** — twelve platform signals (standby bucket, Doze, background restriction, Data Saver, pending-job reasons, network validation, battery-opt exemption, maintenance windows, process deaths, thermal status, charge time, thread pressure) read into snapshots and persisted transitions; the diagnoser folds them into verdicts. → [`bridge-glassbox/.../signals/`](bridge-glassbox/src/main/java/io/github/iamjosephmj/bridge/signals/)


### <b><kbd>TIER 3</kbd>&nbsp; Durable coroutines — suspend blocks that survive process death</b>
<br>

<div align="center"><img src="docs/assets/tier3-durable.svg" alt="A heartbeat trace flatlines at a force-stop tick, then resumes at exactly the same point and finishes SUCCEEDED — each step ran once" width="920"></div>

Background logic as **ordinary suspend functions that survive process death** — Temporal's deterministic-replay model, on-device, with no continuation serialization:

```kotlin
// Must run on a path that executes at every process start (Application.onCreate) —
// relaunching IS the recovery path, the same reachability rule WorkManager
// places on its worker classes. launch() has KEEP semantics: if the work is
// already live in the journal, this re-registers the block and reattaches.
val handle = Bridge.scope().launch("publish-post") {
    // step(): the only place for effects. Executes ONCE EVER — after a process
    // death, replay returns the journaled result instantly without re-running it.
    val media = step("upload") { uploader.upload(draft.attachments) }

    // Journaled timer → alarm. The block PARKS (unwinds without burning an
    // attempt); the process can die here and the alarm still fires. On wake,
    // replay fast-forwards through "upload" and resumes at this exact point.
    delay(2.hours)

    // Parks until the signal hub satisfies the predicate; satisfaction is journaled.
    await("validated-net") { it.values[SignalKind.NETWORK_VALIDATED] == SignalValue.Flag(true) }

    // Runs only after relaunch if the process died above — exactly once.
    step("commit") { db.markPublished(media) }
}

handle.join()                    // suspends until terminal state
val end = handle.await()         // same, returning SUCCEEDED / FAILED / CANCELLED
handle.whyPending()              // e.g. DurableParked(delay until 14:02)
```

The contract is small: **effects live inside `step()`**, and code between steps stays deterministic (time via `now()`, randomness via `random()`). Shape changes mid-flight fail loudly via a positional structure guard — never silent corruption. Parks are first-class (`RunResult.Parked`): they never burn attempts and never read as crashes.

<b>Device-verified:</b> force-stopped mid-`delay(20s)`, relaunched after the timer elapsed while the process was dead:

<div align="center">

<img src="docs/assets/panel-durable.svg" alt="Durable acceptance, device-verified: state SUCCEEDED; firstStepExecutions 1 (ran before the kill, replayed after); secondStepExecutions 1 (ran only after relaunch); step events journaled 2; parks 1" width="920">

</div>

<sub>Step counters persist on-device precisely because process memory does not — that is the scenario. The simulator's signature demo additionally survives death at +30 min and deep Doze 1–3 h mid-<code>delay(2h)</code>. <a href="docs/RESULTS.md#durable-acceptance--force-stop-mid-delay">raw numbers →</a></sub>


### <b><kbd>TIER 4</kbd>&nbsp; Simulator — practice on dry land: JVM device regimes in milliseconds</b>
<br>

<div align="center"><img src="docs/assets/tier4-sim.svg" alt="A tiny device with a fast-forwarding clock: multi-day Doze regimes asserted in milliseconds on the JVM" width="920"></div>

Multi-day device regimes asserted in milliseconds of JUnit. [`bridge-sim`](bridge-sim/README.md) scripts signal timelines and a fake clock over the <i>real</i> journal / dispatcher / runner / diagnoser. **7 canonical scenarios ship as tests**, including the stall mirror of the device result and the durable signature demo.

```kotlin
simulate {
    worker("upload") { UploadWorker() }
    bucket(Buckets.RARE)
    doze(fromMs = 1.h, untilMs = 5.h, maintenanceEveryMs = 2.h)
    val work = enqueue(workRequest("sync", "upload"))
    assertThat(work.verdictAt(3.h).diagnosis).isInstanceOf(Diagnosis.DeferredByDoze::class.java)
    assertThat(work.completedWithin(26.h)).isTrue()
}
```

Its scope is explicit: **a logic assertion under a scripted regime, not a device guarantee** — the [gating model](bridge-sim/README.md#the-gating-model-is-deliberately-simple) makes no attempt to reproduce OEM heuristics. Device truth comes from the instrumented suite and the benchmark harness ([`bench/`](bench/README.md), with its own [honesty rules](bench/README.md#honesty-rules)).


---

## Bridge vs WorkManager

A direct capability comparison — including the rows WorkManager currently wins, listed at the bottom.

<div align="center">

<img src="docs/assets/scorecard.svg" alt="Bridge vs WorkManager scorecard: bridge leads on resumption, explanation, durable coroutines, chains, forensics, cost, deadlines, quota and doze strategy; WorkManager still wins on Data payloads/tags/observers, multi-branch chains and ecosystem" width="1000">

<sub>Filled mint dot = has it; hollow dot = does not. The mint goes to whoever actually wins the row — including the four rows WorkManager still does. <a href="docs/RESULTS.md#bridge-vs-workmanager">raw table →</a></sub>

</div>

---

## Performance

| Metric | Measured / design | Notes |
|---|---|---|
| Cold start | **318–334 ms, including Bridge init** | `initializeAsync` runs journal-open + reconciliation on a background dispatcher, so `Application.onCreate` returns without paying for it; `scope().launch` and `handle.await()` tolerate pre-init by gating on readiness internally |
| Steady-state main-thread cost | **~zero** | Journal writes go through a dedicated I/O executor; signal snapshots and diagnosis are pull-based, computed only on request |
| Metadata reads (KvStore) | **In-memory-first** | Lock-free `ConcurrentHashMap` reads over a `kv` table in `bridge.db`, DB-before-memory writes — hot-path metadata never touches disk on read |

## Status & roadmap

Bridge is stable and device-verified end to end: the full constraint surface (three silent constraint-loss bugs fixed en route), `initialDelay`, `periodic`, durable coroutines, the compat façade, the policy engine, and the glass box — with the measurements above to show for it.

| Roadmap item | Current state | Planned |
|---|---|---|
| Broader OEM matrix | Device verification on API 36 hardware | Extended verification across Samsung, Xiaomi, OnePlus and Oppo devices |
| Remaining WorkManager surface | `Data` payloads, tags, LiveData/Flow observers, and multi-branch chains remain on WorkManager (the compat façade keeps both runnable side by side) | Incremental coverage |
| Cost auto-demotion | `report()` flags LOW/MIN work measuring 3× the pool median | Automatic demotion as an opt-in |

## Migration

Three stages, each independently shippable and reversible — glass box first, then the import swap, then native for the headline features: [`docs/MIGRATION.md`](docs/MIGRATION.md).

## License

License: TBD — not yet chosen. Until a license file lands, all rights reserved; open an issue if you want to use Bridge in the meantime.
