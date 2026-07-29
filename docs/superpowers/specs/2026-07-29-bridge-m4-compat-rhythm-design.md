# Bridge M4 — Compat + rhythm (v0.4) design

Parent design: `2026-07-27-bridge-design.md` (§4.4 items 5–6, §4.6 tier 1, §8 M4).
Status: approved design (autonomous run 2026-07-29; scoping decisions recorded), pre-implementation.

## 1. Goal and scope

M4 opens the migration path and closes the L4 loop: an `androidx.work`-shaped
`bridge-compat` artifact, a migration guide, the v1 rhythm model (descriptive statistics,
no ML), and per-worker cost flagging.

Scoping decisions:

- **Compat covers the WorkManager surface that real apps migrate first:** `Worker`
  (`doWork(): Result`), `OneTimeWorkRequest.Builder` + `Constraints`
  (charging/unmetered), `enqueueUniqueWork` (KEEP/REPLACE), `getWorkInfoState`,
  and sequential chains via `beginWith().then()`. **Periodic work, LiveData, tags, and
  input/output Data are out** — journaled as the honest v0.4 line; periodic lands with
  its own runtime primitive later, not as a compat shim.
- **A chain compiles to ONE Bridge work item whose links are chunks.** Chunk-exact
  resumption then gives per-link resume for free — a chain interrupted at link 3
  resumes at link 3. This is the design's own vocabulary paying for the façade.
- **Rhythm v1 = maintenance-window cadence.** From the signal log's
  `MAINTENANCE_WINDOW`/`DOZE` history: median gap between window opens predicts the
  next one; quota/thermal holds use the prediction as `untilMs` instead of fixed
  rechecks when history exists (≥3 observed windows). Charge/unmetered session rhythm
  is recorded in the log already; predictions for those wait for real-world traces.
- **Cost flagging is relative and importance-weighted:** a worker flags when its mean
  measured cost (cpuMs + bytes, from ledger `CostDelta`s over ≥3 completed runs)
  exceeds 3× the median across workers AND its declared importance is ≤ LOW —
  "expensive work masquerading as unimportant". Flags appear in `report()`; no
  auto-demotion (that stays opt-in v1.1 per parent §4.4.6).

## 2. Components

```
bridge-compat/ (new module, depends on bridge-runtime)
  Worker.kt            abstract class Worker { abstract fun doWork(): Result }
                       sealed Result { Success, Retry, Failure }
  Constraints.kt       Builder: setRequiresCharging, setRequiredNetworkType(UNMETERED)
  OneTimeWorkRequest.kt Builder(workerClass) + setConstraints
  WorkContinuation.kt  beginWith(req).then(req)... .enqueue() → one chunked Bridge item
  BridgeWorkManager.kt getInstance(ctx); enqueueUniqueWork(name, policy, req);
                       getWorkInfoState(name): WorkInfo.State (maps RunState);
                       cancelUniqueWork(name)
  CompatChainWorker.kt bridge ChunkedWorker: chunk i = link i's doWork()
bridge-runtime/
  policy/RhythmModel.kt  predictNextMaintenance(slice, now): Long? (median-gap)
  policy/PolicyEngine.kt Hold.untilMs uses prediction when ≥3 windows observed
  diagnostics/CostFlags.kt per-worker mean cost over ledger, 3×median × importance rule
  diagnostics/BridgeReport.kt + costFlags: List<CostFlag>; render appends flag lines
docs/MIGRATION.md      WorkManager → bridge-compat → native API, with the out-of-scope list
```

## 3. Semantics

- `enqueueUniqueWork(name, KEEP|REPLACE, request)`: KEEP maps to Bridge's existing
  live-work KEEP; REPLACE cancels then enqueues (new generation).
- `WorkInfo.State` mapping: ENQUEUED/DISPATCHED→ENQUEUED, RUNNING→RUNNING,
  SUCCEEDED→SUCCEEDED, FAILED→FAILED, CANCELLED→CANCELLED.
- Chain item name = first request's name; links execute in order inside one host-job
  run; a link returning Retry retries the whole item from the failed link (chunk
  ledger); Failure fails the chain (remaining links never run).
- Compat workers registered automatically: worker name = class FQN, factory =
  no-arg-constructor reflection at compat-enqueue time.
- RhythmModel: gaps = deltas between successive `MAINTENANCE_WINDOW false→true`
  transitions in the slice; prediction = last open + median gap; if `< now`, walk
  forward whole gaps. Never predicts into the past; <3 windows → null → callers use
  their fixed defaults.
- CostFlags: only workers with ≥3 `Completed` runs and non-null cost enter the pool;
  score = cpuUserMs+cpuSystemMs + (txBytes+rxBytes)/1000; median over pool.

## 4. Testing

- **Compat unit (Robolectric):** builder mapping (constraints → WorkRequest flags),
  KEEP/REPLACE, state mapping, cancel; chain of 3 compat workers runs in order via a
  driven WorkRunner; link-2 Retry resumes at link 2 (chunk ledger asserts).
- **Rhythm unit:** median-gap prediction incl. walk-forward and <3-window null.
- **Cost unit:** flag matrix (expensive+LOW flags; expensive+HIGH doesn't; thin history
  doesn't); report render includes flag line.
- **Sim:** hold-until-predicted-window scenario: doze cadence 3h; after 3 windows a
  quota-held item's `Hold.untilMs` lands on the predicted next window (±tick).

## 5. Out of scope

Periodic work, Data payloads, tags, LiveData/Flow observers, multi-branch chains
(`WorkContinuation.combine`), bytecode interop, charge/unmetered rhythm predictions,
cost auto-demotion.
