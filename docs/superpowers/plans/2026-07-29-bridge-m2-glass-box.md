# Bridge M2 — Glass Box Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bridge explains stalled work: signal hub + signal log + typed verdicts (`whyPending`), per-run ledger with device context, app-wide report, JVM simulator, and a bench `stall` scenario.

**Architecture:** Process-wide `SignalHub` polls 9 platform signals lazily at scheduling decisions/broadcasts, persisting transitions to a budgeted `SignalLog` (separate file from the work journal, per approach C in the spec). A pure `Diagnoser` folds (live snapshot, work events, signal-log slice) into a sealed `Diagnosis` + evidence `Verdict`. `Ledger`/`report()` are read-time projections. A `bridge-sim` module scripts signals + clock + a gated `JobGateway` so full lifecycles run in JVM unit tests.

**Tech Stack:** Kotlin, kotlinx.serialization (existing `EventCodec` pattern), SQLite via `SQLiteOpenHelper` (existing `Journal` pattern), JUnit4 + Robolectric for Android sources, plain JUnit for pure logic. Spec: `docs/superpowers/specs/2026-07-29-bridge-m2-glass-box-design.md`.

## Global Constraints

- minSdk 26; API-gated reads degrade to `SignalValue.Unknown`, never a guess.
- Package root `io.github.iamjosephmj.bridge`; new packages `signals/`, `diagnostics/`; new module `bridge-sim`.
- Diagnostics read path never throws on a healthy process: unknown work name → `null`; corrupt/missing signal log → live-snapshot-only verdict with `SignalHistoryUnavailable` evidence note; per-source exceptions → `Unknown`.
- `Diagnoser` and everything `bridge-sim` executes must have no `android.*` imports.
- SignalLog budget: 4,000 transitions or 14 days; breach folds oldest half into baseline records.
- Commit style: `feat:`/`fix:`/`docs:` + `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Work on branch `m2-glass-box` off master.

---

### Task 1: Branch + housekeeping

**Files:** Modify: `.gitignore`

- [ ] Step 1: `git checkout -b m2-glass-box`
- [ ] Step 2: Append `.idea/` to `.gitignore` (deferred M1 cleanup).
- [ ] Step 3: Commit `chore: ignore .idea/`.

### Task 2: Extract `EventJournal` interface + `InMemoryJournal`

The simulator and pure-JVM tests need the journal without SQLite. Existing `Journal` becomes the SQLite implementation of a new interface.

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/store/EventJournal.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/store/InMemoryJournal.kt`
- Modify: `store/Journal.kt` (implements interface), `dispatch/Dispatcher.kt`, `exec/WorkRunner.kt`, `exec/DeathAttributor.kt` (if it takes `Journal`), `dispatch/Reconciler.kt`, `Bridge.kt` — change parameter types `Journal` → `EventJournal`.
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/store/InMemoryJournalTest.kt`

**Interfaces (Produces):**
```kotlin
interface EventJournal {
    fun append(event: WorkEvent)
    fun appendAll(events: List<WorkEvent>)
    fun events(workId: String): List<WorkEvent>
    fun state(workId: String): WorkState?
    fun liveWork(): List<WorkState>
    fun runningWork(): List<WorkState>
    fun allWork(): List<WorkState>          // NEW: needed by report()
    fun prune(olderThanMs: Long, now: Long)
    fun close()
}
class InMemoryJournal : EventJournal        // ArrayList + fold, no threading
```
`Journal` gains `allWork()` = `statesWhere("1=1")`.

- [ ] Step 1: Write `InMemoryJournalTest` — enqueue→state fold, liveWork filtering, allWork, prune of terminal work (mirror 3 representative cases from `JournalTest`).
- [ ] Step 2: Run; fails (class missing).
- [ ] Step 3: Create interface, make `Journal : EventJournal`, add `allWork()`, write `InMemoryJournal` (synchronized list, same fold).
- [ ] Step 4: Retype consumers (`Dispatcher`, `WorkRunner`, `Reconciler`, `DeathAttributor`, `Bridge`) to `EventJournal`.
- [ ] Step 5: Full `./gradlew :bridge-runtime:test` green.
- [ ] Step 6: Commit `feat(store): EventJournal interface + in-memory implementation`.

### Task 3: Signal model + codec

**Files:**
- Create: `signals/SignalModel.kt`, `signals/SignalCodec.kt`
- Test: `test/.../signals/SignalCodecTest.kt`

**Interfaces (Produces):**
```kotlin
enum class SignalKind { PENDING_REASONS, STANDBY_BUCKET, BG_RESTRICTED, DATA_SAVER,
    DOZE, MAINTENANCE_WINDOW, NETWORK_VALIDATED, BATT_OPT_EXEMPT, PROCESS_DEATH }

