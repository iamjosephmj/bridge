---
title: Diagnostics
nav_order: 6
---

# Diagnostics

![whyPending terminal: WorkManager answers RUNNING (stale); Bridge answers DeferredByDoze(deep) with basis REPORTED.](assets/whyPending.svg)

Three questions Bridge always answers: why isn't it running, what happened last time, and how is everything. All three are total functions — no nulls to defend against; unknown names get an `UnknownWork` verdict.

## whyPending — why isn't it running?

```kotlin
Bridge.whyPending("photo-backup").render(now)
// ENQUEUED 4h 12m — DeferredByStandbyBucket(RARE) [INFERRED]
//   contributing: DeferredByDoze(deep)
//   evidence:
//     STANDBY_BUCKET    Bucket(RARE)  t=...  BROADCAST
//     DOZE              Doze(DEEP)    t=...  BROADCAST
```

Verdicts carry a basis: `REPORTED` means the platform itself gave the reason (`getPendingJobReasons`); `INFERRED` means Bridge deduced it from signal state. Policy holds appear as `HeldByPolicy(why)` with the decision arithmetic spelled out; parked durable blocks as `DurableParked(...)`.

## ledger — what happened last time?

```kotlin
Bridge.ledger("photo-backup")
```

Per-attempt history: dispatch/start/end times, outcome (`Completed` / `Stopped` / `Died(exitReason)` / `Cancelled` / `InFlight`), the chunk range executed, the HealthStats cost delta, and the signal-log slice for the attempt window — so "died mid-run" becomes "died mid-run during deep Doze".

## report — how is everything?

```kotlin
Bridge.report().render(now)
// backup              ENQUEUED   DeferredByDoze(deep)
// nightly-sync        SUCCEEDED
// telemetry           ENQUEUED   HeldByPolicy(thread pressure MEDIUM (runnable 12 / 8 cores)
//                                — deferring importance 1 work)
// publish-post        ENQUEUED   DurableParked(delay until 1785561720000)
// conformance: MULTIPLEXED · signal log: 412 transitions / oldest 3d
```

## From adb, with no code changes

```
adb shell am broadcast -a io.github.iamjosephmj.bridge.REPORT \
    -n <pkg>/io.github.iamjosephmj.bridge.diagnostics.ReportReceiver
```
