---
title: Migration
nav_order: 10
---

# Migrating from WorkManager to Bridge

Three stages, each independently shippable and reversible.

## Stage 1 — swap the import (`bridge-compat`)

For the covered surface, migration is an import change:

```kotlin
// before: import androidx.work.*
import io.github.iamjosephmj.bridge.compat.*

class SyncWorker : Worker() {
    override fun doWork(): Result = try {
        api.sync(); Result.success()
    } catch (e: IOException) { Result.retry() }
}

BridgeWorkManager.enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP,
    OneTimeWorkRequest.Builder(SyncWorker::class.java)
        .setConstraints(Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED).build())
        .build())
```

Chains become chunked Bridge items — an interrupted chain resumes at the failed link,
which WorkManager cannot do:

```kotlin
BridgeWorkManager.beginUniqueWork("publish", ExistingWorkPolicy.KEEP, uploadRequest)
    .then(createPostRequest)
    .then(commitRequest)
    .enqueue()
```

**Not covered in v0.4** (keep these on WorkManager until they land natively):
periodic work, input/output `Data`, tags, LiveData/Flow observers, multi-branch
chains. `Bridge.initialize {}` must run before compat calls (Application.onCreate).

## Stage 2 — use the glass box without changing workers

Even before touching your workers, Bridge answers questions WorkManager cannot:

```kotlin
Bridge.whyPending("sync").render(now)   // the cause, not just ENQUEUED
Bridge.ledger("sync")                    // per-run history, deaths, device context
Bridge.report()                          // whole-app health incl. cost flags
```

## Stage 3 — go native for the headline features

Rewrite a worker onto the native API when you want chunk-exact resumption, deadlines,
or importance-aware scheduling:

```kotlin
Bridge.initialize(context) {
    worker("photo-backup") { PhotoBackupWorker() }   // ChunkedWorker
}
Bridge.enqueue(workRequest("photo-backup", "photo-backup") {
    chunks(40, estimatedUpBytes = 200_000_000L)
    unmetered(); charging()
    importance(Importance.LOW)
    mustCompleteBy(tomorrow6amMs)        // L4 escalation ladder takes it from there
})
```

Rollback at any stage is the reverse import change — the compat façade keeps both
worlds runnable side by side (different unique-name namespaces recommended).
