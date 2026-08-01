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

1. content-trigger work is runnable only after a scripted `contentChanged` on one of
   its uris at or after enqueue — earlier changes don't count, mirroring the
   platform's per-schedule observer registration
2. background-restricted blocks everything
3. Doze: deep Doze blocks except during scripted maintenance windows — but
   device-idle work is *inverted*: it runs only while the device is dozing
4. battery-not-low and storage-not-low gate on the scripted flags
5. network-required work gates on `NETWORK_VALIDATED`
6. Data Saver (and no unmetered network) blocks unmetered-constrained work
7. charging constraint gates on the scripted charging flag
8. standby buckets delay the *first* dispatch by the platform's documented deferral
   floors — WORKING_SET ~2h, FREQUENT ~8h, RARE ~24h; expedited jobs bypass the
   floor (modeling the platform's relaxed quota — fidelity disclaimer below applies)
9. a retry-stop parks the item for 30 simulated minutes (crash backoff)

It makes **no attempt to reproduce real JobScheduler heuristics**, which vary by OEM
anyway. `completedWithin()` is a logic assertion about Bridge's behavior under a scripted
regime — it is not a device guarantee. Use scenarios to pin down verdict and lifecycle
logic, and the device conformance suite for real-world behavior.
