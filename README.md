# Bridge

A reimplementation of Android background work that uses what system_server
actually offers: JobWorkItem-multiplexed dispatch, an append-only event journal,
chunk-exact resumption, death forensics via ApplicationExitInfo, and measured
per-run cost via HealthStats.

- Design: `docs/superpowers/specs/2026-07-27-bridge-design.md`
- M1 plan: `docs/superpowers/plans/2026-07-27-bridge-m1-core-scheduler.md`
- Benchmark vs WorkManager: `bench/README.md`

Status: M1 (core scheduler v0.1) — see plan for progress.

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
