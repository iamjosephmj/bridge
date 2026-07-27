# Bridge — A Reimplementation of Android Background Work

**Date:** 2026-07-27
**Status:** Draft for review
**Repo:** `bridge` (greenfield Android library project)

## 1. Summary

Bridge is an unprivileged Android library that replaces WorkManager with a more
powerful scheduler. WorkManager talks to one system service (JobScheduler)
through a narrow straw: it sets ~10 of the ~30 available `JobInfo` fields, never
uses the execution-side contract (`JobWorkItem`, mid-run progress, per-job
network binding), and reads none of the diagnostic or predictive signals
system_server publishes (pending-job reasons history, thermal headroom
forecasts, charge-time prediction, process death forensics, per-UID health
stats). Bridge's thesis: **fuse the full system_server surface — roughly ten
services — into one scheduling layer with judgment.**

Everything Bridge does uses public API, verified against android-36 SDK sources
and WorkManager 2.10.0 sources. No root, no privileged permissions, no custom
ROM.

## 2. Goals

1. **Full scheduler vocabulary.** Compile work requests down to the complete
   `JobInfo` surface: priority, prefetch, estimated/minimum network bytes,
   user-initiated data transfer (UIDT) jobs, namespaces, debug/trace tags,
   windowed alarms.
2. **Explainability.** `whyPending()` answers "why hasn't my work run" from
   `getPendingJobReasons(History)`, standby bucket, background-restriction,
   Data Saver, and Doze state. Every scheduling decision Bridge itself makes is
   journaled with its reason.
3. **Chunk-exact resumability.** Long work declared as resumable chunks via
   `JobScheduler.enqueue(JobInfo, JobWorkItem)`; a kill at chunk N resumes at
   chunk N+1. The 10-minute ceiling and quota kills stop being design hazards.
4. **Closed-loop policy.** Admission control ("don't start what can't finish"),
   deadline escalation, quota budgeting, rhythm-model placement, and
   measured-cost demotion.
5. **Forensics.** Every run's cost measured (`SystemHealthManager` HealthStats
   snapshots); every process death attributed (`ApplicationExitInfo` joined with
   a black-box `setProcessStateSummary` stamp); production traces on regression
   (`ProfilingManager`, API 35).
6. **Testability.** Virtual time and interfaced signal sources throughout, so
   scheduling behavior is unit-testable against simulated device conditions
   (Doze, RARE bucket, thermal pressure) in milliseconds.
7. **Mechanical migration.** An `androidx.work`-shaped compat façade so existing
   app and SDK code moves without rewrites.

## 3. Non-goals

- Defeating Doze, App Standby, or OEM battery managers. Bridge surfs them and
  reports them; it does not circumvent them.
- Exact-time alarms as a product (Bridge uses windowed and while-idle alarms
  internally, but is not an AlarmManager replacement).
- ART/runtime modification, bytecode transformation of user code, or
  continuation serialization. (Possible future direction; explicitly out of
  scope for this design.)
- Multi-process support in v1 (single-process apps first; the journal design
  does not preclude it later).
- KMP/iOS in v1.

## 4. Architecture

Six layers. Each reverses a specific WorkManager defect.

```
┌──────────────────────────────────────────────────────────┐
│ L6  API: compat façade │ new vocabulary │ diagnostics    │
├──────────────────────────────────────────────────────────┤
│ L4  Policy engine (admission, escalation, rhythm, cost)  │
├───────────────────────────────┬──────────────────────────┤
│ L3  Signal hub (10 services)  │ L5  Execution (leases,   │
│                               │     black box, pressure) │
├───────────────────────────────┴──────────────────────────┤
│ L2  Dispatch (host jobs + JobWorkItem queues, alarms)    │
├──────────────────────────────────────────────────────────┤
│ L1  Store (append-only event journal, SQLite WAL)        │
└──────────────────────────────────────────────────────────┘
```

### 4.1 L1 — Store: event-sourced journal

WorkManager's defect: mutable `WorkSpec` rows; state overwritten; history
pruned after ~1 week; no forensics possible.

Design: a single SQLite database (WAL mode) with an append-only `events` table
plus derived, rebuildable read-model tables.

