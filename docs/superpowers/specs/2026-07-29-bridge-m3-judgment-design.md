# Bridge M3 — Judgment (v0.3) design

Parent design: `2026-07-27-bridge-design.md` (§4.4 L4, §8 phasing M3).
Status: approved design (autonomous run 2026-07-29; scoping decisions recorded here),
pre-implementation.

## 1. Goal and scope

M3 gives Bridge judgment: a pure policy engine deciding *whether, when, and how urgently*
to dispatch — every decision journaled and surfaced through `whyPending()`. Per §8, M3
covers **admission control, deadline escalation, quota budgeting, and Doze window
strategy**. Rhythm model and cost flagging are M4 (§4.4 items 5–6).

Scoping decisions (made autonomously, following the parent design's grain):

- **Two new signals**, behind the existing `SignalSource` seam: `THERMAL` (29+:
  `getCurrentThermalStatus`; below → `Unknown`) and `CHARGE_TIME` (28+:
  `computeChargeTimeRemaining`). The M2 "explanation-driven subset" grows to 11 —
  exactly the sources admission control consumes, still not the full 17.
- **Escalation ladder caps at inexact alarms.** Tiers: DEFERRABLE → DEFAULT →
  EXPEDITED (31+, `setExpedited`; skipped+journaled below 31) → ALARM
  (`AlarmManager.setAndAllowWhileIdle`). The parent doc's final
  `setExactAndAllowWhileIdle` tier needs the `SCHEDULE_EXACT_ALARM` special permission
  and its Play-policy story; deferred past M3, recorded as a skip in the ladder.
- **Quota model is a labeled heuristic**, not a platform readout: JobScheduler exposes
  no quota API, so admission uses the documented ~10-minute-per-window bucket allowance
  (WORKING_SET and below) against a duration estimate from the work's own ledger
  history (mean of past run durations; falls back to `estimatedUpBytes` at 1 MB/s; no
  history + no estimate → admit). Every hold names its arithmetic in `why`.
- **Doze strategy = burst-drain + freshness dispatch.** Maintenance-window and
  doze-exit broadcasts trigger `dispatchAll()`. Window-cadence *prediction* belongs to
  the M4 rhythm model.

## 2. Components

```
bridge-runtime/
  policy/
    PolicyEngine.kt    pure: (WorkState, events, SignalSnapshot, now) -> Decision
    Decision.kt        Admit(tier) | Hold(untilMs, why) | Shed(why)
  dispatch/
    HostJobClass.kt    + EXPEDITED(710_004) tier; JobPlanCompiler maps setExpedited on 31+
    AlarmGateway.kt    interface + SystemAlarmGateway (setAndAllowWhileIdle) + BridgeAlarmReceiver
    Dispatcher.kt      consults PolicyEngine before gateway.enqueue; journals PolicyDecision
  store/
    WorkEvent.kt       + PolicyDecision(workId, at, decision, why) — folds to no state change
  signals/
    AndroidSignalSources.kt  + ThermalSource, ChargeTimeSource; SignalKind += THERMAL, CHARGE_TIME
  diagnostics/
    Diagnoser.kt       rule 2 gains: last event PolicyDecision(hold/shed) -> HeldByPolicy(why)
    Verdict.kt         Diagnosis += HeldByPolicy(why: String)
bridge-sim/            ticks call dispatcher.dispatchAll() so held work re-evaluates;
                       FakeAlarmGateway fires due alarms; scenarios for hold/escalate/shed
```

## 3. Decision semantics

`PolicyEngine.decide(state, events, snapshot, now): Decision`, pure, ordered:

1. **Thermal admission.** `THERMAL >= SEVERE(3)` → `Hold(now + 15min, "thermal SEVERE")`.
2. **Quota admission.** Bucket ≥ WORKING_SET(20): estimate run duration (ledger mean →
   bytes/1MBps → admit-if-unknown). Estimate > 10min quota window and work not chunked →
   `Hold(bucketFloorReset, "estimated Xm exceeds ~10m window")`. Chunked work always
   admits (chunks fit windows — that is what chunks are for).
3. **Quota budgeting.** Bucket ≥ FREQUENT(30) and importance ≤ LOW(1) →
   `Shed("bucket FREQUENT+, importance LOW — spend quota on higher-value work first")`.
   Shed work stays ENQUEUED and re-evaluates on the next scheduling decision; it is a
   journaled deprioritization, not a cancellation.
4. **Deadline escalation.** `deadlineMs` set (new `WorkRequest.mustCompleteBy(atMs)`,
   carried on `Enqueued`): remaining = deadline − now vs total = deadline − enqueuedAt.
   remaining/total > 0.5 → `Admit(normal tier)`; > 0.25 → `Admit(DEFAULT)`;
   > 0.10 → `Admit(EXPEDITED)` (31+, else DEFAULT + journaled skip);
   ≤ 0.10 → `Admit(ALARM)` — dispatcher schedules `setAndAllowWhileIdle(deadline − 5min)`
   via `AlarmGateway`, journaled as `escalate:ALARM`.
5. Otherwise `Admit(HostJobClass.forWork(state))`.

Dispatcher behavior: `Admit(tier)` → enqueue with tier, journal
`PolicyDecision("admit:$tier", why)` only when the tier differs from the default;
`Hold`/`Shed` → journal `PolicyDecision`, do NOT enqueue; work re-evaluates on every
subsequent scheduling decision (enqueue/reconcile/broadcast poke). `whyPending()` on
held/shed work reports `HeldByPolicy(why)` as primary (rule-2 slot: Bridge's own held
decisions outrank device inference; platform-reported reasons still outrank it).

Policy failures fail open: any exception inside `decide` → `Admit(default tier)` — the
policy layer must never lose work.

## 4. Doze strategy

`SignalBroadcasts` already snapshots on idle transitions. M3 adds: when deep idle exits
(or a maintenance window opens), invoke `Bridge.reconcileIfInitialized()` → `dispatchAll()`
→ every ENQUEUED item re-runs policy and dispatches — burst-drain at window open,
freshness dispatch at doze exit. No new persistent state.

## 5. Testing

- **Unit:** PolicyEngine rule table (thermal hold, quota hold arithmetic incl. chunked
  bypass, shed matrix bucket×importance, escalation fractions incl. sub-31 skip, fail-open).
- **Sim scenarios:** (a) long un-chunked work in WORKING_SET holds with quota arithmetic
  in the verdict, then admits when bucket returns ACTIVE; (b) chunked equivalent admits
  immediately; (c) LOW-importance work sheds in FREQUENT while DEFAULT work runs, runs
  after bucket recovers; (d) deadline work escalates DEFAULT→EXPEDITED→ALARM as the
  deadline nears, alarm fires, work completes; (e) burst-drain: three items held by deep
  doze all complete inside the first maintenance window.
- **Robolectric:** the two new sources' API-floor degradation; JobPlanCompiler EXPEDITED
  mapping on 31+ vs skip below.

## 6. Out of scope

Rhythm/cadence prediction, cost flagging (M4); exact alarms + SCHEDULE_EXACT_ALARM;
thermal-headroom *forecasting* (M3 uses current status only); admission for
charge-time-remaining (signal lands, arithmetic waits for M4 rhythm windows — recorded
so the CHARGE_TIME source is not dead code: it feeds verdict evidence today).
