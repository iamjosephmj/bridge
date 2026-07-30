---
title: Introduction
nav_order: 1
---

# Bridge

Bridge is an Android background-work runtime built directly on the platform's own primitives: an append-only event journal, JobWorkItem-multiplexed dispatch, death forensics via `ApplicationExitInfo`, and measured cost via `HealthStats`.

Two properties define it:

1. **Work interrupted by process death resumes where it stopped.** A 40-chunk upload force-stopped at chunk 6 continues at chunk 6, not chunk 0. A durable coroutine killed mid-`delay` wakes when the timer fires and replays to exactly the suspension point.
2. **Every deferred or held job can explain why.** `whyPending()` returns a typed verdict backed by the platform's own reporting (`getPendingJobReasons`, standby buckets, Doze state), never a stale `RUNNING`.

![Force-stop demo: both schedulers are killed at chunk 6. Bridge resumes at chunk 6; WorkManager starts over from chunk 0.](assets/killdemo.svg)

## How this book is organized

| Chapter | Covers |
|---|---|
| [Getting started](getting-started.html) | Modules, initialization, the first enqueue |
| [Tier 0 — Glassbox](glassbox.html) | Diagnostics for any app, no migration |
| [Tier 1 — Compat](compat.html) | The `androidx.work`-shaped façade |
| [Tier 2 — Runtime](runtime.html) | The constraint DSL, chunked work, periodic work |
| [Diagnostics](diagnostics.html) | `whyPending`, `ledger`, `report`, adb access |
| [Tier 3 — Durable coroutines](durable.html) | Suspend blocks that survive process death |
| [Tier 4 — Simulator](simulator.html) | Testing device regimes on the JVM |
| [Bridge vs WorkManager](comparison.html) | Capability comparison and measured results |
| [Migration](MIGRATION.html) | Moving from WorkManager in three reversible stages |
| [Internals](INTERNALS.html) | How the runtime is built |
| [Results](RESULTS.html) | Raw measured numbers, citable |

## Adoption model

Bridge is adopted incrementally. Each tier is useful on its own, and every step is reversible.

| Tier | Module | Provides | Adoption cost |
|---|---|---|---|
| 0 | `bridge-glassbox` | Diagnostics for any app's existing jobs | Two lines; nothing to migrate |
| 1 | `bridge-compat` | `androidx.work`-shaped façade; chains resume at the failed link | An import change |
| 2 | `bridge-runtime` | Full engine: constraints, chunks, deadlines, periodic, diagnostics | Native API adoption |
| 3 | `bridge-runtime` | Durable coroutines surviving process death | Builds on Tier 2 |
| 4 | `bridge-sim` | JVM device regimes in milliseconds, for tests | Test-only dependency |