Event types (initial set):

```
Enqueued(workId, spec)            Compiled(workId, jobPlan, reason)
Dispatched(workId, hostJobClass)  Started(workId, attempt, network)
ChunkCompleted(workId, chunkIdx)  Progress(workId, transferredBytes)
Stopped(workId, stopReason)       Died(workId, exitReason, rss, blackBox)
Finished(workId, result, cost)    PolicyDecision(workId, decision, why)
SignalObserved(signal, value)     WindowObserved(kind, start, end)
```

- Current work state = fold over events; cached in a `work_state` read model
  updated in the same transaction as the append.
- All writes go through one serial executor (WorkManager's proven pattern).
- Retention: events pruned by age/size budget (default 30 days / 20 MB),
  configurable; `work_state` never pruned while work is live.
- Generation counter per work id (WorkManager's pattern) so stale
  `JobParameters` can't execute a newer definition.
- All timestamps via an injectable `Clock`; no direct `System.currentTimeMillis`
  anywhere in the library.

### 4.2 L2 — Dispatch: multiplexed host jobs

WorkManager's defect: one JobScheduler job per WorkSpec, throttled to
`maxSchedulerLimit` (20) slots; no coalescing; scheduler blind to the real
queue.

Design: a fixed set of **host job classes**, one JobScheduler job per class,
fed by `JobScheduler.enqueue(JobInfo, JobWorkItem)`:

| Host class | JobInfo profile |
|---|---|
| `default` | ANY network, DEFAULT priority |
| `deferrable` | LOW/MIN priority, prefetch as applicable |
| `unmetered-charging` | unmetered net + charging, LOW priority |
| `expedited` | `setExpedited(true)` (API 31+) |
| `uidt` | `setUserInitiated(true)` + estimated bytes (API 34+) |
| `idle-compute` | `requiresDeviceIdle` + charging |
| `timed` | windowed via AlarmManager, not JobScheduler |

- Each Bridge work item → one `JobWorkItem` (carrying workId + generation +
  per-item network byte estimates). `dequeueWork()`/`completeWork()` drain the
  queue inside the host `JobService`; `getDeliveryCount()` gives system-tracked
  per-item attempts.
- JobScheduler namespace (`forNamespace("bridge")`, API 34) isolates Bridge's
  job ids; `addDebugTag`/`setTraceTag` label jobs in dumpsys/traces.
- Whole queue submitted — no 20-slot drip. JobSchedulerService sees the real
  workload and can batch globally.
- **Timed dispatch:** `AlarmManager.setWindow()` for "run between 02:00–05:00
  local" (DST-aware recomputation on `TIME_CHANGED`/`TIMEZONE_CHANGED`);
  `setExactAndAllowWhileIdle()` reserved for the top deadline-escalation tier
  (budgeted: it is rate-limited to ~1/9min in deep idle).
- **Reconciliation:** WorkManager's `ForceStopRunnable` pattern retained —
  sentinel PendingIntent to detect force-stop; `setPersisted(false)`; full
  reschedule from the journal on boot/update/force-stop. Receivers:
  `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, time/timezone changed.
- **Conformance self-test:** `enqueue()`/`JobWorkItem` behavior varies by API
  level and OEM. On first run (and per OS update) Bridge executes a self-test
  of the host-job path; on failure it falls back to 1:1 dispatch
  (WorkManager-style) for that device, recording the downgrade in the journal.

### 4.3 L3 — Signal hub

WorkManager's defect: reads nothing but its own constraint trackers.

One process-wide component; every source behind an interface (for the
simulator); every observation appended as `SignalObserved`/`WindowObserved`.

| Signal | Source | Min API |
|---|---|---|
| Pending-job reasons (+history) | `JobScheduler.getPendingJobReasons(History)` | 34 / 36 |
| Standby bucket | `UsageStatsManager.getAppStandbyBucket()` + bucket-change broadcast | 28 |
| Background restricted | `ActivityManager.isBackgroundRestricted()` | 28 |
| Data Saver | `ConnectivityManager.getRestrictBackgroundStatus()` | 24 |
| Doze state (light/deep) | `isDeviceIdleMode()` / `isDeviceLightIdleMode()` + `DEVICE_IDLE_MODE_CHANGED` | 23 / 33 |
| Maintenance windows | derived: job execution during idle + idle-mode transitions | 23 |
| Thermal status + headroom forecast | `getCurrentThermalStatus()` / `getThermalHeadroom(s)` | 29 / 30 |
| Charge time remaining | `BatteryManager.computeChargeTimeRemaining()` | 28 |
| Discharge prediction | `PowerManager.getBatteryDischargePrediction()` | 31 |
| Next user alarm | `AlarmManager.getNextAlarmClock()` | 21 |
| Process deaths | `ActivityManager.getHistoricalProcessExitReasons()` | 30 |
| Per-run cost | `SystemHealthManager.takeMyUidSnapshot()` (before/after run) | 24 |
| Cold-start attribution | `ApplicationStartInfo` (`START_REASON_JOB`) | 35 |
| App usage rhythm | `UsageStatsManager.queryEvents()` (requires PACKAGE_USAGE_STATS opt-in; optional) | 21 |
| Network validation | `NetworkCallback` + `NET_CAPABILITY_VALIDATED` | 23 |
| Cache quota / space | `StorageManager.getCacheQuotaBytes` / `allocateBytes` | 26 |
| Battery-optimization exemption | `isIgnoringBatteryOptimizations()` | 23 |

Missing signals on older API levels degrade to `Unknown`, never to a guess.

### 4.4 L4 — Policy engine

WorkManager's defect: no judgment — schedule blind, retry blind.

Pure functions from (journal state, signals, request metadata) → decisions;
every decision journaled as `PolicyDecision(why)`. Sub-policies:

1. **Admission control.** Before dispatching a run: estimated duration vs.
   remaining bucket quota window, thermal headroom forecast over that duration,
   charge-time-remaining for charging-required work, network chunk math vs.
   estimated bytes. If the run cannot finish → hold for the predicted next
   viable window (journaled: `held: quota 0/10min, resets 04:12`), or split
   into chunks if the work supports it.
2. **Deadline escalation.** `mustCompleteBy(T)` walks tiers as T nears:
   deferrable → DEFAULT → HIGH/important-while-foreground → expedited → UIDT →
   (final, budgeted) `setExactAndAllowWhileIdle` — each escalation journaled.
   On API levels lacking a tier, skip it and record the skip.
3. **Quota budgeting.** Spend scarce bucket quota highest-declared-value first;
   shed or defer LOW/MIN work explicitly when quota is low.
4. **Doze strategy.** Maintenance-window burst-drain (drain everything eligible
   at max parallelism when a window opens); window-cadence prediction from
   `WindowObserved` history; doze-exit → immediate freshness dispatch;
   `idle-compute` host class placed deliberately inside deep idle.
5. **Rhythm model.** v1: descriptive statistics over journaled windows (charge
   sessions, unmetered-network sessions, doze windows, next-alarm) predicting
   the next opportunity window per constraint class. No ML in v1; the journal
   makes a learned model a drop-in later.
6. **Cost accounting.** Join `Finished(cost)` (HealthStats deltas) and
   `Died(exitInfo)` per worker; expose per-worker measured cost; flag (v1) and
   optionally auto-demote (v1.1, opt-in) workers whose measured cost is out of
   line with declared importance.

### 4.5 L5 — Execution

WorkManager's defect: fire-and-forget dispatch; interrupt-only mid-run
contract; process death invisible.

- **Leases.** A run executes under a lease: deadline (from JobScheduler's
  budget), the granted `Network` from `JobParameters.getNetwork()` (exposed to
  the worker so transfers ride the right network), and renewal via chunk
  completion.
- **Black box.** Every transition stamps
  `ActivityManager.setProcessStateSummary("workId:step:attempt")`. On next
  start, unmatched `Started` events are joined with
  `ApplicationExitInfo` (+ the recovered summary) into `Died` events — every
  death attributed (LMK, ANR, user-kill), with reason and RSS.
- **Mid-run contract** (worker-facing):
  - `onPressure(level)` — thermal/quota pressure; worker may degrade instead of
    being killed.
  - `onNetworkChanged(network)` — plumbed from `JobService.onNetworkChanged`
    (API 34).
  - `reportProgress(transferred)` — forwarded to
    `updateTransferredNetworkBytes` (and system UI for UIDT jobs).
  - `isDeadlineForced()` — from `isOverrideDeadlineExpired()`: run degraded if
    constraints were overridden.
- **Worker kinds:** `suspend`-function worker (default), `ChunkedWorker`
  (declares resumable chunks → JobWorkItems), compat `Worker`/
  `CoroutineWorker` adapters.
- **Notifications:** long-running work uses `JobService.setNotification()`
  (API 34+) instead of a foreground-service dance; pre-34 falls back to an
  FGS path for UIDT-class work only.
- **Cost measurement:** HealthStats snapshot before/after each run; delta
  recorded in `Finished(cost)`.
- **FCM tickle (integration point, not a dependency):** an app-provided hook
  (`bridge.drainCritical()`) intended to be called from a high-priority FCM
  handler; Bridge drains the critical queue inside the temp-allowlist window.
  Bridge itself has no Firebase dependency.

### 4.6 L6 — API

Three tiers, separate artifacts:

1. **`bridge-compat`** — `androidx.work`-shaped façade (`WorkManager`-like
   entry point, `OneTimeWorkRequest`/`PeriodicWorkRequest`, chains,
   `WorkInfo` LiveData/Flow) delegating to Bridge. Migration is an import
   change. (A bytecode-level redirect of `androidx.work` calls from third-party
   SDKs is a possible later artifact; out of v1 scope.)
2. **`bridge-runtime`** — the native API:

```kotlin
bridge.enqueue("photo-backup") {
    importance(LOW)                      // → setPriority
    deadline(6.hours)                    // → escalation policy
    transfer(up = 200.MB, chunk = 5.MB)  // → estimated/min chunk bytes
    constraints {
        network(UNMETERED, validated = true)
        charging()
        needsBytes(500.MB)               // → allocateBytes
        custom { session.isLoggedIn }    // in-process gate, no retry burn
    }
    window(daily = 2.h..5.h)             // → AlarmManager.setWindow
    chunked { chunkIdx -> uploadChunk(chunkIdx) }
}
```

3. **Diagnostics** (part of runtime): `bridge.whyPending(id)` → structured
   verdict (reasons history + bucket + restriction + Doze + Bridge's own held
   decisions); `bridge.ledger(id)` → run history with stop reasons, deaths,
   measured cost; `bridge.report()` → app-wide summary.

Chains/graphs: v1 supports WorkManager-equivalent chaining (sequence, merge,
unique-name policies) in the compat façade and runtime. Richer graph semantics
(branching, compensation) are explicitly deferred.

## 5. Platform degradation matrix

Bridge targets minSdk 26. Capabilities degrade explicitly — every unavailable
tier is visible in diagnostics, never silently absorbed:

| Capability | Full | Degraded path |
|---|---|---|
| JobWorkItem host jobs | 26+ | — (minSdk floor) |
| Reasons (history) | 34 (36) | inference from signal hub only, labeled "inferred" |
| UIDT + setNotification + onNetworkChanged | 34+ | FGS fallback / no mid-run net events |
| Expedited | 31+ | high-priority host class only |
| setPriority/setPrefetch | 33/28+ | omitted, journaled as unavailable |
| Thermal headroom | 30+ | thermal status only (29), else Unknown |
| ApplicationStartInfo | 35+ | omitted |
| Namespaces | 34+ | reserved job-id range (WorkManager pattern) |

## 6. Testing strategy

- **Simulator (first-class deliverable):** all signal sources and the clock are
  interfaces; a `SimulatedDevice` drives them deterministically:
  `simulate { bucket(RARE); doze(from = 23.h, windows = escalating()); thermal(SEVERE at 2.h) }`
  with assertions like `work.completedWithin(6.h)`. Runs in milliseconds, on
  the JVM.
- **Unit:** journal fold correctness; policy decisions as pure-function tests;
  JobInfo compilation per API level (Robolectric).
- **Device conformance suite:** the L2 self-test packaged as an instrumented
  suite, run in CI across emulator API levels and a physical-device farm
  matrix (priority: MIUI/HyperOS, ColorOS, One UI) — this doubles as the OEM
  behavior dataset.
- **Reliability harness:** long-running instrumented test that enqueues a
  corpus, then force-stops, reboots, clears caches, toggles Doze
  (`adb shell dumpsys deviceidle`), and asserts zero lost work from the
  journal.
- **Benchmark harness (first-class M1 deliverable):** a dedicated app module
  (`bench/`) that runs the *same declarative workload corpus* through two
  backends — WorkManager 2.10 and Bridge — and produces the comparison numbers
  that validate (or refute) the scheduling claims:
  - **Metrics:** time-to-first-execution and time-to-completion per work item;
    work-loss rate after force-stop / reboot / Doze cycles; retry-from-zero vs.
    chunk-resume progress waste (bytes re-transferred); measured per-run cost
    via HealthStats snapshots (taken by the harness itself, so WorkManager runs
    are measured the same way Bridge's are); cold starts attributed to jobs
    (API 35+).
  - **Corpus:** small ping (4 KB), medium sync (5 MB), large chunked transfer
    (200 MB), periodic 15-min work, deadline work — each under constraint
    profiles (none / unmetered+charging / expedited).
  - **Scenario driver:** scripted `adb` scenarios (force-stop, reboot,
    `dumpsys deviceidle` force-idle/unforce cycles, standby-bucket demotion via
    `am set-standby-bucket`) so runs are reproducible across devices.
  - **Output:** one JSON report per (device, backend, scenario); a report
    generator renders the side-by-side table. The device matrix prioritizes
    Pixel (baseline), MIUI/HyperOS, ColorOS, One UI.
  - **Honesty rule:** the harness ships publicly with the library; results are
    published whether they flatter Bridge or not — flat results still validate
    the glass-box half, and the harness itself doubles as the OEM behavior
    dataset collector.

## 7. Risks

| Risk | Mitigation |
|---|---|
| `JobWorkItem` semantics vary across OEMs/API levels | conformance self-test + per-device fallback to 1:1 dispatch |
| Reliability trust must be earned vs. a 10-year incumbent | journal proves delivery; reliability harness in CI from day one; compat façade keeps migration reversible |
| Signal hub battery cost (observing everything) | signals polled lazily on scheduling decisions, not continuously; broadcasts only for cheap events; budget test in CI |
| Journal growth | size/age budgets + fold snapshots |
| Play policy (FGS fallback pre-34) | FGS path only for UIDT-class work; documented justification |
| Scope explosion | phasing below; L4 sub-policies land incrementally behind flags |

## 8. Phasing

- **M1 — Core scheduler (v0.1):** L1 journal, L2 host jobs + conformance
  self-test + reconciliation, L5 leases/black box/HealthStats, minimal L6
  runtime API, **and the benchmark harness (§6)** — the empirical proof is a
  deliverable of the same milestone as the claims it tests. Signature
  capability: chunk-exact resumable transfer surviving quota kill, force-stop,
  and reboot, with published side-by-side numbers vs. WorkManager.
- **M2 — Glass box (v0.2):** L3 signal hub complete, `whyPending()`, ledger,
  diagnostics; simulator released with it. Signature demo: same stalled job —
  WorkManager says `ENQUEUED`, Bridge explains it.
- **M3 — Judgment (v0.3):** L4 admission control, deadline escalation, Doze
  window strategy, quota budgeting.
- **M4 — Compat + rhythm (v0.4):** `bridge-compat` façade, migration guide,
  rhythm-model placement, cost flagging.

Each milestone is independently shippable and demoable.

## 9. Decisions taken (previously open)

- **Compatibility posture:** clean reimplementation with a compat façade
  (option B), bytecode interop deferred (option C later). Rationale: a shim
  over androidx.work caps every headline feature at what its internals allow.
- **v1 shape:** deep-and-narrow first (M1's resumable-transfer slice proves the
  architecture end-to-end), widening in M2–M4.
- **minSdk 26** (JobWorkItem floor; covers >95% of active devices).
