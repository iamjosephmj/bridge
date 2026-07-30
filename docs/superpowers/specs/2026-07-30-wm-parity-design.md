# WorkManager-parity surface — design (phases 1 + 2)

**Date:** 2026-07-30 · **Direction from Joseph:** "do phase 1 and 2, test on the Pixel 6 Pro" —
executed autonomously; scoping decisions below were made without further review.

## Problem

Four capability gaps vs WorkManager blocked drop-in adoption: `Data` payloads, tags,
LiveData/Flow observers, and multi-branch chains. Each has a well-understood WorkManager
mechanism; each maps onto Bridge's journal — usually more simply, because the journal is
already the single write path and single source of truth.

## Phase 1

### Data payloads (`BridgeData`)

WorkManager persists a typed bundle in its Room WorkSpec table and merges outputs via
InputMerger. Bridge journals instead:

- `BridgeData`: immutable string map with typed parse-on-read getters, 10KB cap enforced
  at enqueue (`input()` in the DSL). Journaled verbatim on `Enqueued`.
- Workers read `ctx.input` and call `ctx.setOutput(data)`. Output lands on `Finished`
  (plain workers) or per-`ChunkCompleted` (chunked workers) — so the ledger shows what
  every attempt received and produced, which WorkManager cannot.
- `WorkState.lastOutput` folds from those events.
- Merge semantics everywhere are **overwrite** (WorkManager's OverwritingInputMerger):
  enqueue input ← prerequisite outputs (declaration order) ← journaled chunk outputs.
- Compat: `Data` (Builder + `workDataOf`), `setInputData`, `Worker.inputData`,
  `Result.success(data)/failure(data)`, `getOutputData(name)`. Chain links relay data
  link-to-link; per-chunk journaled outputs mean a chain resumed after death still sees
  its completed links' outputs.

### Tags

`tag(...)` in the DSL → `tags` on `Enqueued`/`WorkState` → `Bridge.namesByTag`,
`Bridge.cancelAllByTag`. Compat: `addTag`, `cancelAllWorkByTag`. No side table — state
is a fold, so tag queries are a filter.

### Flow observers

WorkManager needs Room's invalidation tracker; Bridge's journal already had
`addListener`. `Bridge.eventsFlow()` (every event, unlimited buffer) and
`Bridge.stateFlow(name)` (fold re-emitted per event for that name, distinct-until-changed)
are `callbackFlow`s over that hook. Cold; collection registers, cancellation unregisters.
LiveData deliberately not shipped — consumers call `asLiveData()`; Bridge takes no
lifecycle dependency. Compat: `getWorkInfoStateFlow(name)`.

## Phase 2 — multi-branch chains (prerequisite DAG)

WorkManager's model (prerequisite edges per WorkSpec) chosen over compiling DAGs into
durable coroutines: it is journal-native and keeps the existing chain=chunks semantics
untouched.

- `after(vararg names)` in the DSL → `prereqs` journaled on `Enqueued`.
- **Gate** (Dispatcher): ENQUEUED work with prereqs dispatches only when every prereq is
  SUCCEEDED. A FAILED/CANCELLED prereq appends `Finished(success=false,
  "prerequisite 'x' FAILED")` — propagation, WorkManager-style; the dependent never
  Started, so no attempt is burned.
- **Wake**: a permanent journal listener registered at initialize re-runs `dispatchAll()`
  on any `Finished`/`Cancelled` event. Monitor locks are reentrant; cascades terminate
  because propagation flips state to FAILED before recursing.
- **Diagnosis**: `WaitingForPrerequisites(pending)` — resolved by callers (facade/sim)
  since the Diagnoser has no journal access; ranked above device-condition inference.
- **Input merge**: prerequisite `lastOutput`s overwrite into the dependent's input at run
  time, declaration order.
- Compat: `WorkContinuation.combine(list)` — branches enqueue under their own names; the
  join item is `<names joined by '+'>:join` with `after(branches)`. Scoping decision:
  join naming is deterministic-by-convention rather than caller-supplied (WorkManager
  uses opaque UUIDs; compat is unique-name-based, so the convention keeps KEEP semantics
  meaningful).

## Constraints & validations

- `after()` cannot combine with `periodic()` (a repeating dependent has no defined join);
  self-prerequisite rejected.
- Journal compatibility: every new event field has a decode-safe default.

## Scoping decisions made without Joseph

1. **Values are strings** with typed parse-on-read getters (not typed arrays) — journal
   JSON stays human-readable; WorkManager's byte-array Data types can layer later if needed.
2. **Output via `ctx.setOutput`**, not a `RunResult.Success(data)` signature change —
   avoids breaking every existing worker.
3. **No LiveData artifact** — `asLiveData()` exists; a lifecycle dependency does not pay.
4. **Prereqs reference unique names**, not request objects — consistent with Bridge's
   name-first identity model.
5. **Compat REPLACE does not cascade** into branches of a combine (only the item it names).

## Verification

- JVM: DSL validation, facade (tags, stateFlow, DAG gate + doom-by-cancel), sim scenarios
  (h) data round trip, (i) DAG join with merged outputs, (j) WaitingForPrerequisites by
  name, (k) failure propagation without a burned attempt; compat data relay, tag cancel,
  combine join.
- Device (Pixel 6 Pro, API 36): data round trip through real JobScheduler dispatch with
  the Flow observer as the completion signal; tag cancel; DAG join waits for both real
  branches and receives merged outputs. Full connected suite green (8 tests).