@Serializable sealed interface SignalValue {
    @Serializable @SerialName("unknown") object Unknown : SignalValue
    @Serializable @SerialName("flag") data class Flag(val on: Boolean) : SignalValue
    @Serializable @SerialName("bucket") data class Bucket(val bucket: Int) : SignalValue   // UsageStatsManager constants
    @Serializable @SerialName("doze") data class Doze(val mode: DozeMode) : SignalValue
    @Serializable @SerialName("reasons") data class PendingReasons(val reasons: List<Int>) : SignalValue
    @Serializable @SerialName("death") data class Death(val exitReason: Int, val atMs: Long) : SignalValue
}
enum class DozeMode { NONE, LIGHT, DEEP }
enum class Trigger { BASELINE, BROADCAST, SCHEDULING_DECISION, DIAGNOSIS }

@Serializable data class SignalTransition(
    val kind: SignalKind, val from: SignalValue, val to: SignalValue,
    val at: Long, val trigger: Trigger)

data class SignalSnapshot(val at: Long, val values: Map<SignalKind, SignalValue>)
data class SignalSlice(val baseline: Map<SignalKind, SignalValue>,
                       val transitions: List<SignalTransition>)
object SignalCodec { fun encode(t: SignalTransition): String; fun decode(s: String): SignalTransition }
```

- [ ] Step 1: Test — round-trip every `SignalValue` case through `SignalCodec`; decode of garbage throws `SerializationException`.
- [ ] Step 2: Fail → implement (Json with `classDiscriminator = "t"`, mirroring `EventCodec`).
- [ ] Step 3: Green; commit `feat(signals): signal model and codec`.

### Task 4: `SignalLog` — transition store, slice, budget fold

**Files:**
- Create: `signals/SignalLog.kt` (contains `TransitionStore` interface + `InMemoryTransitionStore`)
- Create: `signals/SqliteTransitionStore.kt` (own `SQLiteOpenHelper`, db `bridge-signals.db`, table `transitions(seq PK AUTOINCREMENT, at INTEGER, payload TEXT)`)
- Test: `test/.../signals/SignalLogTest.kt` (in-memory), `test/.../signals/SqliteTransitionStoreTest.kt` (Robolectric)

**Interfaces (Produces):**
```kotlin
interface TransitionStore {
    fun append(at: Long, payload: String)
    fun all(): List<Pair<Long, String>>              // ordered by seq
    fun count(): Int
    fun oldestAt(): Long?
    fun deleteOldest(n: Int)
}
class SignalLog(
    private val store: TransitionStore,
    private val maxEntries: Int = 4000,
    private val maxAgeMs: Long = 14L * 24 * 60 * 60 * 1000,
) {
    fun append(t: SignalTransition)                  // + budget check after append
    fun slice(fromMs: Long, toMs: Long): SignalSlice
    fun health(): Pair<Int, Long?>                   // (count, oldestAt) for report()
}
```
**Budget fold algorithm (Step 3):** when `count() > maxEntries` or `oldestAt() < now - maxAgeMs` (now = the appended transition's `at`): decode oldest half, compute final value per kind (last `to` wins), delete them, re-append one `BASELINE` transition per kind carrying `from = Unknown, to = finalValue, at = <at of newest folded entry>`.
**Slice algorithm:** baseline = per-kind last `to` among transitions with `at < fromMs`; transitions = those with `at in fromMs..toMs`.

- [ ] Step 1: Tests — append/slice basics; baseline resolution before window; entry-count breach folds to per-kind baselines (append 4001 single-kind transitions → count collapses to ~2001, oldest entry is a BASELINE with correct `to`); age breach same; corrupt payload in store is skipped, not thrown.
- [ ] Step 2: Fail → implement `SignalLog` + `InMemoryTransitionStore`.
- [ ] Step 3: Robolectric test for `SqliteTransitionStore` CRUD.
- [ ] Step 4: Green; commit `feat(signals): budgeted process-wide signal log`.

### Task 5: `SignalHub` — snapshot, diff, baseline

**Files:**
- Create: `signals/SignalSource.kt` (interface only + `FakeSignalSource`)
- Create: `signals/SignalHub.kt`
- Test: `test/.../signals/SignalHubTest.kt`

**Interfaces (Produces):**
```kotlin
interface SignalSource { val kind: SignalKind; fun read(): SignalValue }
class FakeSignalSource(override val kind: SignalKind, var value: SignalValue) : SignalSource {
    override fun read() = value
}
class SignalHub(
    private val sources: List<SignalSource>,
    private val log: SignalLog,
    private val clock: BridgeClock,
) {
    @Synchronized fun snapshot(trigger: Trigger): SignalSnapshot
}
```
Behavior: read all sources (`try/catch` per source → `Unknown` on throw); first-ever observation of a kind logs a `BASELINE` transition (`from = Unknown`); subsequent changed values log a transition with the passed `trigger`; unchanged values log nothing.

- [ ] Step 1: Tests — first snapshot logs one BASELINE per source; unchanged second snapshot logs nothing; changed value logs one transition with correct from/to/trigger; throwing source yields `Unknown` in snapshot without exception.
- [ ] Step 2: Fail → implement → green.
- [ ] Step 3: Commit `feat(signals): lazy-polling signal hub with transition diffing`.

### Task 6: The 9 Android `SignalSource` implementations + broadcasts

**Files:**
- Create: `signals/AndroidSignalSources.kt` (all 9, each ~10 lines, `Build.VERSION.SDK_INT` gated)
- Create: `signals/SignalBroadcasts.kt` — registers runtime receivers (`DEVICE_IDLE_MODE_CHANGED`, `ACTION_APP_STANDBY_BUCKET_CHANGED` via UsageStatsManager listener where available, `ConnectivityManager.NetworkCallback`) calling `hub.snapshot(Trigger.BROADCAST)`.
- Test: `test/.../signals/AndroidSignalSourcesTest.kt` (Robolectric, `@Config(sdk = [26, 28, 31, 34])`)

**Interfaces (Consumes):** `SignalSource`, `SignalKind`, `SignalValue` from Tasks 3/5.

Sources → APIs (each returns `Unknown` below its floor or on exception):
| Kind | Read | Floor |
|---|---|---|
| PENDING_REASONS | `JobScheduler.getPendingJobReasons(jobId)` for the work's job slot (34+; history 36+) | 34 |
| STANDBY_BUCKET | `UsageStatsManager.getAppStandbyBucket()` → `Bucket` | 28 |
| BG_RESTRICTED | `ActivityManager.isBackgroundRestricted()` → `Flag` | 28 |
| DATA_SAVER | `ConnectivityManager.getRestrictBackgroundStatus() == RESTRICT_BACKGROUND_STATUS_ENABLED` → `Flag` | 26 (API exists 24) |
| DOZE | `PowerManager.isDeviceIdleMode()` / 33+ `isDeviceLightIdleMode()` → `Doze` | 26 |
| MAINTENANCE_WINDOW | derived: `Doze == DEEP && last idle broadcast flipped to non-idle within 10s` → `Flag`; hub-side, source returns `Flag(false)` default | 26 |
| NETWORK_VALIDATED | tracked `NetworkCallback` capability `NET_CAPABILITY_VALIDATED` → `Flag` | 26 |
| BATT_OPT_EXEMPT | `PowerManager.isIgnoringBatteryOptimizations(pkg)` → `Flag` | 26 |
| PROCESS_DEATH | latest `ApplicationExitInfo` (30+) → `Death` | 30 |

- [ ] Step 1: Robolectric tests: per source, shadowed value maps correctly at supporting SDK; at `sdk=26` the 28+/30+/34+ sources return `Unknown`.
- [ ] Step 2: Fail → implement sources + `SignalBroadcasts` (receiver registration in a `start(context)` fun; no test for registration beyond "doesn't throw" on Robolectric).
- [ ] Step 3: Green; commit `feat(signals): nine platform signal sources with API-level degradation`.

### Task 7: Verdict model

**Files:**
- Create: `diagnostics/Verdict.kt`
- Test: `test/.../diagnostics/VerdictRenderTest.kt`

**Interfaces (Produces):**
```kotlin
sealed interface Diagnosis {
    data class DeferredByStandbyBucket(val bucket: Int) : Diagnosis
    data class DeferredByDoze(val deep: Boolean) : Diagnosis
    object BackgroundRestricted : Diagnosis
    object DataSaverBlocked : Diagnosis
    data class AwaitingConstraint(val constraint: String) : Diagnosis   // "charging" | "unmetered"
    object AwaitingConformanceFallback : Diagnosis
    data class ThrottledAfterCrashes(val crashes: Int) : Diagnosis
    data class NotDispatched(val reason: String) : Diagnosis
    object Running : Diagnosis
    object Finished : Diagnosis
    data class Unexplained(val note: String) : Diagnosis
}
enum class Basis { REPORTED, INFERRED }
data class Evidence(val kind: SignalKind, val value: SignalValue, val at: Long, val trigger: Trigger)
data class Verdict(
    val workId: String, val state: RunState,
    val diagnosis: Diagnosis, val contributing: List<Diagnosis>,
    val evidence: List<Evidence>, val basis: Basis,
    val pendingSinceMs: Long?,          // Enqueued.at of current generation, null if terminal
) { fun render(now: Long): String }
```
`render` format (asserted in test):
```
PENDING 4h 12m — DeferredByStandbyBucket(RARE) [INFERRED]
  contributing: DeferredByDoze(deep)
  evidence:
    STANDBY_BUCKET  Bucket(40)  t=14:02  BROADCAST
