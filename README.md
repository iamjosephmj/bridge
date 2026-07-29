# Bridge

A reimplementation of Android background work that uses what system_server
actually offers: JobWorkItem-multiplexed dispatch, an append-only event journal,
chunk-exact resumption, death forensics via ApplicationExitInfo, and measured
per-run cost via HealthStats.

- Design: `docs/superpowers/specs/2026-07-27-bridge-design.md`
- M1 plan: `docs/superpowers/plans/2026-07-27-bridge-m1-core-scheduler.md`
- M2 design: `docs/superpowers/specs/2026-07-29-bridge-m2-glass-box-design.md`
- Benchmark vs WorkManager: `bench/README.md`
- Simulator: `bridge-sim/README.md`

Status: M2 (glass box v0.2).

## M2 — the glass box

Bridge explains stalled work. A process-wide signal hub reads nine platform
signals (standby bucket, Doze, background restriction, Data Saver, pending-job
reasons, network validation, battery-opt exemption, maintenance windows,
process deaths), persists transitions to a budgeted signal log, and a pure
rule-set diagnoser folds them into typed verdicts:

```kotlin
Bridge.whyPending("photo-backup")?.render(now)
// ENQUEUED 4h 12m — DeferredByStandbyBucket(RARE) [INFERRED]
//   contributing: DeferredByDoze(deep)
//   evidence: ...
Bridge.ledger("photo-backup")   // per-run history with device context + death forensics
Bridge.report()                 // app-wide one-liner per work item (also via adb:
                                // am broadcast -a io.github.iamjosephmj.bridge.REPORT)
```

`bridge-sim` scripts signal timelines and a fake clock over the real
journal/dispatcher/runner/diagnoser: multi-day device regimes assert in
milliseconds on the JVM (7 canonical scenarios ship as tests).

**M2 acceptance:** the simulator stall mirror (RARE bucket →
`DeferredByStandbyBucket(RARE) [INFERRED]` while WorkManager reports
`ENQUEUED`) is green in CI. The on-device `bench/scripts/run-stall.sh` run is
**pending** — the harness device blocked USB install during the automated run;
re-run when a device with install confirmation is available.

## M1 acceptance run (Pixel 6 Pro, API 36, 2026-07-28)

`force-stop` scenario, `large_chunked` (200MB / 40 chunks), process killed
mid-run and relaunched:

| metric | bridge | workmanager |
|---|---|---|
| attempts | 2 | 2 |
| chunksReplayed | **1** | **20** |
| timeToCompleteMs | 61,350 | 72,346 |

Bridge re-executed only the single chunk that was mid-flight at the moment of
the kill (the chunk-execution ledger records at chunk start, so an interrupted
chunk counts as executed); every completed chunk's result survived.
WorkManager, with no resume primitive, restarted the job from chunk 0.
Raw reports: `bench/scripts/reports/`.
