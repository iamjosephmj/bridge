# Bridge Bench

Compares Bridge and WorkManager on the same simulated workload corpus.

## Run

    ./gradlew :bench:installDebug
    cd bench/scripts
    ./run-scenario.sh bridge baseline
    ./run-scenario.sh workmanager baseline
    ./compare-reports.py reports/report-bridge-*.json reports/report-workmanager-*.json

Scenarios: `baseline` (2 min undisturbed), `force-stop` (kill mid-run, relaunch),
`doze` (force deep idle mid-run for 60 s).

## Honesty rules

- The corpus and both backends live in this module; WorkManager timestamps are
  self-instrumented (it keeps no run history — that asymmetry is itself a result).
- Publish results whether or not they flatter Bridge.
- One report per (device, backend, scenario); metrics: time-to-first-start,
  time-to-complete, attempts, chunks replayed, incomplete items.
- Bridge's "no constraints" compiles to JobInfo NETWORK_TYPE_ANY while WorkManager's Constraints.NONE has no network requirement — near-equivalent but not identical (fairness disclosure).
