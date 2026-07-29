# Bridge M2 — Glass box (v0.2) design

Parent design: `2026-07-27-bridge-design.md` (§4.3 L3, §6 phasing M2).
Status: approved design, pre-implementation.

## 1. Goal and scope

M2 makes Bridge explain itself. Signature demo: the same stalled job —
WorkManager reports `ENQUEUED` and nothing else; Bridge returns a verdict
naming the cause.

Scoping decisions (agreed 2026-07-29):

- **Explanation-driven signal subset.** The hub lands with the 9 sources that
  `whyPending()` and the ledger consume, not the full 17-source table.
  Remaining sources (thermal forecast, discharge prediction, next-user-alarm,
  usage rhythm, cache quota, charge time, cold-start attribution) slot in
  behind the same `SignalSource` interface in M3/M4 when policy needs them.
  This narrows the parent doc's "L3 signal hub complete" line deliberately.
- **Diagnosis + evidence verdict.** One primary typed diagnosis chosen by an
  ordered rule set, plus untrimmed evidence. No confidence scoring.
- **Signals + gateway simulator.** `bridge-sim` scripts signal timelines, the
  clock, and a simulated `JobGateway` with a simple documented gating model —
  full work lifecycles run on the JVM in fake time. No attempt at faithful
  JobScheduler emulation.
- **Bench `stall` scenario + sim mirror.** The demo ships as an adb-driven
  bench scenario on a real device, mirrored as a simulator scenario.
- **Cost is plumbing, not a claim.** The ledger surfaces per-run HealthStats
  deltas as data; no published cost numbers in M2 (bench's simulated I/O would
  make them hollow — see memory `bench-harness-deferred-cleanups`). Cost
  claims wait for M4.

Deviation from parent doc: signal observations append to a **process-wide
signal log**, not the per-work journal (parent §4.3 says "every observation
appended as `SignalObserved`"). Signals are process-scoped; journaling them
per-work would duplicate events and bloat the M1 journal. Event-sourced spirit
retained; only the destination changes.

## 2. Components

```
bridge-runtime/
  signals/
    SignalSource.kt     interface + 9 concrete sources
    SignalHub.kt        process-wide owner: lazy poll, broadcasts, diffing
    SignalLog.kt        append-only process-wide log, transitions only
  diagnostics/
    Verdict.kt          typed diagnosis + evidence model
    Diagnoser.kt        pure rules: (snapshot, work events, slice) -> Verdict
    Ledger.kt           read-time run/signal correlation
bridge-sim/             new JVM-only module
  SimulatedDevice.kt    scripted signal timelines + FakeClock
  SimulatedGateway.kt   JobGateway impl, simple gating model
  scenario DSL          simulate {}, verdictAt(), completedWithin()
bench/
  stall scenario        no prime(); bucket demotion; WorkInfo vs Verdict
```

The 9 signal sources: pending-job reasons + history (API 34/36), standby
bucket (28), background restricted (28), Data Saver (24), Doze light/deep
(23/33), maintenance windows (derived), network validation (23), battery-opt
exemption (23), process deaths (30). Below the source's min API the value is
`Unknown`, never a guess.

`Bridge` facade additions: `whyPending(name): Verdict?`,
`ledger(name): Ledger?`, `report(): BridgeReport`.

## 3. Signal hub and signal log

**Polling.** `SignalHub.snapshot()` reads all sources on demand, returning a
`SignalSnapshot`. Invoked at scheduling decision points (enqueue, dispatch,
reconcile, `whyPending()`) and on registered broadcasts
(`DEVICE_IDLE_MODE_CHANGED`, bucket change, connectivity callbacks). No
timers, no wakeups, nothing continuous.

**Transition detection.** The hub diffs each snapshot against the last one in
memory and appends only changes to the log as
`SignalTransition(signal, from, to, at, trigger)`. `trigger` records what
prompted the observation (broadcast vs scheduling decision) — a change noticed
late is recorded at observation time with the trigger explaining the gap.
First observation of each signal per process start is always logged
(baseline).

**Persistence.** `SignalLog` reuses `Journal`'s durable-write machinery
(append, fsync policy, codec versioning) in its own file. Budget: 4,000
transitions or 14 days, whichever first; on breach, fold the oldest half into
one `SignalBaseline` record — old history degrades to "state as of t", never
disappears silently.

**Read path.** `SignalLog.slice(fromMs, toMs)`: transitions in the interval
plus the baseline in effect at `fromMs`. The only query diagnoser and ledger
need.

**Death correlation.** `DeathAttributor` findings are cross-referenced with
the signal-log slice around the death time, so the ledger reports "died during
deep Doze, bucket RARE, exit reason 10", not just the exit code.

## 4. Verdict and diagnoser

```kotlin
data class Verdict(
    val workId: String,
    val state: WorkState.Phase,
    val diagnosis: Diagnosis,           // one primary answer
    val contributing: List<Diagnosis>,  // stacked causes, unranked
    val evidence: List<Evidence>,       // every signal consulted: value, at, source
    val basis: Basis,                   // REPORTED (API 34+ reasons) or INFERRED
) { fun render(): String }
```

`Diagnosis` is sealed, one case per condition: `DeferredByStandbyBucket`,
`DeferredByDoze`, `BackgroundRestricted`, `DataSaverBlocked`,
`AwaitingConstraint`, `AwaitingConformanceFallback`, `ThrottledAfterCrashes`,
`NotDispatched`, plus terminals `Running`/`Finished` (it isn't pending) and
`Unexplained(evidence)` (nothing matched). No free-text diagnoses; every case
is assertable in the simulator.

`Diagnoser.diagnose(snapshot, workEvents, signalSlice): Verdict` is a pure
function, no Android imports. Rules are an ordered matcher list,
most-specific first:

1. platform-reported pending reasons (API 34+) — `basis = REPORTED`
2. Bridge's own held decisions from the work journal (conformance fallback
   pending, crash backoff)
3. device-state inference: background restricted > Data Saver > Doze >
   standby bucket

First match is `diagnosis`; later matches go to `contributing`. Below API 34
everything is `INFERRED` and `render()` says so. Evidence is never trimmed.

## 5. Ledger and report

`ledger(name)` is a read-time projection, no new persistence:

```kotlin
data class Run(
    val attempt: Int,
    val dispatchedAt: Long?, val startedAt: Long?, val endedAt: Long?,
    val outcome: RunOutcome,        // Completed | Stopped(reason) | Died(exitInfo) | Cancelled
    val chunksExecuted: IntRange?,
    val cost: CostDelta?,           // HealthStats delta if captured; data only
    val deviceContext: SignalSlice, // signal-log slice over the run interval
)
```

`report()` (M2-thin): per work name — folded phase, last-run outcome, and for
pending work the `Verdict.diagnosis` one-liner; process-level — conformance
mode (multiplexed vs 1:1) and signal-log health. `render()` for adb. Trends
and cost aggregation are M4; excluded here.

**Read-path error handling.** Diagnostics never throw on a healthy process:
unknown name returns `null` (matches `state()`); corrupt/missing signal log
degrades to live-snapshot-only verdicts with a `SignalHistoryUnavailable`
evidence note; a source that throws (OEM quirk) is caught per-source and
recorded as `Unknown`.

## 6. Simulator (`bridge-sim`)

Gradle module depending on `bridge-runtime` (implemented as a `com.android.library`
module whose simulator classes have no `android.*` imports and whose scenario tests are
plain JVM unit tests — a pure-JVM module cannot compile against bridge-runtime's Android
classes). Three seams:
`BridgeClock` (M1), `SignalSource` (M2), `JobGateway` (M1). The simulator
supplies `FakeClock` (time jumps, no sleeping), scripted sources, and
`SimulatedGateway`.

```kotlin
simulate {
    bucket(RARE at 0.h)
    doze(deep from 1.h until 5.h, maintenanceWindows = escalating())
    dataSaver(on from 2.h)
    val work = enqueue(uploadRequest)
    advanceTo(3.h)
    assertThat(work.verdictAt(3.h)).isDiagnosis<DeferredByDoze>()
    assertThat(work).completedWithin(6.h)
}
```

**Gating model — simple, documented as such.** Fixed order:
background-restricted blocks all; deep Doze blocks except in maintenance
windows; Data Saver blocks metered-network constraints; buckets apply the
platform's documented deferral floors (WORKING_SET ~2h, FREQUENT ~8h,
RARE ~24h). Explicitly not a scheduler-fidelity claim; the module README
states that `completedWithin()` is a logic assertion, not a device guarantee.

