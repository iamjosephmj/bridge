# Bridge

A reimplementation of Android background work that uses what system_server
actually offers: JobWorkItem-multiplexed dispatch, an append-only event journal,
chunk-exact resumption, death forensics via ApplicationExitInfo, and measured
per-run cost via HealthStats.

- Design: `docs/superpowers/specs/2026-07-27-bridge-design.md`
- M1 plan: `docs/superpowers/plans/2026-07-27-bridge-m1-core-scheduler.md`
- M2 design: `docs/superpowers/specs/2026-07-29-bridge-m2-glass-box-design.md`
- Benchmark vs WorkManager: `bench/README.md`
- Simulator: `bridge-sim/README.md`

Status: M5 (durable coroutines v0.5).

## M5 — durable coroutines

Background logic as ordinary suspend functions that survive process death —
deterministic replay (Temporal's model, on-device), not continuation
serialization:

```kotlin
Bridge.initialize(context) {
    durable("publish-post") { ctx ->
        val media = ctx.step("upload") { uploader.upload(draft.attachments) }
        ctx.delay(2 * 60 * 60 * 1000L)   // journaled timer → alarm; survives death
        ctx.await("validated-net") { it.values[NETWORK_VALIDATED] == Flag(true) }
        ctx.step("commit") { db.markPublished(media) }
    }
}
Bridge.durable("publish-post")           // start an instance
```

After death the block re-executes from the top; completed `step()`s return
their journaled results instantly (`StepCompleted` — the generalization of
M1's `ChunkCompleted`); the run reattaches at the first live step, timer, or
await. Parks are first-class (`RunResult.Parked`): they never burn attempts
and never read as crashes; `whyPending()` answers `DurableParked(delay until
14:02)`. Shape changes mid-flight fail explicitly via a positional structure
guard. `BridgeDispatcher` (minimal tier) black-box-stamps every resumption
and cancels on host stop. Signature demo green in the simulator: a 3-step
block survives process death at +30min and deep Doze 1–3h mid-`delay(2h)`,
each step executing exactly once.

Design: `docs/superpowers/specs/2026-07-29-bridge-m5-durable-design.md`.

## M4 — compat + rhythm

- **`bridge-compat`:** an `androidx.work`-shaped façade — `Worker`, `Constraints`,
  `OneTimeWorkRequest`, `enqueueUniqueWork` (KEEP/REPLACE), state queries, cancel,
  and sequential chains. A chain compiles to one Bridge item whose links are
  chunks, so an interrupted chain resumes at the failed link. Migration guide:
  `docs/MIGRATION.md`. (Not covered yet: periodic work, Data, tags, observers.)
- **Rhythm model v1:** descriptive statistics over the signal log — median-gap
  prediction of the next maintenance window; policy holds land on the predicted
  window instead of fixed rechecks once ≥3 windows are observed.
- **Cost flagging:** workers whose measured cost (HealthStats deltas over ≥3 runs)
  exceeds 3× the pool median while declared LOW/MIN are flagged in `report()` —
  "expensive work declared unimportant". No auto-demotion (v1.1, opt-in).

Design: `docs/superpowers/specs/2026-07-29-bridge-m4-compat-rhythm-design.md`.

## M3 — judgment

An L4 policy engine sits in front of dispatch — pure functions from
(journal, signals, request) to decisions, every decision journaled as
`PolicyDecision` and surfaced by `whyPending()` as `HeldByPolicy(why)`:

- **Admission control:** thermal-SEVERE holds; demoted-bucket quota arithmetic
  (ledger-measured duration vs the ~10-minute window heuristic — chunked work
  always admits, that's what chunks are for).
- **Quota budgeting:** LOW/MIN work sheds explicitly in FREQUENT-or-worse
  buckets ("quota reserved for higher-value work"), never silently.
- **Deadline escalation:** `mustCompleteBy(T)` walks DEFAULT → EXPEDITED →
  while-idle alarm as T nears, each step journaled, skips recorded on API
  levels lacking a tier. Dispatched work re-tiers in place.
- **Doze strategy:** maintenance-window burst-drain and doze-exit freshness
  dispatch via the signal-hub broadcasts.

Design: `docs/superpowers/specs/2026-07-29-bridge-m3-judgment-design.md`.

## M2 — the glass box

Bridge explains stalled work. A process-wide signal hub reads nine platform
signals (standby bucket, Doze, background restriction, Data Saver, pending-job
reasons, network validation, battery-opt exemption, maintenance windows,
process deaths), persists transitions to a budgeted signal log, and a pure
rule-set diagnoser folds them into typed verdicts:

```kotlin
Bridge.whyPending("photo-backup")?.render(now)
// ENQUEUED 4h 12m — DeferredByStandbyBucket(RARE) [INFERRED]
//   contributing: DeferredByDoze(deep)
//   evidence: ...
Bridge.ledger("photo-backup")   // per-run history with device context + death forensics
Bridge.report()                 // app-wide one-liner per work item (also via adb:
                                // am broadcast -a io.github.iamjosephmj.bridge.REPORT)
```

`bridge-sim` scripts signal timelines and a fake clock over the real
journal/dispatcher/runner/diagnoser: multi-day device regimes assert in
milliseconds on the JVM (7 canonical scenarios ship as tests).

**M2 acceptance run (Pixel 6 Pro, API 36, 2026-07-29).** `stall` scenario:
battery simulated unplugged, app demoted to RARE, deep Doze forced, same corpus
on both backends, both APIs asked "why":

| item | workmanager says | bridge says |
|---|---|---|
| ping-unmetered_charging | **RUNNING** | `DeferredByDoze(deep) [REPORTED]` |
| medium_sync-unmetered_charging | SUCCEEDED | `DeferredByDoze(deep) [REPORTED]` |
| large_chunked-none | **RUNNING** | `DeferredByDoze(deep) [REPORTED]` |
| large_chunked-unmetered_charging | **RUNNING** | `DeferredByDoze(deep) [REPORTED]` |

The headline turned out stronger than "ENQUEUED with no reason": WorkManager
reports **RUNNING** for jobs the forced idle has stopped — a stale answer, not
just an empty one. Bridge's verdicts carry `basis=REPORTED`: they come from
`getPendingJobReasons`, the platform's own explanation, not inference. An
earlier pass of the same script (before Doze forcing) also produced
`AwaitingConstraint(charging) [REPORTED]` for charging-constrained work on the
unplugged device. Raw reports: `bench/scripts/reports/`. The simulator stall
mirror asserts the same diagnosis types in CI. Platform pending-reason
constants were verified on-device during this run (`DEVICE_STATE = 12`, not 8
as first coded — mapping centralized in `PlatformPendingReasons`).

## M1 acceptance run (Pixel 6 Pro, API 36, 2026-07-28)

`force-stop` scenario, `large_chunked` (200MB / 40 chunks), process killed
mid-run and relaunched:

| metric | bridge | workmanager |
|---|---|---|
| attempts | 2 | 2 |
| chunksReplayed | **1** | **20** |
| timeToCompleteMs | 61,350 | 72,346 |

Bridge re-executed only the single chunk that was mid-flight at the moment of
the kill (the chunk-execution ledger records at chunk start, so an interrupted
chunk counts as executed); every completed chunk's result survived.
WorkManager, with no resume primitive, restarted the job from chunk 0.
Raw reports: `bench/scripts/reports/`.
