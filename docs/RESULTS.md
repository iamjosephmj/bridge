---
title: Results
nav_order: 12
---

# Raw device-run numbers, citable

The README renders these as designed panels; this file keeps the raw markdown tables verbatim for citing, diffing, and copy-pasting. All results measured on physical hardware, same workload corpus on both backends. Raw reports: [`bench/scripts/reports/`](../bench/scripts/reports/).

## 1 vs 20 — force-stop replay

`force-stop` scenario, `large_chunked` (200 MB / 40 chunks), process killed mid-run and relaunched:

| metric | bridge | workmanager |
|---|---|---|
| attempts | 2 | 2 |
| chunks replayed | **1** | **20** |
| time to complete | 61,350 ms | 72,346 ms |

<sub>Bridge re-executed only the chunk in flight at the kill; every completed chunk's result survived. WorkManager, with no resume primitive, restarted from chunk 0.</sub>

## The stall verdict

`stall` scenario: unplugged, demoted to RARE, deep Doze forced — then both APIs asked "why?":

| item | workmanager says | bridge says |
|---|---|---|
| ping-none | SUCCEEDED | `Finished [INFERRED]` |
| ping-unmetered_charging | **RUNNING** | `DeferredByDoze(deep=true) [REPORTED]` |
| medium_sync-none | **RUNNING** | `Finished [INFERRED]` |
| medium_sync-unmetered_charging | SUCCEEDED | `DeferredByDoze(deep=true) [REPORTED]` |
| large_chunked-none | **RUNNING** | `DeferredByDoze(deep=true) [REPORTED]` |
| large_chunked-unmetered_charging | **RUNNING** | `DeferredByDoze(deep=true) [REPORTED]` |

<sub>WorkManager reports <b>RUNNING</b> for jobs the forced idle has stopped — a stale answer, not just an empty one (in <code>medium_sync-none</code> it reports RUNNING for work Bridge's copy had already finished). Bridge's verdicts for still-pending items carry <code>basis=REPORTED</code>: they come from <code>getPendingJobReasons</code>, the platform's own explanation, not inference; completed items answer <code>Finished</code>.</sub>

## Durable acceptance — force-stop mid-delay

Durable block force-stopped mid-`delay(20s)`, relaunched after the timer elapsed while the process was dead:

| metric | value |
|---|---|
| state | SUCCEEDED |
| firstStepExecutions | **1** (ran before the kill, replayed after) |
| secondStepExecutions | **1** (ran only after relaunch) |
| step events journaled | 2 |
| parks | 1 |

<sub>Step counters persist in on-device storage precisely because process memory does not — that is the scenario. The simulator's signature demo additionally survives death at +30 min and deep Doze 1–3 h mid-<code>delay(2h)</code>.</sub>

## Bridge vs WorkManager

| capability | Bridge | WorkManager | |
|---|---|---|---|
| Resume interrupted work at the exact chunk | Yes — chunk ledger | No — restarts from zero | **device-verified** (1 vs 20 chunks replayed) |
| Explain stalled work | Typed verdict + platform evidence (`[REPORTED]`) | `ENQUEUED` (and can report stale `RUNNING`) | **device-verified** (stall scenario) |
| Durable coroutines (suspend blocks surviving death) | Yes — deterministic replay, journaled steps/timers/awaits | No | **device-verified** (force-stop mid-delay) |
| Chains resume at the failed link | Yes — links compile to chunks | No — chain restarts | verified in instrumented suite |
| Per-run history with death forensics | `ledger()`: `Died(exitReason)` attributed via `ApplicationExitInfo`, device context, cost | None (keeps no run history) | |
| Measured per-run cost | HealthStats deltas; flags "expensive work declared unimportant" | None | |
| Deadline escalation | `mustCompleteBy`: DEFAULT → EXPEDITED → while-idle alarm, each step journaled | Expedited flag only | |
| Importance-aware quota budgeting | LOW/MIN sheds explicitly in demoted buckets, never silently | Silent platform deferral | |
| Doze strategy | Maintenance-window burst-drain, doze-exit freshness dispatch, rhythm prediction | Platform default | |
| Constraints | charging, network/unmetered, battery/storage-not-low, device-idle, content-URI triggers | same | |
| Periodic + initial delay | Yes (journaled generations / exact-path latency) | Yes | |
| `Data` payloads | Yes — journaled input + per-attempt output | Yes — latest output only | **device-verified** (round trip) |
| Tags (query / cancel by tag) | Yes | Yes | **device-verified** |
| Work observers | Flow (`stateFlow` / `eventsFlow`); LiveData via `asLiveData()` | LiveData / Flow | **device-verified** |
| Multi-branch chains | Yes — prerequisite DAG (`after(...)` / `combine`) | Yes | **device-verified** (DAG join) |
| **Where WorkManager still wins** | | | |
| OEM maturity | one device of hardware evidence | a decade across every OEM's process killer | honest gap |
| Ecosystem (Hilt integration, docs, Stack Overflow mass) | new | vast | |
