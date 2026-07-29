# Bridge M3 — Judgment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pure policy engine (admission, quota budgeting, deadline escalation) + Doze
burst-drain, every decision journaled and surfaced via `whyPending()`.

**Architecture / interfaces:** exactly spec §2–§4
(`docs/superpowers/specs/2026-07-29-bridge-m3-judgment-design.md`) — the spec's component
list, decision semantics, and rule table are normative; this plan sequences them.

**Tech stack:** unchanged from M2. Branch `m3-judgment`. Commit style unchanged.

## Global Constraints

- Policy failures fail open to `Admit(default)` — never lose work.
- `PolicyEngine` has no `android.*` imports (sim runs it).
- New `Enqueued.deadlineMs` and new `WorkEvent.PolicyDecision` must be
  serialization-backward-compatible (defaults; additive @SerialName).

### Task 1: Model plumbing
- [ ] `WorkEvent.PolicyDecision(workId, at, decision, why)` + fold no-op + codec round-trip test.
- [ ] `WorkRequest.mustCompleteBy(atMs)` builder + `Enqueued.deadlineMs = 0L` default +
      `WorkState.deadlineMs` folded through; Bridge.enqueue passes it. Tests.
- [ ] Commit `feat(store): PolicyDecision event, work deadlines`.

### Task 2: New signals + host tier
- [ ] `SignalKind.THERMAL` (`SignalValue.Count(status)` — add Count case) and
      `SignalKind.CHARGE_TIME` (`Count(minutesRemaining)`), sources with 29+/28+ floors,
      Robolectric degradation tests; Timeline defaults (`Unknown`).
- [ ] `HostJobClass.EXPEDITED(710_004)`; `JobPlanCompiler` maps `setExpedited(true)` on 31+,
      falls back to DEFAULT class below (journaling handled by dispatcher). Robolectric test.
- [ ] Commit `feat(signals,dispatch): thermal + charge-time signals, expedited host tier`.

### Task 3: PolicyEngine (pure) — spec §3 rules 1–5
- [ ] `Decision` sealed type; `PolicyEngine.decide` rule table implemented in spec order;
      unit tests: thermal hold, quota hold arithmetic + chunked bypass + unknown-admit,
      shed matrix, escalation fractions (incl. sub-31 skip via `apiLevel` param), fail-open
      (throwing snapshot access → Admit default).
- [ ] Commit `feat(policy): pure policy engine — admission, budgeting, escalation`.

### Task 4: Dispatcher + alarms + diagnoser wiring
- [ ] `AlarmGateway` interface, `SystemAlarmGateway` (`setAndAllowWhileIdle` +
      `BridgeAlarmReceiver` manifest receiver → `Bridge.reconcileIfInitialized()`),
      `FakeAlarmGateway`.
- [ ] Dispatcher: consult engine; journal `PolicyDecision` on hold/shed/escalate/skip;
      enqueue with decided tier; ALARM tier also schedules the alarm. Unit tests with
      FakeJobGateway + FakeAlarmGateway.
- [ ] `Diagnosis.HeldByPolicy(why)`; diagnoser rule-2: last generation event is
      `PolicyDecision(hold|shed)` → primary. Tests.
- [ ] `SignalBroadcasts`: doze-exit / maintenance-window → `Bridge.reconcileIfInitialized()`.
- [ ] Commit `feat(dispatch): policy-driven dispatch, alarm tier, HeldByPolicy verdicts`.

### Task 5: Sim + scenarios + docs
- [ ] `SimulatedDevice`: dispatcher constructed with engine + FakeAlarmGateway; each tick
      `dispatchAll()` + fire due alarms; SimScope `thermal(status, fromMs)`.
- [ ] Scenarios (a)–(e) from spec §5. All green.
- [ ] README M3 section; merge `m3-judgment` → master no-ff.