```
Bucket ints render via names (10 ACTIVE, 20 WORKING_SET, 30 FREQUENT, 40 RARE, 45 RESTRICTED).

- [ ] Step 1: Render test (fixed clock) → fail → implement → green.
- [ ] Step 2: Commit `feat(diagnostics): typed verdict model with human render`.

### Task 8: `Diagnoser` — pure rule set

**Files:**
- Create: `diagnostics/Diagnoser.kt`
- Test: `test/.../diagnostics/DiagnoserTest.kt`

**Interfaces (Produces):**
```kotlin
object Diagnoser {
    fun diagnose(state: WorkState?, events: List<WorkEvent>,
                 snapshot: SignalSnapshot, slice: SignalSlice?): Verdict?
}
```
Returns `null` when `state == null`. Terminal/`RUNNING` states → `Running`/`Finished` diagnosis, empty contributing. Ordered matchers for ENQUEUED/DISPATCHED (first hit = primary, later hits = contributing):

1. `PENDING_REASONS` is `PendingReasons(nonEmpty)` → map platform constants (`PENDING_JOB_REASON_APP_STANDBY`→bucket, `PENDING_JOB_REASON_DEVICE_STATE`→doze, `PENDING_JOB_REASON_CONSTRAINT_CHARGING`→AwaitingConstraint("charging"), `PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY`→AwaitingConstraint("unmetered"), `PENDING_JOB_REASON_BACKGROUND_RESTRICTION`→BackgroundRestricted, else `NotDispatched("platform reason N")`); `basis = REPORTED`.
2. Journal: last event for current generation is `Stopped(STOP_REASON_RETRY)` and 2+ `Stopped`/`Died` in generation → `ThrottledAfterCrashes(n)`.
3. Journal: state is ENQUEUED with no `Dispatched` for current generation → `AwaitingConformanceFallback` if a conformance-downgrade marker event exists in events, else `NotDispatched("gateway rejected or not yet dispatched")`.
4. `BG_RESTRICTED == Flag(true)` → `BackgroundRestricted`.
5. `DATA_SAVER == Flag(true)` and `state.requiresUnmetered` → `DataSaverBlocked`.
6. `DOZE == Doze(DEEP|LIGHT)` → `DeferredByDoze`.
7. `STANDBY_BUCKET == Bucket(b)` with `b >= 30` (FREQUENT or worse) → `DeferredByStandbyBucket(b)`.
8. `state.requiresCharging || state.requiresUnmetered` → `AwaitingConstraint(...)` (charging first).
9. Nothing → `Unexplained("no explaining signal; basis inferred")`.

Rules 2–9: `basis = INFERRED`. Evidence = every snapshot entry (all 9 kinds) + slice transitions mapped to `Evidence`. `slice == null` → append `Evidence(kind=PROCESS_DEATH… )` no — instead: `contributing += NotDispatched("SignalHistoryUnavailable")` is wrong too; spec says an evidence *note*: represent as `Evidence(SignalKind.PROCESS_DEATH, SignalValue.Unknown, at=snapshot.at, trigger=DIAGNOSIS)` — no. Correct representation: add field `val notes: List<String>` to `Verdict`; `SignalHistoryUnavailable` goes there (adjust Task 7 model accordingly — field defaults to `emptyList()`).

- [ ] Step 1: Rule-table test, one test per numbered rule + precedence tests (REPORTED beats device inference; BackgroundRestricted beats Doze beats bucket) + `Running`/`Finished` + `Unexplained` + null-state → null + null-slice adds note.
- [ ] Step 2: Fail → implement → green.
- [ ] Step 3: Commit `feat(diagnostics): ordered-rule diagnoser producing verdicts`.

### Task 9: `Ledger` projection

**Files:**
- Create: `diagnostics/Ledger.kt`
- Test: `test/.../diagnostics/LedgerTest.kt`

**Interfaces (Produces):**
```kotlin
data class CostDelta(val cpuUserMs: Long, val cpuSystemMs: Long, val txBytes: Long, val rxBytes: Long)
sealed interface LedgerOutcome {
    data class Completed(val success: Boolean) : LedgerOutcome
    data class Stopped(val stopReason: Int) : LedgerOutcome
    data class Died(val exitReason: Int) : LedgerOutcome
    object Cancelled : LedgerOutcome
    object InFlight : LedgerOutcome
}
data class Run(
    val attempt: Int, val generation: Int,
    val dispatchedAt: Long?, val startedAt: Long?, val endedAt: Long?,
    val outcome: LedgerOutcome, val chunksExecuted: IntRange?,
    val cost: CostDelta?, val deviceContext: SignalSlice?,
)
data class Ledger(val workId: String, val runs: List<Run>)
object LedgerFold {
    fun fold(workId: String, events: List<WorkEvent>,
             sliceFor: (fromMs: Long, toMs: Long) -> SignalSlice?): Ledger
}
```
Fold walks events: `Dispatched` opens a run (or `Started` if dispatch record missing); `ChunkCompleted` extends `chunksExecuted`; `Stopped`/`Died`/`Finished`/`Cancelled` closes it (`endedAt = at`, cost from `Finished` fields when nonzero). `deviceContext = sliceFor(dispatchedAt ?: startedAt, endedAt ?: startedAt+0)`; open run → `InFlight`, `deviceContext = null`.

- [ ] Step 1: Tests — M1-style history (dispatch→start→chunks→die→dispatch→start→chunks→finish) folds to 2 runs with correct ranges/outcomes/cost; slice lambda receives run intervals; cancelled work; in-flight run.
- [ ] Step 2: Fail → implement → green.
- [ ] Step 3: Commit `feat(diagnostics): per-run ledger with device-context correlation`.

### Task 10: `BridgeReport` + facade wiring + REPORT broadcast

**Files:**
- Create: `diagnostics/BridgeReport.kt`
- Modify: `Bridge.kt` (new fields `signalHub`, `signalLog`; new methods; hub hooks), `BridgeConfigBuilder` (adds `signalSources: List<SignalSource>?`, `transitionStore: TransitionStore?`), `dispatch/Reconciler.kt` (snapshot on reconcile), `AndroidManifest.xml` (receiver)
- Create: `diagnostics/ReportReceiver.kt` (`io.github.iamjosephmj.bridge.REPORT` → `Log.i("BridgeReport", Bridge.report().render(now))`)
- Test: `test/.../BridgeDiagnosticsFacadeTest.kt`

**Interfaces (Produces):**
```kotlin
data class ReportLine(val workId: String, val runState: RunState, val diagnosis: Diagnosis?)
data class BridgeReport(val lines: List<ReportLine>, val conformanceMode: String,
                        val signalLogHealth: Pair<Int, Long?>) { fun render(now: Long): String }
