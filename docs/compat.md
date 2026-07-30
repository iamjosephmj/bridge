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
| Data | `Data` / `workDataOf`, `setInputData`, `Worker.inputData`, `Result.success(data)`, `getOutputData` |
| Tags | `addTag`, `cancelAllWorkByTag` |
| Observers | `getWorkInfoStateFlow` (LiveData via `asLiveData()`) |
| Chains | `beginUniqueWork(...).then(...).enqueue()` — resumes at the failed link |
| Multi-branch chains | `WorkContinuation.combine(...)` |
| Introspection and control | `getWorkInfoState`, `cancelUniqueWork` |

## Data through chains

Each link's `inputData` is its request's input overwritten by upstream outputs, and link
outputs are journaled per chunk — so a chain that dies at link N and resumes still hands
link N the outputs of links 0..N-1:

```kotlin
class UploadWorker : Worker() {
    override fun doWork(): Result =
        Result.success(workDataOf("remoteUrl" to api.upload(inputData.getString("file")!!)))
}
class CommitWorker : Worker() {
    override fun doWork(): Result {
        db.commit(inputData.getString("remoteUrl")!!)   // upstream output, post-death too
        return Result.success()
    }
}
```

## Multi-branch chains

```kotlin
val resize = BridgeWorkManager.beginUniqueWork("resize", KEEP, resizeRequest)
val caption = BridgeWorkManager.beginUniqueWork("caption", KEEP, captionRequest)
WorkContinuation.combine(listOf(resize, caption))
    .then(publishRequest)      // runs after both; inputs = merged branch outputs
    .enqueue()                 // join item is named "resize+caption:join"
```

Full migration guide: [Migration](MIGRATION.html).
