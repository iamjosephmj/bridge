---
title: Tier 4 — Simulator
nav_order: 8
---

# Tier 4 — Simulator

![A small device with a fast-forwarding clock: multi-day Doze regimes asserted in milliseconds on the JVM.](assets/tier4-sim.svg)

Multi-day device regimes asserted in milliseconds of JUnit. `bridge-sim` scripts signal timelines and a fake clock over the real journal, dispatcher, runner, and diagnoser — the production classes, not mocks.

```kotlin
simulate {
    worker("upload") { UploadWorker() }
    bucket(Buckets.RARE)
    doze(fromMs = 1.h, untilMs = 5.h, maintenanceEveryMs = 2.h)
    threadPressure(runnable = 12, fromMs = 2.h)   // MEDIUM on the sim's 8 cores
    val work = enqueue(workRequest("sync", "upload"))
    assertThat(work.verdictAt(3.h).diagnosis).isInstanceOf(Diagnosis.DeferredByDoze::class.java)
    assertThat(work.completedWithin(26.h)).isTrue()
}
```

## Scriptable regime

| DSL | Scripts |
|---|---|
| `bucket(...)` | Standby bucket over time |
| `doze(...)` | Doze windows with maintenance cadence |
| `thermal(status)` | PowerManager thermal status level |
| `threadPressure(runnable)` | Process runnable-thread count (the sim pins 8 cores) |
| `charging(...)`, `batteryLow(...)`, `storageLow(...)`, `dataSaver(...)`, `bgRestricted(...)` | The remaining constraint gates |
| `unmetered(...)`, `contentChanged(uri, atMs)` | Network class over time; content-URI trigger firings |
| `signal(kind, value, fromMs)` | Escape hatch: script any raw signal directly |
| `restartProcess()`, `advanceTo(ms)` | Simulated process death + relaunch; explicit clock control |
| `launch(name) { ... }` / `startDurable(...)` | Durable coroutines under the scripted regime |

Thirty-one scenario tests across six suites ship with the module — constraint gates, policy escalation, WorkManager-parity chains, timing, and the durable signature test (death at +30 min, deep Doze mid-`delay(2h)`), including the stall mirror of the device result.

## Scope

The simulator's scope is explicit: a logic assertion under a scripted regime, not a device guarantee. The gating model makes no attempt to reproduce OEM heuristics. Device truth comes from the instrumented suite and the benchmark harness in [`bench/`](https://github.com/iamjosephmj/bridge/tree/master/bench).
