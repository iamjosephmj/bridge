# bridge-sim

Deterministic device simulator for Bridge. Scripts signal timelines, the clock, and a
simulated `JobGateway`, then runs the *real* journal, dispatcher, work runner, signal hub,
and diagnoser on the JVM. Scenarios that span days execute in milliseconds.

```kotlin
simulate {
    worker("upload") { UploadWorker() }
    bucket(Buckets.RARE)
    doze(fromMs = 1.h, untilMs = 5.h, maintenanceEveryMs = 2.h)
    val work = enqueue(workRequest("sync", "upload"))
    assertThat(work.verdictAt(3.h).diagnosis).isInstanceOf(Diagnosis.DeferredByDoze::class.java)
    assertThat(work.completedWithin(26.h)).isTrue()
}
```

## The gating model is deliberately simple

`SimulatedGateway` applies scripted gates in a fixed order:

1. background-restricted blocks everything
2. deep Doze blocks except during scripted maintenance windows
3. Data Saver (and no unmetered network) blocks unmetered-constrained work
4. charging constraint gates on the scripted charging flag
5. standby buckets delay the *first* dispatch by the platform's documented deferral
   floors — WORKING_SET ~2h, FREQUENT ~8h, RARE ~24h
6. a retry-stop parks the item for 30 simulated minutes (crash backoff)

It makes **no attempt to reproduce real JobScheduler heuristics**, which vary by OEM
anyway. `completedWithin()` is a logic assertion about Bridge's behavior under a scripted
regime — it is not a device guarantee. Use scenarios to pin down verdict and lifecycle
logic, and the device conformance suite for real-world behavior.
