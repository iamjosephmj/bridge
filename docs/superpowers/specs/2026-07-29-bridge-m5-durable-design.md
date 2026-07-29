# Bridge M5 — Durable coroutines (v0.5) design

Parent design: `2026-07-27-bridge-design.md` §4.7. Status: approved design (autonomous
run 2026-07-29; scoping decisions recorded), pre-implementation.

## 1. Goal and scope

Background logic as ordinary suspend functions that survive process death, powered by
deterministic replay (Temporal's model, on-device) — not continuation serialization.
Signature demo (simulator): a durable block surviving process death and Doze
mid-`delay(2.hours)`, with completed steps never re-executing.

Scoping decisions:

- **Parking is a first-class run result.** `RunResult.Parked(wakeAtMs)` — a parked
  durable (timer/await) must NOT burn `maxAttempts` or count as a crash. WorkRunner
  journals `Stopped(STOP_REASON_PARKED=2)` and reports RETRY to the scheduler; the
  diagnoser already counts only `Stopped(0)` as crashes.
- **Structure guard = positional step-name check**, not a hash: on replay, journaled
  step name at position i must equal the code's step name at position i; mismatch
  fails the workflow explicitly (`PolicyDecision("structure-mismatch", detail)` +
  `Finished(success=false)`). Same explicitness as the parent's hash, simpler, and
  the divergence message names both steps.
- **Step results via kotlinx.serialization**, `StepCompleted(name, resultJson)`;
  reified `step<T>()` requires `@Serializable`/primitive results. `ctx.now()` /
  `ctx.random()` are auto-named internal steps (`$sys:now:N`), so determinism costs
  no extra machinery.
- **`ctx.delay`** journals target wake time as a step; if not yet elapsed → park +
  `AlarmGateway.scheduleAt(wakeAt)` (M3's tier; inexact). Replay after the alarm
  skips straight past the elapsed timer. Re-checks before the alarm are cheap
  replays, accepted v1 cost.
- **`ctx.await(name, predicate)`** — v1 API is a named predicate over
  `SignalSnapshot` (the spec sketch's constraint DSL waits for a charging
  SignalKind that doesn't exist yet). Satisfied → journaled as a step; else park;
  re-woken by the existing dispatch paths (broadcast pokes, reconcile, alarm-less).
- **Registration at initialize:** durable blocks register in the config builder
  (`durable(name) { ctx -> ... }`) so replay works after death;
  `Bridge.durable(name)` enqueues an instance. Sim mirrors both.
- **`BridgeDispatcher` lands minimal:** a `CoroutineDispatcher` wrapping a delegate
  that black-box-stamps every resumption and cancels the job when the host signals
  stop. Thermal-aware parallelism and cost attribution wait for real usage.
- **No `async` fan-out inside durable blocks** (parent v1 contract). Chunked work
  keeps `ChunkCompleted`; event unification stops at "StepCompleted generalizes it"
  — no migration of M1 events.
- **Parked visibility:** the worker journals `PolicyDecision("park", why)` before
  parking; the diagnoser maps a park newer than the last `Started` to
  `Diagnosis.DurableParked(why)` — `whyPending("publish-post")` answers
  "parked: delay until 14:02".

## 2. Components

```
bridge-runtime/
  api/WorkRequest.kt      RunResult.Parked(wakeAtMs)
  api/Durable.kt          DurableContext (step/delay/await/now/random), DurableBlock,
                          DurableWorker (replay engine, a plain BridgeWorker),
                          DurableDeps(journal, clock, snapshotProvider, alarmGateway)
  api/BridgeDispatcher.kt dispatcher wrapping delegate: stamp + stop-cancel
  store/WorkEvent.kt      StepCompleted(name, resultJson) — fold: no state change
  exec/WorkRunner.kt      Parked branch: Stopped(PARKED=2), no attempt burn, RETRY
  diagnostics/            Diagnosis.DurableParked(why); diagnoser park rule
  Bridge.kt               BridgeConfigBuilder.durable(name, block); Bridge.durable(name)
bridge-sim/               SimScope.durable + SimulatedDevice.restartProcess()
                          (drops parked payloads + in-memory runner state, keeps the
                          journal, then reconcile-style dispatchAll — the M1 recovery
                          path in miniature)
```

## 3. Replay semantics

`DurableWorker.run`: replay cursor walks this generation's `StepCompleted` events in
order. For each `ctx.step(name, block)`: cursor has an event → verify positional name
(mismatch → explicit fail), decode `resultJson`, return instantly, no effect re-runs.
Cursor exhausted → execute block, journal `StepCompleted`, continue. `delay`/`await`
park via `ParkSignal` (internal CancellationException subclass) caught in the worker,
which returns `Parked(wakeAt)`. Everything before the park replays identically next
run — the process may have died in between; that is the point.

## 4. Testing

- **Unit (JVM, InMemoryJournal):** step executes once / replays from journal;
  positional mismatch fails explicitly; parked delay journals wake time and arms the
  alarm; elapsed delay passes on replay; await parks then passes when the snapshot
  satisfies; now()/random() stable across replay; Parked doesn't consume attempts
  (maxAttempts=1 work parks repeatedly without failing); BridgeDispatcher stamps and
  stop-cancels.
- **Sim scenarios:** (1) signature demo — 3-step block with `delay(2h)` mid-flight;
  process death at +30min and deep Doze 1h–3h; asserts step1 executed exactly once,
  completion after the timer, `DurableParked` verdict while parked; (2) await —
  parks on a predicate, satisfied by a scripted signal flip, completes; (3) death
  mid-step — step re-executes (at-least-once contract for the live step only).
- **Codec:** StepCompleted round-trip.

## 5. Out of scope

ART frame serialization; async fan-out in durable blocks; exact alarms; result
migration/versioning of journaled steps beyond the structure guard; BridgeDispatcher
thermal parallelism + cost attribution; on-device acceptance (sim is the M5 gate —
device E2E rides the existing instrumented suite in a later hardening pass).