// Bridge additions
fun whyPending(name: String): Verdict?
fun ledger(name: String): Ledger?
fun report(): BridgeReport
```
`whyPending`: `hub.snapshot(Trigger.DIAGNOSIS)` + events + `signalLog.slice(enqueuedAt, now)` (slice errors → null slice → note) → `Diagnoser.diagnose`. `enqueue()` and `Reconciler.reconcile()` call `hub.snapshot(Trigger.SCHEDULING_DECISION)` (wrapped in try/catch — diagnostics must never break scheduling).

- [ ] Step 1: Facade test with `FakeSignalSource`s + `InMemoryJournal` + `InMemoryTransitionStore`: enqueue charged-constraint work → `whyPending` yields `AwaitingConstraint("charging")`; demoted bucket source → `DeferredByStandbyBucket`; `ledger` returns folded runs; `report` lists both work items with diagnosis on the pending one; unknown name → nulls.
- [ ] Step 2: Fail → wire → green (full suite).
- [ ] Step 3: Commit `feat(diagnostics): whyPending/ledger/report facade + adb REPORT receiver`.

### Task 11: `bridge-sim` module

**Files:**
- Create: `bridge-sim/build.gradle.kts` (android library, depends on `:bridge-runtime`, JVM unit tests), register in `settings.gradle.kts`
- Create: `bridge-sim/src/main/java/io/github/iamjosephmj/bridge/sim/SimulatedDevice.kt` (device + DSL)
- Create: `bridge-sim/src/main/java/io/github/iamjosephmj/bridge/sim/SimulatedGateway.kt`
- Create: `bridge-sim/src/main/java/io/github/iamjosephmj/bridge/sim/Timeline.kt` (scripted signal values over time)
- Test: `bridge-sim/src/test/java/io/github/iamjosephmj/bridge/sim/ScenarioTest.kt`
- Create: `bridge-sim/README.md` (gating-model limitation statement)

**Interfaces (Produces):**
```kotlin
class Timeline { fun set(kind: SignalKind, value: SignalValue, atMs: Long) ; fun valueAt(kind: SignalKind, atMs: Long): SignalValue }
class SimulatedGateway(private val device: SimulatedDevice) : JobGateway {
    val parked: List<WorkItemPayload>
    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean  // parks
    override fun cancelAll()
    fun runnable(atMs: Long): List<WorkItemPayload>   // gating model below
}
class SimulatedDevice(registryBlock: BridgeConfigBuilder-like) {
    val clock: FakeClock; val journal: InMemoryJournal; val hub: SignalHub
    fun enqueue(request: WorkRequest): SimHandle
    fun advanceTo(ms: Long)          // steps in 60s ticks: update sources from timeline,
                                     // hub.snapshot(BROADCAST on change), run runnable payloads
                                     // via WorkRunner (kotlinx.coroutines runBlocking)
}
class SimHandle(name: String, device: SimulatedDevice) {
    fun verdictAt(ms: Long): Verdict           // device.advanceTo(ms) then whyPending path
    fun completedWithin(ms: Long): Boolean     // advance, check RunState.SUCCEEDED
    fun state(): WorkState?
}
fun simulate(block: SimScope.() -> Unit)       // SimScope wraps device + timeline DSL:
//   bucket(RARE at 0.h) / doze(deep from 1.h until 5.h, maintenanceWindows = every(2.h, 10.min))
//   dataSaver(on from 2.h) / bgRestricted(from ...)
```
**Gating model (document in README):** ordered — `BG_RESTRICTED` blocks all; `DOZE == DEEP` blocks unless inside a scripted maintenance window; `DATA_SAVER` blocks `requiresUnmetered` work; bucket floors delay first dispatch by WORKING_SET 2h / FREQUENT 8h / RARE 24h from enqueue; otherwise runnable. Charging/unmetered constraints gate on scripted `Flag` timelines (`charging(on from …)` maps to an extra `SignalKind`-independent gate held in the timeline under keys `"charging"`/`"unmetered"`).

- [ ] Step 1: Scenario tests (these are the deliverable): (a) doze-with-maintenance-windows — verdict at 3h is `DeferredByDoze`, `completedWithin(6.h)`; (b) bucket ladder RARE → verdict `DeferredByStandbyBucket`, not complete within 23h, completes by 25h; (c) data-saver + unmetered constraint → `DataSaverBlocked`, flips off → completes; (d) crash-backoff: worker that throws twice → `ThrottledAfterCrashes(2)`; (e) **stall mirror**: RARE bucket, verdict diagnosis type + basis INFERRED asserted — the exact assertion the device demo prints.
- [ ] Step 2: Fail → implement module → green.
- [ ] Step 3: Commit `feat(sim): deterministic device simulator with scenario DSL`.

### Task 12: Bench `stall` scenario

**Files:**
- Modify: `bench/src/main/java/io/github/iamjosephmj/bench/BenchReceiver.kt` (new command `stall_report` → for the current bench work name, collect WorkManager `WorkInfo.state` + Bridge `whyPending()?.render()` + structured fields into the JSON report), `bench/src/main/java/io/github/iamjosephmj/bench/Backend.kt` (expose whyPending for the bridge backend)
- Create: `bench/scripts/run-stall.sh`
- Test: manual/emulator; script includes cleanup trap.

**run-stall.sh outline (real script in implementation):**
```bash
# no prime() — instead:
adb shell am set-standby-bucket "$PKG" rare
trap 'adb shell am set-standby-bucket "$PKG" active; adb shell cmd appops reset "$PKG"' EXIT
# optional variant: adb shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND deny
# enqueue same corpus entry on both backends (existing enqueue broadcast)
# sleep OBSERVATION_WINDOW (default 120s)
# broadcast stall_report; adb pull the JSON; print two-column table
```
JSON gains `{"stall": {"workmanager": {"state": "ENQUEUED"}, "bridge": {"diagnosis": "...", "basis": "...", "render": "...", "evidence": [...]}, "device": {"model": ..., "api": ...}}}`.

- [ ] Step 1: Implement receiver command + script.
- [ ] Step 2: Verify `:bench:assembleDebug` compiles; run script against emulator if one is reachable, else record as pending-device.
- [ ] Step 3: Commit `feat(bench): stall scenario — WorkInfo vs whyPending side by side`.

### Task 13: Docs + merge

**Files:** Modify `README.md` (M2 section: features, simulator scenario results, stall table or "pending device run"), memory file note.

- [ ] Step 1: README M2 section; status line → "M2 (glass box v0.2)".
- [ ] Step 2: Full `./gradlew test` + `:bridge-sim:test` green.
- [ ] Step 3: Commit `docs: M2 features and acceptance status`; merge `m2-glass-box` → master (no-ff, like M1).

## Self-Review Notes

- Spec coverage: §2 components → Tasks 3–11; §3 hub/log → 4–6; §4 verdict/diagnoser → 7–8 (+`notes` field amendment); §5 ledger/report → 9–10; §6 simulator → 11 (module is `com.android.library` with JVM unit tests — deviation from "JVM-only module", recorded in Task 11 and to be noted in spec §6 at merge); §7 bench → 12; §8 testing → embedded per task.
- Type consistency: `EventJournal` (Task 2) consumed by Tasks 9–11; `SignalSlice` (3) by 4/8/9; `Verdict.notes` added in Task 8 amends Task 7's model — implementer of 7 includes `val notes: List<String> = emptyList()`.
- `RunOutcome` name collision: `exec.RunOutcome` exists; ledger uses `LedgerOutcome` to avoid it.