Inside a scenario the real `Journal`, `Dispatcher`, `WorkRunner`, `SignalHub`,
and `Diagnoser` run — everything but Android services. Consequence: those
classes must stay free of `android.*` imports; where an Android type currently
leaks in, M2 introduces a thin interface (targeted extraction, not a module
split).

## 7. Bench `stall` scenario

`bench/scripts` gains a `stall` variant: skips `prime()`; demotes the app
(`am set-standby-bucket <pkg> rare`; background-restricted variant via
`appops set <pkg> RUN_ANY_IN_BACKGROUND deny`); enqueues the same corpus entry
on both backends; waits a fixed observation window (no force-stop — the job
simply doesn't run); snapshots into the JSON report:

- WorkManager column: `WorkInfo.state` (expected `ENQUEUED`), everything its
  API offers.
- Bridge column: full `Verdict` + `render()` string.

Cleanup path (bucket restore, appops reset) runs even on abort so a failed
run doesn't leave the bench app throttled for M1 scenarios. Report records
device model + API level (OEM-variable demotion behavior) and `basis`
(API-36 Pixel: `REPORTED`; older: `INFERRED`).

The M2 README table is the two columns side by side — this is the acceptance
artifact, mirroring M1's.

## 8. Testing

- **Unit (JVM):** Diagnoser rule table — one test per `Diagnosis` case,
  precedence, `Unexplained` fallback; SignalLog budget/fold/slice; transition
  diffing; ledger correlation.
- **Simulator:** the stall mirror + canonical scenarios (Doze with
  maintenance windows, bucket ladder, Data Saver vs unmetered constraint,
  crash-backoff). Both acceptance tests and shipped examples.
- **Robolectric:** the 9 sources across API 26/28/31/34/36 — values and
  `Unknown` degradation.
- **Instrumented:** one on-device E2E — demote bucket, enqueue, assert
  `whyPending()` names the bucket; existing E2E suite stays green.
- **Acceptance gate:** `stall` bench run on the Pixel 6 Pro, table in README.

## 9. Out of scope for M2

Remaining 8 signal sources; policy decisions from signals (M3); cost claims
and trend reporting (M4); compat façade (M4); durable coroutines (M5);
faithful scheduler emulation (never — documented limitation).
