---
title: Internals
nav_order: 11
---

# Bridge internals

How `bridge-runtime` is built. Nothing here is needed to *use* Bridge — the [README](../README.md) covers usage; this is the implementation reference.

## Layer stack

Inside `bridge-runtime`, layers stack strictly — each depends only on those below it:

| Layer | Components | Responsibility |
|---|---|---|
| **durable** | DurableScope: step / delay / await | Deterministic replay of suspend blocks |
| **diagnostics** | Diagnoser · Verdict · Ledger · BridgeReport | Fold journal + signals into answers |
| **policy** | PolicyEngine | Admission, quota, thread pressure, deadline escalation, doze strategy, rhythm |
| **signals** | SignalHub | 12 platform signals, budgeted transition log |
| **dispatch** | Dispatcher · JobGateway (multiplexed / 1:1) · AlarmGateway · Reconciler | Get work onto the platform and back |
| **journal** | Append-only WorkEvent log · SQLite · KvStore | Durable ground truth |

## Event-sourced journal

Every state change is an appended `WorkEvent` (`Enqueued`, `ChunkCompleted`, `StepCompleted`, `PolicyDecision`, …); current state is a fold over events. Nothing is ever updated in place, so "what happened" is always answerable. → [`bridge-runtime/.../store/`](../bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/store/)

## Deterministic replay

After death, a chunked worker resumes at `nextChunk`; a durable block re-executes from the top with completed `step()`s returning journaled results instantly, reattaching at the first live step, timer, or await. → [`api/Durable.kt`](../bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/api/Durable.kt)

## Policy engine

Pure functions from (journal, signals, request) to decisions: thermal holds, bucket-quota admission, thread-pressure admission (runnable threads vs cores classify LOW / MEDIUM / HIGH; MEDIUM defers MIN/LOW-importance work, HIGH also defers DEFAULT — `Importance.HIGH` and deadline work never wait, and `maxThreadPressure(level)` overrides the mapping per request), deadline escalation, doze burst-drain. Every decision is journaled and surfaced by `whyPending()` as `HeldByPolicy(why)` — nothing is ever silently deferred. → [`policy/`](../bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/policy/)

## Signal hub

Twelve platform signals — standby bucket, Doze, background restriction, Data Saver, pending-job reasons, network validation, battery-opt exemption, maintenance windows, process deaths, thermal status, charge time, thread pressure — read into snapshots and persisted transitions; the diagnoser folds them into verdicts. Sampling is pull-based (baseline, broadcast, scheduling decision, diagnosis) — no polling. → [`bridge-glassbox/.../signals/`](../bridge-glassbox/src/main/java/io/github/iamjosephmj/bridge/signals/)

## KvStore

In-memory-first reads over a `kv` table in `bridge.db`: lock-free `ConcurrentHashMap` reads, DB-before-memory writes — hot-path metadata never touches disk on read.

## Related documents

- Usage and adoption tiers: [README](../README.md)
- Measured results, citable tables: [RESULTS.md](RESULTS.md)
- Migration guide: [MIGRATION.md](MIGRATION.md)
- Design specs: [`docs/superpowers/specs/`](superpowers/specs/)
