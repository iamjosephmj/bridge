---
title: Bridge vs WorkManager
nav_order: 9
---

# Bridge vs WorkManager

A direct capability comparison, including the rows WorkManager currently wins. Rows marked *device-verified* correspond to the measured results in [Results](RESULTS.html).

| Capability | Bridge | WorkManager | Verification |
|---|---|---|---|
| Resume interrupted work at the exact chunk | Yes — journaled chunk ledger | No — restarts from zero | Device-verified (1 vs 20 chunks replayed) |
| Explain stalled work | Typed verdict with platform evidence (`[REPORTED]`) | State query only; can report a stale `RUNNING` | Device-verified (stall scenario) |
| Durable coroutines surviving process death | Yes — deterministic replay; journaled steps, timers, awaits | No | Device-verified (force-stop mid-`delay`) |
| Chains resume at the failed link | Yes — links compile to chunks | No — the chain restarts | Instrumented suite |
| Per-run history with death forensics | Yes — `ledger()` with `ApplicationExitInfo`, device context, cost | No run history kept | |
| Measured per-run cost | HealthStats deltas; flags expensive work declared unimportant | No | |
| Deadline escalation | `mustCompleteBy`: DEFAULT → EXPEDITED → while-idle alarm, each step journaled | Expedited flag only | |
| Importance-aware quota budgeting | Explicit, journaled shed/hold decisions in demoted buckets | Silent platform deferral | |
| Thread-pressure admission | Runnable threads vs cores classify LOW / MEDIUM / HIGH; per-request `maxThreadPressure` | No | Device-verified |
| Doze strategy | Maintenance-window burst-drain, doze-exit dispatch, rhythm prediction | Platform default | Simulated scenarios |
| Constraints (charging, network, battery/storage-not-low, device-idle, content-URI triggers) | Full surface | Full surface | |
| Periodic work and initial delay | Yes — journaled generations, exact-path latency | Yes | |
| `Data` payloads | Yes — journaled input and per-attempt output, visible in `ledger()` | Yes — latest output only | Device-verified (round trip) |
| Tags (query and cancel by tag) | Yes | Yes | Device-verified |
| Work observers | `Flow` (`stateFlow` / `eventsFlow`; LiveData via `asLiveData()`) | LiveData / Flow | Device-verified (flow-observed completion) |
| Multi-branch chains | Yes — prerequisite DAG: `after(...)` / `WorkContinuation.combine`, branch outputs merged into the join | Yes | Device-verified (DAG join) |
| Ecosystem (Hilt integration, documentation, community) | New | Extensive | |

## Measured results

Measured on physical hardware. Identical workloads on both backends.

| Scenario | Bridge | WorkManager |
|---|---|---|
| Force-stop mid-upload (200 MB / 40 chunks) | Resumed at the in-flight chunk — **1** chunk replayed | Restarted from chunk 0 — **20** chunks replayed |
| Time to complete after kill | **61,350 ms** | 72,346 ms |
| Stall diagnosis under forced idle | `DeferredByDoze(deep)` `[REPORTED]` | `RUNNING` — stale; the job had already been stopped |
| Durable coroutine force-stopped mid-`delay(20s)` | **SUCCEEDED**, each step exactly once | Not supported |

![Force-stop replay panel.](assets/panel-forcestop.svg)

![Stall verdict panel.](assets/panel-stall.svg)

Raw tables: [Results](RESULTS.html).
