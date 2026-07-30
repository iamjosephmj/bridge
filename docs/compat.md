---
title: Tier 1 — Compat
nav_order: 4
---

# Tier 1 — Compat: an androidx.work façade

![The androidx.work import is replaced by bridge.compat while a broken chain re-links at exactly the broken link.](assets/tier1-compat.svg)

Existing workers are unchanged; only the import changes. For the covered surface, migration is an import change:

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

## Chains resume at the failed link

Chains compile to a single Bridge item whose links are chunks, so an interrupted chain resumes at the failed link — which WorkManager cannot do:

```kotlin
BridgeWorkManager.beginUniqueWork("publish", ExistingWorkPolicy.KEEP, uploadRequest)
    .then(createPostRequest)
    .then(commitRequest)
    .enqueue()
```

If the process dies during `createPostRequest`, the next attempt starts at `createPostRequest` — `uploadRequest`'s completed result survives in the journal.

## Covered surface

| Area | Coverage |
|---|---|
| One-time work | `OneTimeWorkRequest`, `enqueueUniqueWork` (KEEP), `setInitialDelay` |
| Periodic work | `PeriodicWorkRequest`, `enqueueUniquePeriodicWork` (KEEP / UPDATE) |
| Constraints | Full `Constraints.Builder` surface: charging, network type, battery-not-low, storage-not-low, device-idle |
| Chains | `beginUniqueWork(...).then(...).enqueue()` — resumes at the failed link |
| Introspection and control | `getWorkInfoState`, `cancelUniqueWork` |

Not covered (keep this work on WorkManager; both run side by side): `Data` payloads, tags, LiveData/Flow observers, multi-branch chains.

Full migration guide: [Migration](MIGRATION.html).
