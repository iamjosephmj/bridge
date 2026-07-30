---
title: Tier 0 — Glassbox
nav_order: 3
---

# Tier 0 — Glassbox: diagnostics for any app

![A scan sweeps across pending jobs and a verdict appears: 7 pending — DeferredByDoze(deep), basis REPORTED.](assets/tier0-glassbox.svg)

Glassbox requires no migration. WorkManager jobs are the app's own JobScheduler jobs, so the platform already reports on them (pending reasons, API 34+); two lines of integration expose that report.

```kotlin
// Application.onCreate
GlassBox.install(this)

// Anywhere, later:
Log.i(TAG, GlassBox.explain().render())
// device: DeferredByStandbyBucket(RARE), DeferredByDoze(deep)
// jobs:   3 pending — DeferredByDoze(deep) [REPORTED]
```

## The Explanation type

`GlassBox.explain()` returns a typed `Explanation` rather than a string:

- **Device-level causes** — standby bucket, Doze state, background restriction, Data Saver.
- **Per-job causes** — the platform's own pending-job reasons for each of the app's jobs.
- **Basis** — every cause is labeled `REPORTED` (the platform said so, via `getPendingJobReasons`) or `INFERRED` (deduced from signal state). The distinction is preserved so you know how much to trust each line.
- **Evidence** — the raw signal observations the verdict was folded from.

## The signal hub

Glassbox reads twelve platform signals into snapshots and persists their transitions: standby bucket, Doze, background restriction, Data Saver, pending-job reasons, network validation, battery-optimization exemption, maintenance windows, process deaths, thermal status, charge time, and thread pressure. Sampling is pull-based — signals are read when you ask for an explanation, not polled.

The same hub powers the full runtime's [diagnostics](diagnostics.html) when you adopt later tiers.
