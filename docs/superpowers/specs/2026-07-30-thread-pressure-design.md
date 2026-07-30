# Thread-pressure admission — design

**Date:** 2026-07-30 · **Approved by:** Joseph (direction: global policy gate, importance-tiered, no per-request knob)

## Problem

Bridge decides *when* to dispatch, but never asks whether the process can afford the work
right now. Dispatching a LOW-importance backup while the process has more runnable threads
than cores adds scheduling latency and jank for whatever the user is actually doing.
"Friendly" here means: low-value background work yields when the process is already busy.

## Decision

A new platform signal plus a policy admission rule — **not** a per-request constraint and
**not** a JobInfo constraint (the platform has no such concept; this is app-local).

### 1. Signal: `SignalKind.THREAD_PRESSURE`

- Value: `SignalValue.Count(runnable)` — the number of threads of **this process** currently
  in state `R` (running/runnable), parsed from `/proc/self/task/*/stat`. Own-process `/proc`
  is readable on all supported API levels.
- **Runnable, not total**: a process can idle at 80 threads with 2 runnable. Total thread
  count is a false pressure signal; runnable count is actual CPU contention.
- Sampled **pull-based only** (Trigger.SCHEDULING_DECISION / DIAGNOSIS), like every other
  source — no polling, preserving the ~zero steady-state cost claim.
- Any parse/IO failure → `SignalValue.Unknown` → the rule does not fire (fail-open, the
  policy layer must never lose work).

### 2. Policy rule (PolicyEngine, before the quota rules) — tiered

The raw count classifies into a `PressureLevel` relative to cores (Joseph's direction,
2026-07-30: levels, not a single cliff):

| Level | Condition | Defers |
|---|---|---|
| LOW | `runnable ≤ cores` (normal parallelism) | nothing |
| MEDIUM | `runnable ≤ cores × 2` | MIN / LOW importance |
| HIGH | `runnable > cores × 2` | MIN / LOW / DEFAULT |

`Importance.HIGH` and deadline work (`state.deadlineMs > 0`) never wait — same exemption
shape as the thermal rule. Classification lives in the policy layer (judgment), the signal
stays the raw count (evidence).

Hold is short — `PRESSURE_RECHECK_MS = 60s` — because thread pressure is transient,
unlike thermal (15 m) or quota (30 m). The why-string journals level and arithmetic:
`"thread pressure MEDIUM (runnable 12 / 8 cores) — deferring importance 1 work"`,
surfaced by `whyPending()` as `HeldByPolicy(...)`. No Shed: pressure passes; work waits.

`cpuCores` becomes a `PolicyEngine` constructor parameter
(default `Runtime.getRuntime().availableProcessors()`); the simulator pins it (8) for
deterministic tests. No Android imports enter PolicyEngine.

### 3. Simulator

- `Timeline.defaultFor`: `THREAD_PRESSURE → Unknown` (rule off unless scripted).
- DSL: `threadPressure(runnable: Int, fromMs)` alongside `thermal(...)`.

## Not doing (YAGNI)

- Per-request `threadPressure(max=…)` DSL knob — arbitrary numbers users can't reason
  about; importance already expresses the intent.
- Load-average / CPU-percent signals — overlaps THERMAL, restricted `/proc` surface.
- Polling or broadcast wiring — pull-only.

## Testing

- `PolicyEngineTest`: level classification boundaries; MEDIUM holds MIN/LOW and spares
  DEFAULT; HIGH holds DEFAULT and spares Importance.HIGH; LOW/Unknown admits; deadline
  work exempt; hold is 60 s recheck.
- Source parser extracted as `parseRunnableThreads(taskDir: File)` and unit-tested against
  a fake `/proc` tree (including malformed stat lines → Unknown).
- Sim scenario: LOW work under scripted pressure defers, completes after pressure clears;
  verdict shows `HeldByPolicy`.

## Touched files

`SignalModel.kt` (enum), `AndroidSignalSources.kt` (+`ThreadPressureSource`),
`PolicyEngine.kt` (rule + `cpuCores` param), `SimulatedDevice.kt` (pin cores),
`Timeline.kt` (default), `Simulate.kt` (DSL), tests, README signal count (9 → 12).
