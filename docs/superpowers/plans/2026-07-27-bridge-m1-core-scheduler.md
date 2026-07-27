# Bridge M1 — Core Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Bridge v0.1: an event-journaled, JobWorkItem-multiplexed background scheduler with leased execution, death forensics, measured run cost, a minimal enqueue API — and the benchmark harness that compares it against WorkManager.

**Architecture:** Append-only SQLite event journal (L1) is the single source of truth; work is dispatched as `JobWorkItem`s into a small set of host JobScheduler jobs (L2); a `WorkRunner` executes workers under a lease with black-box death stamps and HealthStats cost deltas (L5); a small `Bridge` facade ties it together (L6). All system boundaries (`Clock`, `JobGateway`, `CostMeter`, `ProcessDeathSource`) are interfaces so everything below instrumented tests runs on the JVM (Robolectric for SQLite/Context).

**Tech Stack:** Kotlin 2.2.10, AGP 9.3.1 (built-in Kotlin), coroutines, kotlinx-serialization-json (event payloads), android.database.sqlite (no Room), JUnit4 + Truth + Robolectric + kotlinx-coroutines-test. Bench module additionally uses androidx.work 2.10.0.

**Spec:** `docs/superpowers/specs/2026-07-27-bridge-design.md` (M1 scope: §4.1, §4.2, §4.5, minimal §4.6, benchmark harness §6).

## Global Constraints

- New library module `:bridge-runtime`, package root `io.github.iamjosephmj.bridge`. New app module `:bench`, package `io.github.iamjosephmj.bench`.
- `:bridge-runtime`: minSdk 26, compileSdk 36. **No androidx.work dependency, no Room.** Higher-API calls gated with `Build.VERSION.SDK_INT` checks.
- `:bench`: minSdk 26, compileSdk 36; depends on `:bridge-runtime` AND `androidx.work:work-runtime-ktx:2.10.0`.
- No direct `System.currentTimeMillis()` / `SystemClock` in library code — always via `BridgeClock`.
- All journal writes go through one serial executor; every DB mutation in a transaction.
- Every task: tests first (TDD), commit at task end. Unit tests run with `./gradlew :bridge-runtime:testDebugUnitTest`.
- Robolectric `@Config(sdk = [34])` unless a test needs another level.

---

### Task 1: `:bridge-runtime` module + event model

**Files:**
- Modify: `settings.gradle.kts` (add `include(":bridge-runtime")`)
- Modify: `gradle/libs.versions.toml`
- Create: `bridge-runtime/build.gradle.kts`
- Create: `bridge-runtime/src/main/AndroidManifest.xml`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/store/WorkEvent.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/store/EventCodec.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/BridgeClock.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/store/EventCodecTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `sealed interface WorkEvent` (all subtypes below), `EventCodec.encode(WorkEvent): String`, `EventCodec.decode(String): WorkEvent`, `interface BridgeClock { fun now(): Long }`, `class FakeClock(var nowMs: Long = 0L) : BridgeClock`.

- [ ] **Step 1: Add version catalog entries**

Append to `gradle/libs.versions.toml`:

```toml
# under [versions]
coroutines = "1.10.2"
serialization = "1.9.0"
robolectric = "4.16"
truth = "1.4.4"
workRuntime = "2.10.0"

# under [libraries]
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }
androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workRuntime" }

# under [plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Create the module**

`settings.gradle.kts`: add `include(":bridge-runtime")` after `include(":app")`.

`bridge-runtime/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.iamjosephmj.bridge"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

`bridge-runtime/src/main/AndroidManifest.xml`:

```xml
<manifest />
```

- [ ] **Step 3: Write the failing codec test**

`EventCodecTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EventCodecTest {
    @Test fun `round-trips every event type`() {
        val events = listOf(
            WorkEvent.Enqueued("w1", 100L, workerName = "upload", generation = 1,
                importance = 2, requiresCharging = true, requiresUnmetered = true,
                chunkCount = 40, estimatedUpBytes = 200_000_000L, maxAttempts = 5),
            WorkEvent.Dispatched("w1", 101L, hostClass = "UNMETERED_CHARGING", generation = 1),
            WorkEvent.Started("w1", 102L, attempt = 1, generation = 1),
            WorkEvent.ChunkCompleted("w1", 103L, chunkIndex = 6),
            WorkEvent.Stopped("w1", 104L, stopReason = 3),
            WorkEvent.Died("w1", 105L, exitReason = 3, rssKb = 380_000, step = "chunk:6", attempt = 1),
            WorkEvent.Finished("w1", 106L, success = true,
                cpuUserMs = 1200, cpuSystemMs = 300, txBytes = 5_000_000, rxBytes = 1000),
        )
        for (e in events) {
            assertThat(EventCodec.decode(EventCodec.encode(e))).isEqualTo(e)
        }
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "io.github.iamjosephmj.bridge.store.EventCodecTest"`
Expected: FAIL (unresolved references `WorkEvent`, `EventCodec`).

- [ ] **Step 5: Implement `BridgeClock.kt`, `WorkEvent.kt`, `EventCodec.kt`**

`BridgeClock.kt`:

```kotlin
package io.github.iamjosephmj.bridge

interface BridgeClock { fun now(): Long }

class SystemBridgeClock : BridgeClock {
    override fun now(): Long = System.currentTimeMillis()
}

class FakeClock(var nowMs: Long = 0L) : BridgeClock {
    override fun now(): Long = nowMs
    fun advance(ms: Long) { nowMs += ms }
}
```

`WorkEvent.kt`:

```kotlin
package io.github.iamjosephmj.bridge.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkEvent {
    val workId: String
    val at: Long

    @Serializable @SerialName("enqueued")
    data class Enqueued(
        override val workId: String, override val at: Long,
        val workerName: String, val generation: Int,
        val importance: Int,                 // 0=MIN 1=LOW 2=DEFAULT 3=HIGH
        val requiresCharging: Boolean = false,
        val requiresUnmetered: Boolean = false,
        val chunkCount: Int = 0,             // 0 = not chunked
        val estimatedUpBytes: Long = 0L,
        val maxAttempts: Int = 3,
    ) : WorkEvent

    @Serializable @SerialName("dispatched")
    data class Dispatched(
        override val workId: String, override val at: Long,
        val hostClass: String, val generation: Int,
    ) : WorkEvent

    @Serializable @SerialName("started")
    data class Started(
        override val workId: String, override val at: Long,
        val attempt: Int, val generation: Int,
    ) : WorkEvent

    @Serializable @SerialName("chunkCompleted")
    data class ChunkCompleted(
        override val workId: String, override val at: Long, val chunkIndex: Int,
    ) : WorkEvent

    @Serializable @SerialName("stopped")
    data class Stopped(
        override val workId: String, override val at: Long, val stopReason: Int,
    ) : WorkEvent

    @Serializable @SerialName("died")
    data class Died(
        override val workId: String, override val at: Long,
        val exitReason: Int, val rssKb: Long, val step: String, val attempt: Int,
    ) : WorkEvent

    @Serializable @SerialName("finished")
    data class Finished(
        override val workId: String, override val at: Long, val success: Boolean,
        val cpuUserMs: Long = 0, val cpuSystemMs: Long = 0,
        val txBytes: Long = 0, val rxBytes: Long = 0,
    ) : WorkEvent

    @Serializable @SerialName("cancelled")
    data class Cancelled(override val workId: String, override val at: Long) : WorkEvent
}
```

`EventCodec.kt`:

```kotlin
package io.github.iamjosephmj.bridge.store

import kotlinx.serialization.json.Json

object EventCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(event: WorkEvent): String = json.encodeToString(WorkEvent.serializer(), event)
    fun decode(raw: String): WorkEvent = json.decodeFromString(WorkEvent.serializer(), raw)
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "io.github.iamjosephmj.bridge.store.EventCodecTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml bridge-runtime docs
git commit -m "feat(store): bridge-runtime module with WorkEvent model and codec"
```

---

### Task 2: WorkState fold (pure)

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/store/WorkState.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/store/WorkStateFoldTest.kt`

**Interfaces:**
- Consumes: `WorkEvent` (Task 1).
- Produces:

```kotlin
enum class RunState { ENQUEUED, DISPATCHED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
data class WorkState(
    val workId: String, val workerName: String, val generation: Int,
    val runState: RunState, val attempt: Int,
    val nextChunk: Int,            // first not-yet-completed chunk index
    val chunkCount: Int, val maxAttempts: Int, val importance: Int,
    val requiresCharging: Boolean, val requiresUnmetered: Boolean,
    val estimatedUpBytes: Long,
    val lastStopReason: Int?, val lastDeath: WorkEvent.Died?,
)
fun foldWorkState(events: List<WorkEvent>): WorkState?   // null if no Enqueued
```

- [ ] **Step 1: Write the failing fold tests**

`WorkStateFoldTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkStateFoldTest {
    private fun enq(chunks: Int = 0, maxAttempts: Int = 3) = WorkEvent.Enqueued(
        "w1", 1L, workerName = "upload", generation = 1, importance = 2,
        chunkCount = chunks, maxAttempts = maxAttempts)

    @Test fun `empty list folds to null`() {
        assertThat(foldWorkState(emptyList())).isNull()
    }

    @Test fun `enqueue then start is RUNNING at attempt 1`() {
        val s = foldWorkState(listOf(enq(), WorkEvent.Started("w1", 2L, 1, 1)))!!
        assertThat(s.runState).isEqualTo(RunState.RUNNING)
        assertThat(s.attempt).isEqualTo(1)
    }

    @Test fun `chunk completion advances nextChunk, stop returns to ENQUEUED`() {
        val s = foldWorkState(listOf(
            enq(chunks = 40),
            WorkEvent.Started("w1", 2L, 1, 1),
            WorkEvent.ChunkCompleted("w1", 3L, 0),
            WorkEvent.ChunkCompleted("w1", 4L, 1),
            WorkEvent.Stopped("w1", 5L, stopReason = 10),
        ))!!
        assertThat(s.nextChunk).isEqualTo(2)
        assertThat(s.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(s.lastStopReason).isEqualTo(10)
    }

    @Test fun `death returns work to ENQUEUED and records forensics`() {
        val died = WorkEvent.Died("w1", 5L, exitReason = 3, rssKb = 380_000, step = "chunk:2", attempt = 1)
        val s = foldWorkState(listOf(enq(chunks = 40), WorkEvent.Started("w1", 2L, 1, 1), died))!!
        assertThat(s.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(s.lastDeath).isEqualTo(died)
    }

    @Test fun `finished success is terminal SUCCEEDED`() {
        val s = foldWorkState(listOf(enq(), WorkEvent.Started("w1", 2L, 1, 1),
            WorkEvent.Finished("w1", 3L, success = true)))!!
        assertThat(s.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `finished failure is terminal FAILED`() {
        val s = foldWorkState(listOf(enq(), WorkEvent.Started("w1", 2L, 1, 1),
            WorkEvent.Finished("w1", 3L, success = false)))!!
        assertThat(s.runState).isEqualTo(RunState.FAILED)
    }

    @Test fun `re-enqueue bumps generation and resets progress`() {
        val s = foldWorkState(listOf(
            enq(chunks = 10),
            WorkEvent.Started("w1", 2L, 1, 1),
            WorkEvent.ChunkCompleted("w1", 3L, 0),
            WorkEvent.Finished("w1", 4L, success = true),
            enq(chunks = 10).copy(at = 5L, generation = 2),
        ))!!
        assertThat(s.generation).isEqualTo(2)
        assertThat(s.nextChunk).isEqualTo(0)
        assertThat(s.runState).isEqualTo(RunState.ENQUEUED)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.WorkStateFoldTest"`
Expected: FAIL (unresolved `foldWorkState`, `RunState`, `WorkState`).

- [ ] **Step 3: Implement `WorkState.kt`**

```kotlin
package io.github.iamjosephmj.bridge.store

enum class RunState { ENQUEUED, DISPATCHED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class WorkState(
    val workId: String, val workerName: String, val generation: Int,
    val runState: RunState, val attempt: Int,
    val nextChunk: Int, val chunkCount: Int, val maxAttempts: Int,
    val importance: Int, val requiresCharging: Boolean, val requiresUnmetered: Boolean,
    val estimatedUpBytes: Long,
    val lastStopReason: Int?, val lastDeath: WorkEvent.Died?,
)

fun foldWorkState(events: List<WorkEvent>): WorkState? {
    var s: WorkState? = null
    for (e in events) {
        s = when (e) {
            is WorkEvent.Enqueued -> WorkState(
                workId = e.workId, workerName = e.workerName, generation = e.generation,
                runState = RunState.ENQUEUED, attempt = 0,
                nextChunk = 0, chunkCount = e.chunkCount, maxAttempts = e.maxAttempts,
                importance = e.importance, requiresCharging = e.requiresCharging,
                requiresUnmetered = e.requiresUnmetered, estimatedUpBytes = e.estimatedUpBytes,
                lastStopReason = null, lastDeath = null,
            )
            is WorkEvent.Dispatched -> s?.copy(runState = RunState.DISPATCHED)
            is WorkEvent.Started -> s?.copy(runState = RunState.RUNNING, attempt = e.attempt)
            is WorkEvent.ChunkCompleted -> s?.copy(nextChunk = maxOf(s.nextChunk, e.chunkIndex + 1))
            is WorkEvent.Stopped -> s?.copy(runState = RunState.ENQUEUED, lastStopReason = e.stopReason)
            is WorkEvent.Died -> s?.copy(runState = RunState.ENQUEUED, lastDeath = e)
            is WorkEvent.Finished -> s?.copy(
                runState = if (e.success) RunState.SUCCEEDED else RunState.FAILED)
            is WorkEvent.Cancelled -> s?.copy(runState = RunState.CANCELLED)
        }
    }
    return s
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.WorkStateFoldTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(store): pure WorkState fold over event log"
```

---

### Task 3: Journal (SQLite, WAL, serial writer)

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/store/Journal.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/store/JournalTest.kt`

**Interfaces:**
- Consumes: `WorkEvent`, `EventCodec`, `foldWorkState`, `WorkState`, `RunState`, `BridgeClock`.
- Produces:

```kotlin
class Journal(context: Context, dbName: String = "bridge.db",
              ioExecutor: Executor = Executors.newSingleThreadExecutor()) {
    fun append(event: WorkEvent)                       // sync on caller for tests via direct executor
    fun appendAll(events: List<WorkEvent>)
    fun events(workId: String): List<WorkEvent>
    fun state(workId: String): WorkState?
    fun liveWork(): List<WorkState>                    // ENQUEUED/DISPATCHED/RUNNING
    fun runningWork(): List<WorkState>
    fun prune(olderThanMs: Long, now: Long)            // keeps events of live work
    fun close()
}
```

All mutations run on `ioExecutor` (tests pass `Executor { it.run() }` for synchronous behavior) inside transactions; reads are synchronous.

- [ ] **Step 1: Write the failing journal tests**

`JournalTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.store

import androidx.test.core.app.ApplicationProvider   // via robolectric's androidx.test bundling
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val direct = Executor { it.run() }
    private fun journal() = Journal(context, dbName = "test-${System.nanoTime()}.db", ioExecutor = direct)

    private fun enq(id: String, at: Long) = WorkEvent.Enqueued(
        id, at, workerName = "w", generation = 1, importance = 2)

    @Test fun `append then read round-trips and folds`() {
        val j = journal()
        j.append(enq("w1", 1L))
        j.append(WorkEvent.Started("w1", 2L, attempt = 1, generation = 1))
        assertThat(j.events("w1")).hasSize(2)
        assertThat(j.state("w1")!!.runState).isEqualTo(RunState.RUNNING)
    }

    @Test fun `liveWork excludes terminal work`() {
        val j = journal()
        j.append(enq("w1", 1L))
        j.append(enq("w2", 2L))
        j.appendAll(listOf(
            WorkEvent.Started("w2", 3L, 1, 1),
            WorkEvent.Finished("w2", 4L, success = true)))
        assertThat(j.liveWork().map { it.workId }).containsExactly("w1")
    }

    @Test fun `state survives reopen`() {
        val name = "persist-${System.nanoTime()}.db"
        Journal(context, name, direct).apply { append(enq("w1", 1L)); close() }
        assertThat(Journal(context, name, direct).state("w1")!!.runState)
            .isEqualTo(RunState.ENQUEUED)
    }

    @Test fun `prune drops old terminal events but keeps live work`() {
        val j = journal()
        j.append(enq("old", 1L))
        j.appendAll(listOf(WorkEvent.Started("old", 2L, 1, 1),
            WorkEvent.Finished("old", 3L, success = true)))
        j.append(enq("live", 4L))
        j.prune(olderThanMs = 100L, now = 1000L)
        assertThat(j.events("old")).isEmpty()
        assertThat(j.events("live")).hasSize(1)
    }
}
```

Note: Robolectric provides `ApplicationProvider` via `androidx.test:core`; add `testImplementation("androidx.test:core-ktx:1.6.1")` to `bridge-runtime/build.gradle.kts` dependencies in this step, and a catalog entry `androidx-test-core = { group = "androidx.test", name = "core-ktx", version = "1.6.1" }`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.JournalTest"`
Expected: FAIL (unresolved `Journal`).

- [ ] **Step 3: Implement `Journal.kt`**

```kotlin
package io.github.iamjosephmj.bridge.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val SCHEMA_VERSION = 1

private class JournalDb(context: Context, name: String) :
    SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE events (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            work_id TEXT NOT NULL,
            at INTEGER NOT NULL,
            payload TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX idx_events_work ON events(work_id)")
        db.execSQL("""CREATE TABLE work_state (
            work_id TEXT PRIMARY KEY,
            run_state TEXT NOT NULL,
            snapshot TEXT NOT NULL)""")   // snapshot = full event list is folded on read; column kept for queries
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
}

class Journal(
    context: Context,
    dbName: String = "bridge.db",
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor(),
) {
    private val db = JournalDb(context.applicationContext, dbName)

    fun append(event: WorkEvent) = appendAll(listOf(event))

    fun appendAll(events: List<WorkEvent>) {
        ioExecutor.execute {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                for (e in events) {
                    w.insert("events", null, ContentValues().apply {
                        put("work_id", e.workId); put("at", e.at)
                        put("payload", EventCodec.encode(e))
                    })
                }
                for (id in events.map { it.workId }.distinct()) {
                    val st = foldLocked(w, id) ?: continue
                    w.insertWithOnConflict("work_state", null, ContentValues().apply {
                        put("work_id", id); put("run_state", st.runState.name)
                        put("snapshot", "")
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                w.setTransactionSuccessful()
            } finally { w.endTransaction() }
        }
    }

    fun events(workId: String): List<WorkEvent> {
        val out = mutableListOf<WorkEvent>()
        db.readableDatabase.rawQuery(
            "SELECT payload FROM events WHERE work_id = ? ORDER BY seq", arrayOf(workId)).use { c ->
            while (c.moveToNext()) out += EventCodec.decode(c.getString(0))
        }
        return out
    }

    fun state(workId: String): WorkState? = foldWorkState(events(workId))

    fun liveWork(): List<WorkState> = statesWhere(
        "run_state IN ('ENQUEUED','DISPATCHED','RUNNING')")

    fun runningWork(): List<WorkState> = statesWhere("run_state = 'RUNNING'")

    private fun statesWhere(where: String): List<WorkState> {
        val ids = mutableListOf<String>()
        db.readableDatabase.rawQuery(
            "SELECT work_id FROM work_state WHERE $where", null).use { c ->
            while (c.moveToNext()) ids += c.getString(0)
        }
        return ids.mapNotNull { state(it) }
    }

    fun prune(olderThanMs: Long, now: Long) {
        ioExecutor.execute {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                w.execSQL("""DELETE FROM events WHERE at < ? AND work_id IN
                    (SELECT work_id FROM work_state
                     WHERE run_state IN ('SUCCEEDED','FAILED','CANCELLED'))""",
                    arrayOf<Any>(now - olderThanMs))
                w.execSQL("""DELETE FROM work_state WHERE
                    run_state IN ('SUCCEEDED','FAILED','CANCELLED') AND
                    work_id NOT IN (SELECT DISTINCT work_id FROM events)""")
                w.setTransactionSuccessful()
            } finally { w.endTransaction() }
        }
    }

    private fun foldLocked(db: SQLiteDatabase, workId: String): WorkState? {
        val events = mutableListOf<WorkEvent>()
        db.rawQuery("SELECT payload FROM events WHERE work_id = ? ORDER BY seq",
            arrayOf(workId)).use { c ->
            while (c.moveToNext()) events += EventCodec.decode(c.getString(0))
        }
        return foldWorkState(events)
    }

    fun close() = db.close()
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.JournalTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime gradle/libs.versions.toml
git commit -m "feat(store): SQLite WAL event journal with serial writer and read models"
```

---

### Task 4: Public request API + worker contracts + registry

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/api/WorkRequest.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/api/BridgeWorker.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/api/WorkerRegistry.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/api/WorkRequestDslTest.kt`

**Interfaces:**
- Consumes: `WorkEvent.Enqueued` (shape alignment only).
- Produces:

```kotlin
enum class Importance { MIN, LOW, DEFAULT, HIGH }          // ordinal used as Int in events
class WorkRequest private constructor(
    val name: String, val workerName: String, val importance: Importance,
    val requiresCharging: Boolean, val requiresUnmetered: Boolean,
    val chunkCount: Int, val estimatedUpBytes: Long, val maxAttempts: Int)
fun workRequest(name: String, workerName: String,
                block: WorkRequestBuilder.() -> Unit = {}): WorkRequest

sealed interface RunResult { object Success : RunResult; object Failure : RunResult; object Retry : RunResult }
interface BridgeWorker { suspend fun run(ctx: RunContext): RunResult }
interface ChunkedWorker : BridgeWorker { suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult }
class RunContext(val workId: String, val attempt: Int, val isStopped: () -> Boolean)

class WorkerRegistry {
    fun register(name: String, factory: () -> BridgeWorker)
    fun create(name: String): BridgeWorker    // throws IllegalArgumentException if unknown
}
```

`ChunkedWorker.run` has a default implementation that loops `runChunk` from `ctx`-provided start (the runner drives chunks; default `run` throws `UnsupportedOperationException` — the runner calls `runChunk` directly for chunked work).

- [ ] **Step 1: Write the failing DSL/registry tests**

`WorkRequestDslTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.api

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkRequestDslTest {
    @Test fun `builder applies all fields with sane defaults`() {
        val r = workRequest(name = "photo-backup", workerName = "upload") {
            importance(Importance.LOW)
            charging()
            unmetered()
            chunks(count = 40, estimatedUpBytes = 200_000_000L)
            maxAttempts(5)
        }
        assertThat(r.importance).isEqualTo(Importance.LOW)
        assertThat(r.requiresCharging).isTrue()
        assertThat(r.requiresUnmetered).isTrue()
        assertThat(r.chunkCount).isEqualTo(40)
        assertThat(r.maxAttempts).isEqualTo(5)

        val plain = workRequest("ping", "pinger")
        assertThat(plain.importance).isEqualTo(Importance.DEFAULT)
        assertThat(plain.chunkCount).isEqualTo(0)
        assertThat(plain.maxAttempts).isEqualTo(3)
    }

    @Test fun `registry creates registered workers and rejects unknown names`() {
        val registry = WorkerRegistry()
        registry.register("upload") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success
        } }
        assertThat(registry.create("upload")).isNotNull()
        assertThrows(IllegalArgumentException::class.java) { registry.create("nope") }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.WorkRequestDslTest"`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement the three API files**

`WorkRequest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.api

enum class Importance { MIN, LOW, DEFAULT, HIGH }

class WorkRequest internal constructor(
    val name: String, val workerName: String, val importance: Importance,
    val requiresCharging: Boolean, val requiresUnmetered: Boolean,
    val chunkCount: Int, val estimatedUpBytes: Long, val maxAttempts: Int,
)

class WorkRequestBuilder internal constructor(
    private val name: String, private val workerName: String) {
    private var importance = Importance.DEFAULT
    private var charging = false
    private var unmetered = false
    private var chunkCount = 0
    private var estimatedUpBytes = 0L
    private var maxAttempts = 3

    fun importance(value: Importance) { importance = value }
    fun charging() { charging = true }
    fun unmetered() { unmetered = true }
    fun chunks(count: Int, estimatedUpBytes: Long = 0L) {
        require(count > 0) { "chunk count must be positive" }
        chunkCount = count; this.estimatedUpBytes = estimatedUpBytes
    }
    fun maxAttempts(value: Int) { require(value > 0); maxAttempts = value }

    internal fun build() = WorkRequest(name, workerName, importance,
        charging, unmetered, chunkCount, estimatedUpBytes, maxAttempts)
}

fun workRequest(name: String, workerName: String,
                block: WorkRequestBuilder.() -> Unit = {}): WorkRequest =
    WorkRequestBuilder(name, workerName).apply(block).build()
```

`BridgeWorker.kt`:

```kotlin
package io.github.iamjosephmj.bridge.api

sealed interface RunResult {
    data object Success : RunResult
    data object Failure : RunResult
    data object Retry : RunResult
}

class RunContext(
    val workId: String,
    val attempt: Int,
    val isStopped: () -> Boolean,
)

interface BridgeWorker {
    suspend fun run(ctx: RunContext): RunResult
}

interface ChunkedWorker : BridgeWorker {
    suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult
    override suspend fun run(ctx: RunContext): RunResult =
        throw UnsupportedOperationException("chunked work is driven via runChunk")
}
```

`WorkerRegistry.kt`:

```kotlin
package io.github.iamjosephmj.bridge.api

class WorkerRegistry {
    private val factories = mutableMapOf<String, () -> BridgeWorker>()

    fun register(name: String, factory: () -> BridgeWorker) {
        factories[name] = factory
    }

    fun create(name: String): BridgeWorker =
        (factories[name] ?: throw IllegalArgumentException(
            "No worker registered for '$name'. Call WorkerRegistry.register(\"$name\") { ... } during Bridge.initialize.")
        ).invoke()
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.WorkRequestDslTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(api): work request DSL, worker contracts, explicit registry"
```

---

### Task 5: Job plan compiler (WorkSpec → host class + JobInfo)

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/HostJobClass.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/JobPlanCompiler.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/dispatch/JobPlanCompilerTest.kt`

**Interfaces:**
- Consumes: `WorkState` (Task 2), `Importance` ordinals.
- Produces:

```kotlin
enum class HostJobClass(val jobId: Int) {
    DEFAULT(710_001), DEFERRABLE(710_002), UNMETERED_CHARGING(710_003);
    companion object { fun forWork(state: WorkState): HostJobClass }
}
object JobPlanCompiler {
    fun jobInfo(context: Context, hostClass: HostJobClass,
                serviceComponent: ComponentName): JobInfo
}
```

Mapping rules (M1 subset of spec §4.2):
- `requiresUnmetered && requiresCharging` → `UNMETERED_CHARGING`
- `importance <= LOW` → `DEFERRABLE`
- else → `DEFAULT`

JobInfo per class: DEFAULT → `NETWORK_TYPE_ANY`; DEFERRABLE → `NETWORK_TYPE_ANY` + (API 33+) `setPriority(PRIORITY_LOW)`; UNMETERED_CHARGING → `NETWORK_TYPE_UNMETERED` + `setRequiresCharging(true)` + (API 33+) `PRIORITY_LOW`. All classes: (API 34+) handled by namespace at the gateway (Task 6), backoff criteria exponential 30s.

- [ ] **Step 1: Write the failing compiler tests**

`JobPlanCompilerTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JobPlanCompilerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val component = ComponentName(context, "io.github.iamjosephmj.bridge.dispatch.BridgeJobService")

    private fun state(importance: Int = 2, charging: Boolean = false, unmetered: Boolean = false) =
        WorkState("w1", "w", 1, RunState.ENQUEUED, 0, 0, 0, 3,
            importance, charging, unmetered, 0L, null, null)

    @Test fun `unmetered plus charging work maps to UNMETERED_CHARGING host`() {
        assertThat(HostJobClass.forWork(state(charging = true, unmetered = true)))
            .isEqualTo(HostJobClass.UNMETERED_CHARGING)
    }

    @Test fun `low importance maps to DEFERRABLE`() {
        assertThat(HostJobClass.forWork(state(importance = 1)))
            .isEqualTo(HostJobClass.DEFERRABLE)
    }

    @Test fun `default work maps to DEFAULT`() {
        assertThat(HostJobClass.forWork(state())).isEqualTo(HostJobClass.DEFAULT)
    }

    @Test fun `UNMETERED_CHARGING JobInfo requires unmetered network and charging`() {
        val info = JobPlanCompiler.jobInfo(context, HostJobClass.UNMETERED_CHARGING, component)
        assertThat(info.networkType).isEqualTo(JobInfo.NETWORK_TYPE_UNMETERED)
        assertThat(info.isRequireCharging).isTrue()
        assertThat(info.id).isEqualTo(HostJobClass.UNMETERED_CHARGING.jobId)
    }

    @Test fun `DEFERRABLE JobInfo carries low priority on API 34`() {
        val info = JobPlanCompiler.jobInfo(context, HostJobClass.DEFERRABLE, component)
        assertThat(info.priority).isEqualTo(JobInfo.PRIORITY_LOW)
    }

    @Test fun `all host JobInfos are not persisted`() {
        for (hc in HostJobClass.entries) {
            assertThat(JobPlanCompiler.jobInfo(context, hc, component).isPersisted).isFalse()
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.JobPlanCompilerTest"`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement `HostJobClass.kt` and `JobPlanCompiler.kt`**

`HostJobClass.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import io.github.iamjosephmj.bridge.store.WorkState

enum class HostJobClass(val jobId: Int) {
    DEFAULT(710_001),
    DEFERRABLE(710_002),
    UNMETERED_CHARGING(710_003);

    companion object {
        fun forWork(state: WorkState): HostJobClass = when {
            state.requiresUnmetered && state.requiresCharging -> UNMETERED_CHARGING
            state.importance <= 1 -> DEFERRABLE   // MIN=0, LOW=1
            else -> DEFAULT
        }
    }
}
```

`JobPlanCompiler.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build

object JobPlanCompiler {
    fun jobInfo(context: Context, hostClass: HostJobClass,
                serviceComponent: ComponentName): JobInfo {
        val b = JobInfo.Builder(hostClass.jobId, serviceComponent)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(false)   // reconciler reschedules; WorkManager-proven pattern
        when (hostClass) {
            HostJobClass.DEFAULT ->
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            HostJobClass.DEFERRABLE -> {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                if (Build.VERSION.SDK_INT >= 33) b.setPriority(JobInfo.PRIORITY_LOW)
            }
            HostJobClass.UNMETERED_CHARGING -> {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                b.setRequiresCharging(true)
                if (Build.VERSION.SDK_INT >= 33) b.setPriority(JobInfo.PRIORITY_LOW)
            }
        }
        if (Build.VERSION.SDK_INT >= 35) b.setTraceTag("bridge:${hostClass.name.lowercase()}")
        return b.build()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.JobPlanCompilerTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(dispatch): host job classes and JobInfo compilation"
```

---

### Task 6: JobGateway + Dispatcher

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/JobGateway.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/Dispatcher.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/dispatch/DispatcherTest.kt`

**Interfaces:**
- Consumes: `Journal`, `WorkState`, `HostJobClass`, `JobPlanCompiler`, `BridgeClock`, `WorkEvent.Dispatched`.
- Produces:

```kotlin
data class WorkItemPayload(val workId: String, val generation: Int)
interface JobGateway {
    fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean
    fun cancelAll()
}
class SystemJobGateway(context: Context) : JobGateway          // real impl
class FakeJobGateway : JobGateway {                            // test impl
    val enqueued: MutableList<Pair<HostJobClass, WorkItemPayload>>
    var failNext: Boolean
}
class Dispatcher(journal: Journal, gateway: JobGateway, clock: BridgeClock) {
    fun dispatchAll()                     // every ENQUEUED live work → gateway + Dispatched event
    fun dispatch(workId: String)
}
```

`SystemJobGateway` uses `JobScheduler` (namespaced `forNamespace("bridge")` on API 34+), builds the `JobWorkItem` with an `Intent` carrying `EXTRA_WORK_ID` / `EXTRA_GENERATION`, and calls `scheduler.enqueue(jobInfo, workItem)`.

- [ ] **Step 1: Write the failing dispatcher tests**

`DispatcherTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DispatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "d-${System.nanoTime()}.db", Executor { it.run() })
    private val gateway = FakeJobGateway()
    private val clock = FakeClock(1000L)
    private val dispatcher = Dispatcher(journal, gateway, clock)

    private fun enqueue(id: String, unmetered: Boolean = false, charging: Boolean = false) {
        journal.append(WorkEvent.Enqueued(id, clock.now(), workerName = "w", generation = 1,
            importance = 2, requiresCharging = charging, requiresUnmetered = unmetered))
    }

    @Test fun `dispatchAll hands every enqueued work to the gateway and journals it`() {
        enqueue("w1"); enqueue("w2", unmetered = true, charging = true)
        dispatcher.dispatchAll()
        assertThat(gateway.enqueued.map { it.second.workId }).containsExactly("w1", "w2")
        assertThat(gateway.enqueued.first { it.second.workId == "w2" }.first)
            .isEqualTo(HostJobClass.UNMETERED_CHARGING)
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.DISPATCHED)
    }

    @Test fun `already dispatched work is not re-enqueued`() {
        enqueue("w1")
        dispatcher.dispatchAll()
        dispatcher.dispatchAll()
        assertThat(gateway.enqueued).hasSize(1)
    }

    @Test fun `gateway failure leaves work ENQUEUED for retry`() {
        enqueue("w1")
        gateway.failNext = true
        dispatcher.dispatchAll()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.ENQUEUED)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.DispatcherTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `JobGateway.kt` and `Dispatcher.kt`**

`JobGateway.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

data class WorkItemPayload(val workId: String, val generation: Int)

const val EXTRA_WORK_ID = "bridge.EXTRA_WORK_ID"
const val EXTRA_GENERATION = "bridge.EXTRA_GENERATION"

interface JobGateway {
    fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean
    fun cancelAll()
}

class SystemJobGateway(private val context: Context) : JobGateway {
    private val scheduler: JobScheduler = run {
        val js = context.getSystemService(JobScheduler::class.java)
        if (Build.VERSION.SDK_INT >= 34) js.forNamespace("bridge") else js
    }
    private val component = ComponentName(context, BridgeJobService::class.java)

    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        val intent = Intent()
            .putExtra(EXTRA_WORK_ID, payload.workId)
            .putExtra(EXTRA_GENERATION, payload.generation)
        val info = JobPlanCompiler.jobInfo(context, hostClass, component)
        return try {
            scheduler.enqueue(info, JobWorkItem(intent)) == JobScheduler.RESULT_SUCCESS
        } catch (e: Exception) {   // OEM IllegalStateException / limit exceeded → conformance fallback signal
            false
        }
    }

    override fun cancelAll() { scheduler.cancelAll() }
}

class FakeJobGateway : JobGateway {
    val enqueued = mutableListOf<Pair<HostJobClass, WorkItemPayload>>()
    var failNext = false
    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        if (failNext) { failNext = false; return false }
        enqueued += hostClass to payload
        return true
    }
    override fun cancelAll() { enqueued.clear() }
}
```

`Dispatcher.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent

class Dispatcher(
    private val journal: Journal,
    private val gateway: JobGateway,
    private val clock: BridgeClock,
) {
    fun dispatchAll() {
        journal.liveWork()
            .filter { it.runState == RunState.ENQUEUED }
            .forEach { dispatchState(it) }
    }

    fun dispatch(workId: String) {
        val state = journal.state(workId) ?: return
        if (state.runState == RunState.ENQUEUED) dispatchState(state)
    }

    private fun dispatchState(state: io.github.iamjosephmj.bridge.store.WorkState) {
        val hostClass = HostJobClass.forWork(state)
        val ok = gateway.enqueue(hostClass, WorkItemPayload(state.workId, state.generation))
        if (ok) {
            journal.append(WorkEvent.Dispatched(
                state.workId, clock.now(), hostClass.name, state.generation))
        }
        // On failure: stay ENQUEUED; reconciler / next dispatchAll retries.
    }
}
```

Note: `BridgeJobService` doesn't exist yet — add a stub so this compiles; Task 7 fills it in:

```kotlin
// bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/BridgeJobService.kt
package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobParameters
import android.app.job.JobService

class BridgeJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean = false
    override fun onStopJob(params: JobParameters): Boolean = false
}
```

Register it in `bridge-runtime/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <service
            android:name="io.github.iamjosephmj.bridge.dispatch.BridgeJobService"
            android:permission="android.permission.BIND_JOB_SERVICE"
            android:exported="false" />
    </application>
</manifest>
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.DispatcherTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(dispatch): JobGateway (namespaced JobWorkItem enqueue) and Dispatcher"
```

---

### Task 7: BlackBox + death attribution

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/exec/BlackBox.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/exec/DeathAttributor.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/exec/DeathAttributorTest.kt`

**Interfaces:**
- Consumes: `Journal`, `WorkEvent`, `BridgeClock`.
- Produces:

```kotlin
interface BlackBox {
    fun stamp(workId: String, step: String, attempt: Int)   // "workId|step|attempt"
    fun clear()
}
class SystemBlackBox(context: Context) : BlackBox            // ActivityManager.setProcessStateSummary (API 30+; no-op below)
class FakeBlackBox : BlackBox { var last: String? }

data class ProcessDeath(val timestampMs: Long, val reason: Int, val rssKb: Long, val summary: String?)
interface ProcessDeathSource { fun recentDeaths(): List<ProcessDeath> }
class SystemProcessDeathSource(context: Context) : ProcessDeathSource   // getHistoricalProcessExitReasons (API 30+; empty below)

class DeathAttributor(journal: Journal, source: ProcessDeathSource, clock: BridgeClock) {
    fun attributeDeaths()   // RUNNING work + matching death summary → append Died; unmatched RUNNING → Stopped(STOP_REASON_UNKNOWN=-1)
}
```

- [ ] **Step 1: Write the failing attribution tests**

`DeathAttributorTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.exec

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeathAttributorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "da-${System.nanoTime()}.db", Executor { it.run() })
    private val clock = FakeClock(5000L)

    private class FakeDeathSource(val deaths: List<ProcessDeath>) : ProcessDeathSource {
        override fun recentDeaths() = deaths
    }

    private fun startWork(id: String) {
        journal.append(WorkEvent.Enqueued(id, 1L, "w", 1, 2))
        journal.append(WorkEvent.Started(id, 2L, attempt = 1, generation = 1))
    }

    @Test fun `matching death summary produces a Died event with forensics`() {
        startWork("w1")
        DeathAttributor(journal, FakeDeathSource(listOf(
            ProcessDeath(3L, reason = 3, rssKb = 380_000, summary = "w1|chunk:6|1"))), clock)
            .attributeDeaths()
        val state = journal.state("w1")!!
        assertThat(state.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(state.lastDeath!!.exitReason).isEqualTo(3)
        assertThat(state.lastDeath!!.step).isEqualTo("chunk:6")
    }

    @Test fun `running work with no matching death gets a generic Stopped`() {
        startWork("w1")
        DeathAttributor(journal, FakeDeathSource(emptyList()), clock).attributeDeaths()
        val state = journal.state("w1")!!
        assertThat(state.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(state.lastStopReason).isEqualTo(-1)
    }

    @Test fun `terminal work is untouched`() {
        startWork("w1")
        journal.append(WorkEvent.Finished("w1", 3L, success = true))
        DeathAttributor(journal, FakeDeathSource(emptyList()), clock).attributeDeaths()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.SUCCEEDED)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.DeathAttributorTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `BlackBox.kt` and `DeathAttributor.kt`**

`BlackBox.kt`:

```kotlin
package io.github.iamjosephmj.bridge.exec

import android.app.ActivityManager
import android.content.Context
import android.os.Build

interface BlackBox {
    fun stamp(workId: String, step: String, attempt: Int)
    fun clear()
}

class SystemBlackBox(context: Context) : BlackBox {
    private val am = context.getSystemService(ActivityManager::class.java)
    override fun stamp(workId: String, step: String, attempt: Int) {
        if (Build.VERSION.SDK_INT >= 30) {
            am.setProcessStateSummary("$workId|$step|$attempt".toByteArray(Charsets.UTF_8))
        }
    }
    override fun clear() {
        if (Build.VERSION.SDK_INT >= 30) am.setProcessStateSummary(null)
    }
}

class FakeBlackBox : BlackBox {
    var last: String? = null
    override fun stamp(workId: String, step: String, attempt: Int) {
        last = "$workId|$step|$attempt"
    }
    override fun clear() { last = null }
}
```

`DeathAttributor.kt`:

```kotlin
package io.github.iamjosephmj.bridge.exec

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.WorkEvent

const val STOP_REASON_UNKNOWN = -1

data class ProcessDeath(
    val timestampMs: Long, val reason: Int, val rssKb: Long, val summary: String?)

interface ProcessDeathSource { fun recentDeaths(): List<ProcessDeath> }

class SystemProcessDeathSource(private val context: Context) : ProcessDeathSource {
    override fun recentDeaths(): List<ProcessDeath> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java)
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 16).map {
            ProcessDeath(
                timestampMs = it.timestamp,
                reason = it.reason,
                rssKb = it.rss,
                summary = it.processStateSummary?.toString(Charsets.UTF_8))
        }
    }
}

class DeathAttributor(
    private val journal: Journal,
    private val source: ProcessDeathSource,
    private val clock: BridgeClock,
) {
    /** Call on init, before any new dispatch: settles work left RUNNING by a dead process. */
    fun attributeDeaths() {
        val running = journal.runningWork()
        if (running.isEmpty()) return
        val deaths = source.recentDeaths()
        for (work in running) {
            val match = deaths.firstOrNull { it.summary?.startsWith("${work.workId}|") == true }
            if (match != null) {
                val parts = match.summary!!.split("|")
                journal.append(WorkEvent.Died(
                    work.workId, clock.now(), exitReason = match.reason, rssKb = match.rssKb,
                    step = parts.getOrElse(1) { "?" },
                    attempt = parts.getOrNull(2)?.toIntOrNull() ?: work.attempt))
            } else {
                journal.append(WorkEvent.Stopped(work.workId, clock.now(), STOP_REASON_UNKNOWN))
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.DeathAttributorTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(exec): black-box death stamps and ApplicationExitInfo attribution"
```

---

### Task 8: CostMeter (HealthStats)

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/exec/CostMeter.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/exec/CostMeterTest.kt`

**Interfaces:**
- Consumes: nothing internal.
- Produces:

```kotlin
data class CostSnapshot(val cpuUserMs: Long, val cpuSystemMs: Long,
                        val txBytes: Long, val rxBytes: Long) {
    operator fun minus(earlier: CostSnapshot): CostSnapshot
}
interface CostMeter { fun snapshot(): CostSnapshot }
class HealthStatsCostMeter(context: Context) : CostMeter   // SystemHealthManager.takeMyUidSnapshot()
class FakeCostMeter(vararg snapshots: CostSnapshot) : CostMeter  // returns them in order, repeats last
```

- [ ] **Step 1: Write the failing tests**

`CostMeterTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CostMeterTest {
    @Test fun `snapshot delta is field-wise and clamped at zero`() {
        val before = CostSnapshot(cpuUserMs = 100, cpuSystemMs = 50, txBytes = 1000, rxBytes = 500)
        val after = CostSnapshot(cpuUserMs = 300, cpuSystemMs = 40, txBytes = 6000, rxBytes = 500)
        val delta = after - before
        assertThat(delta).isEqualTo(
            CostSnapshot(cpuUserMs = 200, cpuSystemMs = 0, txBytes = 5000, rxBytes = 0))
    }

    @Test fun `fake meter returns snapshots in order then repeats the last`() {
        val m = FakeCostMeter(
            CostSnapshot(1, 1, 1, 1), CostSnapshot(2, 2, 2, 2))
        assertThat(m.snapshot()).isEqualTo(CostSnapshot(1, 1, 1, 1))
        assertThat(m.snapshot()).isEqualTo(CostSnapshot(2, 2, 2, 2))
        assertThat(m.snapshot()).isEqualTo(CostSnapshot(2, 2, 2, 2))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.CostMeterTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `CostMeter.kt`**

```kotlin
package io.github.iamjosephmj.bridge.exec

import android.content.Context
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats

data class CostSnapshot(
    val cpuUserMs: Long, val cpuSystemMs: Long,
    val txBytes: Long, val rxBytes: Long,
) {
    operator fun minus(earlier: CostSnapshot) = CostSnapshot(
        (cpuUserMs - earlier.cpuUserMs).coerceAtLeast(0),
        (cpuSystemMs - earlier.cpuSystemMs).coerceAtLeast(0),
        (txBytes - earlier.txBytes).coerceAtLeast(0),
        (rxBytes - earlier.rxBytes).coerceAtLeast(0),
    )
    companion object { val ZERO = CostSnapshot(0, 0, 0, 0) }
}

interface CostMeter { fun snapshot(): CostSnapshot }

class HealthStatsCostMeter(context: Context) : CostMeter {
    private val shm = context.getSystemService(SystemHealthManager::class.java)
    override fun snapshot(): CostSnapshot = try {
        val hs = shm.takeMyUidSnapshot()
        fun m(key: Int) = if (hs.hasMeasurement(key)) hs.getMeasurement(key) else 0L
        CostSnapshot(
            cpuUserMs = m(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS),
            cpuSystemMs = m(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS),
            txBytes = m(UidHealthStats.MEASUREMENT_MOBILE_TX_BYTES) +
                m(UidHealthStats.MEASUREMENT_WIFI_TX_BYTES),
            rxBytes = m(UidHealthStats.MEASUREMENT_MOBILE_RX_BYTES) +
                m(UidHealthStats.MEASUREMENT_WIFI_RX_BYTES),
        )
    } catch (e: Exception) { CostSnapshot.ZERO }   // some OEMs throw from batterystats parceling
}

class FakeCostMeter(private vararg val snapshots: CostSnapshot) : CostMeter {
    private var i = 0
    override fun snapshot(): CostSnapshot =
        snapshots[minOf(i++, snapshots.size - 1)]
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.CostMeterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(exec): HealthStats cost meter with snapshot deltas"
```

---

### Task 9: WorkRunner (leased execution, chunk resume, retry decisions)

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/exec/WorkRunner.kt`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/exec/WorkRunnerTest.kt`

**Interfaces:**
- Consumes: `Journal`, `WorkerRegistry`, `BridgeWorker`, `ChunkedWorker`, `RunContext`, `RunResult`, `BlackBox`, `CostMeter`, `BridgeClock`, fold state.
- Produces:

```kotlin
enum class RunOutcome { COMPLETED, FAILED, RETRY }   // COMPLETED/FAILED → completeWork(); RETRY → leave item for redelivery
class WorkRunner(journal: Journal, registry: WorkerRegistry, blackBox: BlackBox,
                 costMeter: CostMeter, clock: BridgeClock) {
    suspend fun run(workId: String, generation: Int, deliveryCount: Int,
                    isStopped: () -> Boolean): RunOutcome
}
```

Behavior contract (each clause is a test):
1. Stale generation or non-live state → `COMPLETED` (drop silently, journal nothing).
2. Journals `Started(attempt = deliveryCount)`; stamps black box before running; clears after.
3. Plain worker: `Success` → `Finished(success=true)` with cost delta → `COMPLETED`; `Failure` → `Finished(success=false)` → `FAILED`.
4. `Retry` or thrown exception: if `deliveryCount >= maxAttempts` → `Finished(success=false)` → `FAILED`; else `Stopped(stopReason=0)` → `RETRY`.
5. Chunked worker: runs `runChunk` from `state.nextChunk` to `chunkCount-1`, journaling `ChunkCompleted` after each; honors `isStopped()` between chunks (→ `Stopped(1)` + `RETRY`); completing last chunk → `Finished(success=true)`.

- [ ] **Step 1: Write the failing runner tests**

`WorkRunnerTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.exec

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.store.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkRunnerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "r-${System.nanoTime()}.db", Executor { it.run() })
    private val registry = WorkerRegistry()
    private val blackBox = FakeBlackBox()
    private val clock = FakeClock(100L)
    private fun runner(meter: CostMeter = FakeCostMeter(CostSnapshot.ZERO)) =
        WorkRunner(journal, registry, blackBox, meter, clock)

    private fun enqueue(id: String = "w1", worker: String = "ok",
                        chunks: Int = 0, maxAttempts: Int = 3) {
        journal.append(WorkEvent.Enqueued(id, 1L, worker, generation = 1,
            importance = 2, chunkCount = chunks, maxAttempts = maxAttempts))
    }

    @Test fun `success journals Finished with measured cost`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue()
        val meter = FakeCostMeter(
            CostSnapshot(100, 10, 0, 0), CostSnapshot(400, 60, 5000, 0))
        val outcome = runner(meter).run("w1", 1, deliveryCount = 1) { false }
        assertThat(outcome).isEqualTo(RunOutcome.COMPLETED)
        val fin = journal.events("w1").filterIsInstance<WorkEvent.Finished>().single()
        assertThat(fin.success).isTrue()
        assertThat(fin.cpuUserMs).isEqualTo(300)
        assertThat(fin.txBytes).isEqualTo(5000)
        assertThat(blackBox.last).isNull()   // cleared after run
    }

    @Test fun `stale generation is dropped without journaling`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue()
        val outcome = runner().run("w1", generation = 99, deliveryCount = 1) { false }
        assertThat(outcome).isEqualTo(RunOutcome.COMPLETED)
        assertThat(journal.events("w1").filterIsInstance<WorkEvent.Started>()).isEmpty()
    }

    @Test fun `retry below maxAttempts yields RETRY, at maxAttempts fails permanently`() = runTest {
        registry.register("flaky") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Retry } }
        enqueue(worker = "flaky", maxAttempts = 2)
        assertThat(runner().run("w1", 1, deliveryCount = 1) { false })
            .isEqualTo(RunOutcome.RETRY)
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.ENQUEUED)
        assertThat(runner().run("w1", 1, deliveryCount = 2) { false })
            .isEqualTo(RunOutcome.FAILED)
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.FAILED)
    }

    @Test fun `worker exception counts as retryable`() = runTest {
        registry.register("boom") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext): RunResult = error("boom") } }
        enqueue(worker = "boom")
        assertThat(runner().run("w1", 1, deliveryCount = 1) { false })
            .isEqualTo(RunOutcome.RETRY)
    }

    @Test fun `chunked worker resumes from nextChunk and completes`() = runTest {
        val ran = mutableListOf<Int>()
        registry.register("chunky") { object : ChunkedWorker {
            override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
                ran += chunkIndex; return RunResult.Success
            } } }
        enqueue(worker = "chunky", chunks = 5)
        journal.append(WorkEvent.Started("w1", 2L, 1, 1))
        journal.append(WorkEvent.ChunkCompleted("w1", 3L, 0))
        journal.append(WorkEvent.ChunkCompleted("w1", 4L, 1))
        journal.append(WorkEvent.Stopped("w1", 5L, 10))
        val outcome = runner().run("w1", 1, deliveryCount = 2) { false }
        assertThat(outcome).isEqualTo(RunOutcome.COMPLETED)
        assertThat(ran).containsExactly(2, 3, 4).inOrder()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `stop signal between chunks yields RETRY preserving progress`() = runTest {
        var calls = 0
        registry.register("chunky") { object : ChunkedWorker {
            override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
                calls++; return RunResult.Success
            } } }
        enqueue(worker = "chunky", chunks = 10)
        val outcome = runner().run("w1", 1, deliveryCount = 1) { calls >= 3 }
        assertThat(outcome).isEqualTo(RunOutcome.RETRY)
        assertThat(journal.state("w1")!!.nextChunk).isEqualTo(3)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.WorkRunnerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `WorkRunner.kt`**

```kotlin
package io.github.iamjosephmj.bridge.exec

import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.ChunkedWorker
import io.github.iamjosephmj.bridge.api.RunContext
import io.github.iamjosephmj.bridge.api.RunResult
import io.github.iamjosephmj.bridge.api.WorkerRegistry
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent

enum class RunOutcome { COMPLETED, FAILED, RETRY }

private const val STOP_REASON_RETRY = 0
private const val STOP_REASON_SYSTEM_STOP = 1

class WorkRunner(
    private val journal: Journal,
    private val registry: WorkerRegistry,
    private val blackBox: BlackBox,
    private val costMeter: CostMeter,
    private val clock: BridgeClock,
) {
    suspend fun run(workId: String, generation: Int, deliveryCount: Int,
                    isStopped: () -> Boolean): RunOutcome {
        val state = journal.state(workId) ?: return RunOutcome.COMPLETED
        if (state.generation != generation) return RunOutcome.COMPLETED
        if (state.runState !in setOf(RunState.ENQUEUED, RunState.DISPATCHED)) {
            return RunOutcome.COMPLETED
        }

        journal.append(WorkEvent.Started(workId, clock.now(), deliveryCount, generation))
        val before = costMeter.snapshot()
        val ctx = RunContext(workId, deliveryCount, isStopped)
        val worker = try { registry.create(state.workerName) } catch (e: IllegalArgumentException) {
            journal.append(WorkEvent.Finished(workId, clock.now(), success = false))
            return RunOutcome.FAILED
        }

        try {
            val result: RunResult = if (state.chunkCount > 0 && worker is ChunkedWorker) {
                runChunked(worker, ctx, workId, state.nextChunk, state.chunkCount, isStopped)
            } else {
                blackBox.stamp(workId, "run", deliveryCount)
                worker.run(ctx)
            }
            return when (result) {
                is RunResult.Success -> finish(workId, before, success = true)
                    .let { RunOutcome.COMPLETED }
                is RunResult.Failure -> finish(workId, before, success = false)
                    .let { RunOutcome.FAILED }
                is RunResult.Retry -> retryOrFail(workId, deliveryCount, state.maxAttempts, before)
            }
        } catch (e: Exception) {
            return retryOrFail(workId, deliveryCount, state.maxAttempts, before)
        } finally {
            blackBox.clear()
        }
    }

    /** Returns Success when all chunks done, Retry when stopped mid-way, Failure/Retry per chunk result. */
    private suspend fun runChunked(worker: ChunkedWorker, ctx: RunContext, workId: String,
                                   fromChunk: Int, chunkCount: Int,
                                   isStopped: () -> Boolean): RunResult {
        for (idx in fromChunk until chunkCount) {
            if (isStopped()) return RunResult.Retry
            blackBox.stamp(workId, "chunk:$idx", ctx.attempt)
            when (val r = worker.runChunk(ctx, idx)) {
                is RunResult.Success ->
                    journal.append(WorkEvent.ChunkCompleted(workId, clock.now(), idx))
                else -> return r
            }
        }
        return RunResult.Success
    }

    private fun finish(workId: String, before: CostSnapshot, success: Boolean) {
        val cost = costMeter.snapshot() - before
        journal.append(WorkEvent.Finished(workId, clock.now(), success,
            cpuUserMs = cost.cpuUserMs, cpuSystemMs = cost.cpuSystemMs,
            txBytes = cost.txBytes, rxBytes = cost.rxBytes))
    }

    private fun retryOrFail(workId: String, deliveryCount: Int, maxAttempts: Int,
                            before: CostSnapshot): RunOutcome =
        if (deliveryCount >= maxAttempts) {
            finish(workId, before, success = false); RunOutcome.FAILED
        } else {
            journal.append(WorkEvent.Stopped(workId, clock.now(), STOP_REASON_RETRY))
            RunOutcome.RETRY
        }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.WorkRunnerTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(exec): WorkRunner with chunk-exact resume, retry policy, cost deltas"
```

---

### Task 10: BridgeJobService (dequeueWork loop)

**Files:**
- Modify: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/BridgeJobService.kt` (replace Task 6 stub)
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/dispatch/BridgeJobServiceTest.kt`

**Interfaces:**
- Consumes: `WorkRunner.run(workId, generation, deliveryCount, isStopped)`, `RunOutcome`, `EXTRA_WORK_ID`, `EXTRA_GENERATION`, `BridgeRuntime` (Task 11 provides the real singleton; this task defines the seam).
- Produces:

```kotlin
// Seam so the service is testable and Task 11 can wire the real graph:
object BridgeServices {
    lateinit var runner: WorkRunner
    var isInitialized: Boolean
}
class BridgeJobService : JobService()   // real dequeue loop
```

Service behavior: `onStartJob` launches a coroutine (`Dispatchers.Default` + `SupervisorJob`); loop `params.dequeueWork()`; for each item read extras, call `runner.run(...)`, `completeWork` on COMPLETED/FAILED, **do not** complete on RETRY (leave for redelivery) and stop draining; call `jobFinished(params, wantsReschedule = hadRetry)` when the queue drains; `onStopJob` cancels the scope and returns `true` (reschedule). A `stopped` `AtomicBoolean` feeds `isStopped()`.

Direct JobService unit-testing is awkward under Robolectric; extract the loop into a testable class and keep the service thin:

```kotlin
class WorkQueueDrainer(private val runner: WorkRunner, private val scope: CoroutineScope) {
    /** Returns wantsReschedule. dequeue returns (payload, deliveryCount) or null. */
    suspend fun drain(dequeue: () -> Pair<WorkItemPayload, Int>?,
                      complete: (WorkItemPayload) -> Unit,
                      isStopped: () -> Boolean): Boolean
}
```

- [ ] **Step 1: Write the failing drainer tests**

`BridgeJobServiceTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.FakeClock
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.exec.*
import io.github.iamjosephmj.bridge.store.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeJobServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val journal = Journal(context, "js-${System.nanoTime()}.db", Executor { it.run() })
    private val registry = WorkerRegistry()
    private val runner = WorkRunner(journal, registry, FakeBlackBox(),
        FakeCostMeter(CostSnapshot.ZERO), FakeClock(10L))

    private fun enqueue(id: String, worker: String) {
        journal.append(WorkEvent.Enqueued(id, 1L, worker, 1, 2))
    }

    @Test fun `drains queue, completes successful items, no reschedule`() = runTest {
        registry.register("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        enqueue("w1", "ok"); enqueue("w2", "ok")
        val queue = ArrayDeque(listOf(
            WorkItemPayload("w1", 1) to 1, WorkItemPayload("w2", 1) to 1))
        val completed = mutableListOf<String>()
        val wantsReschedule = WorkQueueDrainer(runner, this).drain(
            dequeue = { queue.removeFirstOrNull() },
            complete = { completed += it.workId },
            isStopped = { false })
        assertThat(completed).containsExactly("w1", "w2")
        assertThat(wantsReschedule).isFalse()
        assertThat(journal.state("w1")!!.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `RETRY outcome leaves item uncompleted and requests reschedule`() = runTest {
        registry.register("flaky") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Retry } }
        enqueue("w1", "flaky")
        val queue = ArrayDeque(listOf(WorkItemPayload("w1", 1) to 1))
        val completed = mutableListOf<String>()
        val wantsReschedule = WorkQueueDrainer(runner, this).drain(
            dequeue = { queue.removeFirstOrNull() },
            complete = { completed += it.workId },
            isStopped = { false })
        assertThat(completed).isEmpty()
        assertThat(wantsReschedule).isTrue()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.BridgeJobServiceTest"`
Expected: FAIL (unresolved `WorkQueueDrainer`).

- [ ] **Step 3: Implement drainer + real service**

Replace `BridgeJobService.kt` entirely:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.app.job.JobParameters
import android.app.job.JobService
import io.github.iamjosephmj.bridge.exec.RunOutcome
import io.github.iamjosephmj.bridge.exec.WorkRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Wiring seam: Bridge.initialize() (Task 11) populates this before any job can run. */
object BridgeServices {
    @Volatile var runner: WorkRunner? = null
}

class WorkQueueDrainer(
    private val runner: WorkRunner,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    suspend fun drain(
        dequeue: () -> Pair<WorkItemPayload, Int>?,
        complete: (WorkItemPayload) -> Unit,
        isStopped: () -> Boolean,
    ): Boolean {
        var wantsReschedule = false
        while (!isStopped()) {
            val (payload, deliveryCount) = dequeue() ?: break
            when (runner.run(payload.workId, payload.generation, deliveryCount, isStopped)) {
                RunOutcome.COMPLETED, RunOutcome.FAILED -> complete(payload)
                RunOutcome.RETRY -> { wantsReschedule = true; break }
            }
        }
        return wantsReschedule
    }
}

class BridgeJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopped = AtomicBoolean(false)

    override fun onStartJob(params: JobParameters): Boolean {
        val runner = BridgeServices.runner ?: return false   // not initialized; drop
        stopped.set(false)
        scope.launch {
            val wantsReschedule = WorkQueueDrainer(runner, scope).drain(
                dequeue = {
                    val item = try { params.dequeueWork() } catch (e: Exception) { null }
                    item?.let {
                        val intent = it.intent
                        WorkItemPayload(
                            intent.getStringExtra(EXTRA_WORK_ID) ?: return@let null,
                            intent.getIntExtra(EXTRA_GENERATION, 0),
                        ) to it.deliveryCount
                    }
                },
                complete = { payload ->
                    // completeWork needs the original JobWorkItem; track it via a map
                    // maintained in the dequeue lambda (see below).
                    completePending(params, payload)
                },
                isStopped = { stopped.get() })
            if (!stopped.get()) jobFinished(params, wantsReschedule)
        }
        return true
    }

    // dequeueWork returns JobWorkItem; we must pass the same instance to completeWork.
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, android.app.job.JobWorkItem>()

    private fun completePending(params: JobParameters, payload: WorkItemPayload) {
        inFlight.remove(payload.workId)?.let {
            try { params.completeWork(it) } catch (e: Exception) { /* job already gone */ }
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true)
        return true   // reschedule: undelivered items redeliver with bumped deliveryCount
    }
}
```

Adjust `onStartJob`'s dequeue lambda to record `inFlight[workId] = item` before returning the payload (single code path — implement it directly in the lambda where the item is unwrapped):

```kotlin
dequeue = {
    val item = try { params.dequeueWork() } catch (e: Exception) { null }
    if (item == null) null else {
        val workId = item.intent.getStringExtra(EXTRA_WORK_ID)
        if (workId == null) { try { params.completeWork(item) } catch (e: Exception) {}; null }
        else {
            inFlight[workId] = item
            WorkItemPayload(workId, item.intent.getIntExtra(EXTRA_GENERATION, 0)) to item.deliveryCount
        }
    }
},
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.BridgeJobServiceTest"`
Expected: PASS (2 tests). Also run the full suite: `./gradlew :bridge-runtime:testDebugUnitTest` — all green.

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(dispatch): BridgeJobService with testable dequeueWork drain loop"
```

---

### Task 11: Reconciler + Bridge facade + receivers

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/Reconciler.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/Bridge.kt`
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/BridgeReceivers.kt`
- Modify: `bridge-runtime/src/main/AndroidManifest.xml`
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/BridgeFacadeTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces (the public v0.1 surface):

```kotlin
class ForceStopDetector(context: Context) { fun wasForceStoppedOrFirstRun(): Boolean }  // sentinel PendingIntent pattern
class Reconciler(journal, dispatcher, deathAttributor, forceStopDetector) { fun reconcile() }

class BridgeConfig internal constructor(...)   // built via BridgeConfigBuilder
object Bridge {
    fun initialize(context: Context, block: BridgeConfigBuilder.() -> Unit)  // idempotent
    fun enqueue(request: WorkRequest): String            // returns workId; unique-by-name KEEP semantics
    fun state(name: String): WorkState?
    fun events(name: String): List<WorkEvent>
    fun cancel(name: String)
    internal fun reset()                                  // test-only teardown
}
class BridgeConfigBuilder {
    fun worker(name: String, factory: () -> BridgeWorker)
    // internal overrides for tests:
    internal var clock: BridgeClock; internal var gateway: JobGateway?
    internal var costMeter: CostMeter?; internal var deathSource: ProcessDeathSource?
    internal var ioExecutor: Executor?
}
```

Semantics: `enqueue` uses the request `name` as the workId. If live work with that name exists → KEEP (return existing id, no new events). If terminal/absent → append `Enqueued` with `generation = (previous generation) + 1` and dispatch immediately. `initialize` runs: death attribution → force-stop reconciliation (if detected: `Stopped(FORCE_STOP=2)` for RUNNING/DISPATCHED work, `gateway.cancelAll()`) → `dispatchAll()`. Receivers (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) call `Bridge.reconcileIfInitialized()`.

- [ ] **Step 1: Write the failing facade tests**

`BridgeFacadeTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.dispatch.FakeJobGateway
import io.github.iamjosephmj.bridge.store.RunState
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeFacadeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeJobGateway()

    @After fun tearDown() = Bridge.reset()

    private fun init() = Bridge.initialize(context) {
        worker("ok") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success } }
        clock = FakeClock(1L)
        this.gateway = this@BridgeFacadeTest.gateway
        ioExecutor = Executor { it.run() }
        dbName = "f-${System.nanoTime()}.db"
    }

    @Test fun `enqueue journals and dispatches immediately`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(Bridge.state("sync")!!.runState).isEqualTo(RunState.DISPATCHED)
        assertThat(gateway.enqueued.single().second.workId).isEqualTo("sync")
    }

    @Test fun `enqueue with same name keeps existing live work`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(gateway.enqueued).hasSize(1)
        assertThat(Bridge.state("sync")!!.generation).isEqualTo(1)
    }

    @Test fun `cancel makes work CANCELLED`() {
        init()
        Bridge.enqueue(workRequest("sync", "ok"))
        Bridge.cancel("sync")
        assertThat(Bridge.state("sync")!!.runState).isEqualTo(RunState.CANCELLED)
    }

    @Test fun `initialize is idempotent`() {
        init(); init()
        Bridge.enqueue(workRequest("sync", "ok"))
        assertThat(gateway.enqueued).hasSize(1)
    }
}
```

(Add `internal var dbName: String` to the builder so tests isolate databases.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.BridgeFacadeTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `ForceStopDetector`/`Reconciler`, `Bridge`, receivers**

`Reconciler.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.iamjosephmj.bridge.BridgeClock
import io.github.iamjosephmj.bridge.exec.DeathAttributor
import io.github.iamjosephmj.bridge.store.Journal
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent

const val STOP_REASON_FORCE_STOP = 2

/** WorkManager's ForceStopRunnable pattern: a sentinel broadcast PendingIntent the OS
 *  wipes on force-stop. Missing sentinel => force-stop (or first run) happened. */
class ForceStopDetector(private val context: Context) {
    private val intent = Intent("io.github.iamjosephmj.bridge.SENTINEL")
        .setPackage(context.packageName)

    fun wasForceStoppedOrFirstRun(): Boolean {
        val existing = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (existing != null) return false
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return true
    }
}

class Reconciler(
    private val journal: Journal,
    private val dispatcher: Dispatcher,
    private val deathAttributor: DeathAttributor,
    private val forceStopDetector: ForceStopDetector,
    private val gateway: JobGateway,
    private val clock: BridgeClock,
) {
    fun reconcile() {
        deathAttributor.attributeDeaths()
        if (forceStopDetector.wasForceStoppedOrFirstRun()) {
            gateway.cancelAll()
            journal.liveWork()
                .filter { it.runState == RunState.DISPATCHED || it.runState == RunState.RUNNING }
                .forEach {
                    journal.append(WorkEvent.Stopped(it.workId, clock.now(), STOP_REASON_FORCE_STOP))
                }
        }
        dispatcher.dispatchAll()
    }
}
```

`Bridge.kt`:

```kotlin
package io.github.iamjosephmj.bridge

import android.content.Context
import io.github.iamjosephmj.bridge.api.WorkRequest
import io.github.iamjosephmj.bridge.api.BridgeWorker
import io.github.iamjosephmj.bridge.api.WorkerRegistry
import io.github.iamjosephmj.bridge.dispatch.*
import io.github.iamjosephmj.bridge.exec.*
import io.github.iamjosephmj.bridge.store.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class BridgeConfigBuilder internal constructor() {
    internal val registry = WorkerRegistry()
    internal var clock: BridgeClock = SystemBridgeClock()
    internal var gateway: JobGateway? = null
    internal var costMeter: CostMeter? = null
    internal var deathSource: ProcessDeathSource? = null
    internal var ioExecutor: Executor? = null
    internal var dbName: String = "bridge.db"

    fun worker(name: String, factory: () -> BridgeWorker) = registry.register(name, factory)
}

object Bridge {
    private var journal: Journal? = null
    private var dispatcher: Dispatcher? = null
    private var clock: BridgeClock = SystemBridgeClock()

    @Synchronized
    fun initialize(context: Context, block: BridgeConfigBuilder.() -> Unit) {
        if (journal != null) return   // idempotent
        val b = BridgeConfigBuilder().apply(block)
        val appContext = context.applicationContext
        clock = b.clock
        val j = Journal(appContext, b.dbName,
            b.ioExecutor ?: Executors.newSingleThreadExecutor())
        val gw = b.gateway ?: SystemJobGateway(appContext)
        val d = Dispatcher(j, gw, b.clock)
        val runner = WorkRunner(j, b.registry, SystemBlackBox(appContext),
            b.costMeter ?: HealthStatsCostMeter(appContext), b.clock)
        BridgeServices.runner = runner
        journal = j; dispatcher = d
        Reconciler(j, d,
            DeathAttributor(j, b.deathSource ?: SystemProcessDeathSource(appContext), b.clock),
            ForceStopDetector(appContext), gw, b.clock).reconcile()
    }

    fun enqueue(request: WorkRequest): String {
        val j = requireNotNull(journal) { "Bridge.initialize() not called" }
        val existing = j.state(request.name)
        if (existing != null && existing.runState in
            setOf(RunState.ENQUEUED, RunState.DISPATCHED, RunState.RUNNING)) {
            return request.name   // KEEP
        }
        val generation = (existing?.generation ?: 0) + 1
        j.append(WorkEvent.Enqueued(request.name, clock.now(), request.workerName, generation,
            importance = request.importance.ordinal,
            requiresCharging = request.requiresCharging,
            requiresUnmetered = request.requiresUnmetered,
            chunkCount = request.chunkCount,
            estimatedUpBytes = request.estimatedUpBytes,
            maxAttempts = request.maxAttempts))
        dispatcher!!.dispatch(request.name)
        return request.name
    }

    fun state(name: String): WorkState? = journal?.state(name)
    fun events(name: String): List<WorkEvent> = journal?.events(name) ?: emptyList()

    fun cancel(name: String) {
        journal?.append(WorkEvent.Cancelled(name, clock.now()))
    }

    fun reconcileIfInitialized() { dispatcher?.dispatchAll() }

    @Synchronized
    internal fun reset() {
        journal?.close(); journal = null; dispatcher = null
        BridgeServices.runner = null
    }
}
```

`BridgeReceivers.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iamjosephmj.bridge.Bridge

class BridgeRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // App process was started for this broadcast; the app's Bridge.initialize()
        // (Application.onCreate) has already reconciled. This nudges dispatch anyway.
        Bridge.reconcileIfInitialized()
    }
}
```

Manifest additions inside `<application>`:

```xml
<receiver android:name="io.github.iamjosephmj.bridge.dispatch.BridgeRescheduleReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

And before `<application>`:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest`
Expected: full suite PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat: Bridge facade, force-stop reconciler, boot/update receivers"
```

---

### Task 12: Conformance self-test + 1:1 fallback dispatch

**Files:**
- Create: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/Conformance.kt`
- Modify: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/dispatch/JobGateway.kt` (add `OneToOneJobGateway`)
- Modify: `bridge-runtime/src/main/java/io/github/iamjosephmj/bridge/Bridge.kt` (gateway selection)
- Test: `bridge-runtime/src/test/java/io/github/iamjosephmj/bridge/dispatch/ConformanceTest.kt`

**Interfaces:**
- Consumes: `JobGateway`, `SystemJobGateway`, `HostJobClass`, `JobPlanCompiler`.
- Produces:

```kotlin
enum class DispatchMode { MULTIPLEXED, ONE_TO_ONE }
class Conformance(prefs: SharedPreferences) {
    var mode: DispatchMode                       // persisted; default MULTIPLEXED
    fun recordEnqueueFailure()                   // 3 consecutive failures → ONE_TO_ONE
    fun recordEnqueueSuccess()                   // resets the counter
}
class OneToOneJobGateway(context: Context) : JobGateway
    // one JobInfo per work item: jobId = 720_000 + stable hash of workId,
    // extras carry workId/generation, constraints compiled from the host class profile
class SelectingJobGateway(system: JobGateway, oneToOne: JobGateway,
                          conformance: Conformance) : JobGateway
    // routes by mode; reports success/failure to Conformance
```

`BridgeJobService.onStartJob` handles both delivery styles: if `params.dequeueWork()` throws or returns null on first call AND the job's extras carry `EXTRA_WORK_ID`, treat it as a 1:1 job (run that single item, `jobFinished` with reschedule on RETRY).

- [ ] **Step 1: Write the failing conformance tests**

`ConformanceTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun conformance() = Conformance(
        context.getSharedPreferences("c-${System.nanoTime()}", Context.MODE_PRIVATE))

    @Test fun `defaults to MULTIPLEXED`() {
        assertThat(conformance().mode).isEqualTo(DispatchMode.MULTIPLEXED)
    }

    @Test fun `three consecutive failures flips to ONE_TO_ONE and persists`() {
        val prefs = context.getSharedPreferences("p", Context.MODE_PRIVATE)
        val c = Conformance(prefs)
        repeat(3) { c.recordEnqueueFailure() }
        assertThat(c.mode).isEqualTo(DispatchMode.ONE_TO_ONE)
        assertThat(Conformance(prefs).mode).isEqualTo(DispatchMode.ONE_TO_ONE)
    }

    @Test fun `success resets the failure streak`() {
        val c = conformance()
        c.recordEnqueueFailure(); c.recordEnqueueFailure()
        c.recordEnqueueSuccess()
        c.recordEnqueueFailure(); c.recordEnqueueFailure()
        assertThat(c.mode).isEqualTo(DispatchMode.MULTIPLEXED)
    }

    @Test fun `selecting gateway falls back after failures`() {
        val failing = object : JobGateway {
            override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload) = false
            override fun cancelAll() {}
        }
        val fallback = FakeJobGateway()
        val sel = SelectingJobGateway(failing, fallback, conformance())
        repeat(3) { sel.enqueue(HostJobClass.DEFAULT, WorkItemPayload("w$it", 1)) }
        sel.enqueue(HostJobClass.DEFAULT, WorkItemPayload("w9", 1))
        assertThat(fallback.enqueued.map { it.second.workId }).containsExactly("w9")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :bridge-runtime:testDebugUnitTest --tests "*.ConformanceTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `Conformance.kt` + `OneToOneJobGateway` + wiring**

`Conformance.kt`:

```kotlin
package io.github.iamjosephmj.bridge.dispatch

import android.content.SharedPreferences

enum class DispatchMode { MULTIPLEXED, ONE_TO_ONE }

class Conformance(private val prefs: SharedPreferences) {
    var mode: DispatchMode
        get() = DispatchMode.valueOf(
            prefs.getString("mode", DispatchMode.MULTIPLEXED.name)!!)
        private set(value) { prefs.edit().putString("mode", value.name).apply() }

    private var failures: Int
        get() = prefs.getInt("failures", 0)
        set(value) { prefs.edit().putInt("failures", value).apply() }

    fun recordEnqueueFailure() {
        failures += 1
        if (failures >= 3) mode = DispatchMode.ONE_TO_ONE
    }

    fun recordEnqueueSuccess() { failures = 0 }
}

class SelectingJobGateway(
    private val multiplexed: JobGateway,
    private val oneToOne: JobGateway,
    private val conformance: Conformance,
) : JobGateway {
    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        if (conformance.mode == DispatchMode.ONE_TO_ONE) {
            return oneToOne.enqueue(hostClass, payload)
        }
        val ok = multiplexed.enqueue(hostClass, payload)
        if (ok) conformance.recordEnqueueSuccess() else conformance.recordEnqueueFailure()
        // After the flip, retry this payload on the fallback path immediately.
        return if (!ok && conformance.mode == DispatchMode.ONE_TO_ONE) {
            oneToOne.enqueue(hostClass, payload)
        } else ok
    }
    override fun cancelAll() { multiplexed.cancelAll(); oneToOne.cancelAll() }
}
```

Add to `JobGateway.kt`:

```kotlin
class OneToOneJobGateway(private val context: Context) : JobGateway {
    private val scheduler: JobScheduler = run {
        val js = context.getSystemService(JobScheduler::class.java)
        if (Build.VERSION.SDK_INT >= 34) js.forNamespace("bridge-1to1") else js
    }
    private val component = ComponentName(context, BridgeJobService::class.java)

    override fun enqueue(hostClass: HostJobClass, payload: WorkItemPayload): Boolean {
        val base = JobPlanCompiler.jobInfo(context, hostClass, component)
        val extras = android.os.PersistableBundle().apply {
            putString(EXTRA_WORK_ID, payload.workId)
            putInt(EXTRA_GENERATION, payload.generation)
        }
        val info = JobInfo.Builder(720_000 + (payload.workId.hashCode() and 0xFFFF), component)
            .setRequiredNetworkType(base.networkType)
            .setRequiresCharging(base.isRequireCharging)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(extras)
            .build()
        return try { scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS }
        catch (e: Exception) { false }
    }

    override fun cancelAll() { scheduler.cancelAll() }
}
```

In `Bridge.initialize`, replace the gateway line:

```kotlin
val gw = b.gateway ?: SelectingJobGateway(
    SystemJobGateway(appContext),
    OneToOneJobGateway(appContext),
    Conformance(appContext.getSharedPreferences("bridge.conformance", Context.MODE_PRIVATE)))
```

In `BridgeJobService.onStartJob`, before the drain loop, add the 1:1 path:

```kotlin
val oneToOneWorkId = params.extras.getString(EXTRA_WORK_ID)
if (oneToOneWorkId != null) {
    scope.launch {
        val outcome = runner.run(oneToOneWorkId,
            params.extras.getInt(EXTRA_GENERATION, 0),
            deliveryCount = 1, isStopped = { stopped.get() })
        if (!stopped.get()) jobFinished(params, outcome == RunOutcome.RETRY)
    }
    return true
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :bridge-runtime:testDebugUnitTest`
Expected: full suite PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge-runtime/src
git commit -m "feat(dispatch): conformance-driven fallback from multiplexed to 1:1 jobs"
```

---

### Task 13: Instrumented smoke test (real JobScheduler, end to end)

**Files:**
- Create: `bridge-runtime/src/androidTest/java/io/github/iamjosephmj/bridge/EndToEndTest.kt`
- Modify: `bridge-runtime/build.gradle.kts` (androidTest deps + runner)

**Interfaces:**
- Consumes: full `Bridge` surface.
- Produces: nothing new — validates real `enqueue(JobInfo, JobWorkItem)` → `BridgeJobService` → `WorkRunner` on a device/emulator. This is the seed of the spec's device conformance suite.

- [ ] **Step 1: Add androidTest configuration**

In `bridge-runtime/build.gradle.kts` `android {}` block add:

```kotlin
defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}
```

Dependencies:

```kotlin
androidTestImplementation(libs.androidx.junit)
androidTestImplementation(libs.truth)
androidTestImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: Write the smoke test**

`EndToEndTest.kt`:

```kotlin
package io.github.iamjosephmj.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.api.*
import io.github.iamjosephmj.bridge.store.RunState
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    @Test fun unconstrained_work_executes_via_real_jobscheduler() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        Bridge.initialize(context) {
            worker("smoke") { object : BridgeWorker {
                override suspend fun run(ctx: RunContext): RunResult {
                    latch.countDown(); return RunResult.Success
                } } }
        }
        Bridge.enqueue(workRequest("smoke-${System.currentTimeMillis()}", "smoke"))
        // Unconstrained DEFAULT host job should run promptly on an unthrottled test device.
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue()
    }

    @Test fun chunked_work_records_progress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(5)
        Bridge.initialize(context) {
            worker("chunky") { object : ChunkedWorker {
                override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
                    latch.countDown(); return RunResult.Success
                } } }
        }
        val name = "chunky-${System.currentTimeMillis()}"
        Bridge.enqueue(workRequest(name, "chunky") { chunks(count = 5) })
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue()
        // Poll briefly for the terminal state (Finished lands just after the last chunk).
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
               Bridge.state(name)?.runState != RunState.SUCCEEDED) Thread.sleep(200)
        assertThat(Bridge.state(name)!!.runState).isEqualTo(RunState.SUCCEEDED)
        assertThat(Bridge.state(name)!!.nextChunk).isEqualTo(5)
    }
}
```

- [ ] **Step 3: Run on an emulator**

Run: `./gradlew :bridge-runtime:connectedDebugAndroidTest` (requires a running emulator/device; charge state normal, screen on to avoid throttling).
Expected: PASS. If `enqueue` behaves unexpectedly on the chosen emulator image, that is *signal* (conformance!), not test noise — investigate before proceeding.

- [ ] **Step 4: Commit**

```bash
git add bridge-runtime
git commit -m "test: instrumented end-to-end smoke via real JobScheduler"
```

---

### Task 14: `:bench` module — corpus, backends, JSON report

**Files:**
- Modify: `settings.gradle.kts` (add `include(":bench")`)
- Create: `bench/build.gradle.kts`
- Create: `bench/src/main/AndroidManifest.xml`
- Create: `bench/src/main/java/io/github/iamjosephmj/bench/Corpus.kt`
- Create: `bench/src/main/java/io/github/iamjosephmj/bench/Backend.kt`
- Create: `bench/src/main/java/io/github/iamjosephmj/bench/BridgeBackend.kt`
- Create: `bench/src/main/java/io/github/iamjosephmj/bench/WorkManagerBackend.kt`
- Create: `bench/src/main/java/io/github/iamjosephmj/bench/Report.kt`
- Create: `bench/src/main/java/io/github/iamjosephmj/bench/BenchApp.kt`
- Create: `bench/src/main/java/io/github/iamjosephmj/bench/BenchReceiver.kt`
- Test: `bench/src/test/java/io/github/iamjosephmj/bench/ReportTest.kt`

**Interfaces:**
- Consumes: `Bridge` public surface; `androidx.work` 2.10.0.
- Produces:

```kotlin
data class CorpusItem(val id: String, val kind: Kind, val constraintProfile: Profile) {
    enum class Kind { PING, MEDIUM_SYNC, LARGE_CHUNKED }         // 4 KB / 5 MB / 200 MB-simulated
    enum class Profile { NONE, UNMETERED_CHARGING }
}
val CORPUS: List<CorpusItem>          // 6 items: each kind × each profile

data class RunRecord(val itemId: String, val backend: String,
    val enqueuedAt: Long, val firstStartAt: Long?, val completedAt: Long?,
    val attempts: Int, val chunksReplayed: Int)   // chunksReplayed = re-executed chunk count
interface Backend {
    val name: String
    fun enqueueAll(items: List<CorpusItem>)
    fun collect(): List<RunRecord>
}
object Report { fun toJson(records: List<RunRecord>, deviceInfo: Map<String, String>): String }
```

Workers on both backends simulate transfer by writing N bytes to a scratch file and sleeping proportionally (deterministic, network-free — the bench measures *scheduling*, not bandwidth). Bridge backend reads `RunRecord` fields from `Bridge.events()`; WorkManager backend records timestamps itself into a shared SQLite table via a static recorder (WorkManager exposes no run history — that asymmetry is the point; note it in the report as `source: "self-instrumented"`).

`BenchReceiver` (exported, guarded by `android:permission="android.permission.DUMP"`... no — keep simple: exported=false and trigger via `am broadcast --receiver-foreground -n`): actions `bench.ENQUEUE_BRIDGE`, `bench.ENQUEUE_WM`, `bench.DUMP_REPORT` (writes JSON to `context.getExternalFilesDir(null)/report-<backend>-<ts>.json` and logs the path).

- [ ] **Step 1: Module scaffolding**

`bench/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.iamjosephmj.bench"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.iamjosephmj.bench"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation(project(":bridge-runtime"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

`settings.gradle.kts`: add `include(":bench")`.

- [ ] **Step 2: Write the failing report test**

`ReportTest.kt`:

```kotlin
package io.github.iamjosephmj.bench

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportTest {
    @Test fun `report json contains records and device info`() {
        val json = Report.toJson(
            listOf(RunRecord("ping-none", "bridge", 100L, 150L, 200L,
                attempts = 1, chunksReplayed = 0)),
            deviceInfo = mapOf("model" to "TestDevice", "sdk" to "34"))
        assertThat(json).contains("\"itemId\":\"ping-none\"")
        assertThat(json).contains("\"model\":\"TestDevice\"")
        assertThat(json).contains("\"timeToFirstStartMs\":50")
        assertThat(json).contains("\"timeToCompleteMs\":100")
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :bench:testDebugUnitTest --tests "*.ReportTest"`
Expected: FAIL.

- [ ] **Step 4: Implement bench sources**

`Corpus.kt`:

```kotlin
package io.github.iamjosephmj.bench

data class CorpusItem(val id: String, val kind: Kind, val profile: Profile) {
    enum class Kind(val bytes: Long, val chunks: Int) {
        PING(4_096, 0), MEDIUM_SYNC(5_000_000, 0), LARGE_CHUNKED(200_000_000, 40)
    }
    enum class Profile { NONE, UNMETERED_CHARGING }
}

val CORPUS: List<CorpusItem> = CorpusItem.Kind.entries.flatMap { kind ->
    CorpusItem.Profile.entries.map { profile ->
        CorpusItem("${kind.name.lowercase()}-${profile.name.lowercase()}", kind, profile)
    }
}
```

`Backend.kt`:

```kotlin
package io.github.iamjosephmj.bench

data class RunRecord(
    val itemId: String, val backend: String,
    val enqueuedAt: Long, val firstStartAt: Long?, val completedAt: Long?,
    val attempts: Int, val chunksReplayed: Int,
)

interface Backend {
    val name: String
    fun enqueueAll(items: List<CorpusItem>)
    fun collect(): List<RunRecord>
}

/** Simulated transfer: deterministic CPU+IO proportional to size; no network. */
fun simulateChunk(bytes: Long, scratchDir: java.io.File, tag: String) {
    val f = java.io.File(scratchDir, "scratch-$tag.bin")
    f.writeBytes(ByteArray(minOf(bytes, 1_000_000L).toInt()))
    Thread.sleep(bytes / 1_000_000L + 5)   // ~1ms per MB
    f.delete()
}
```

`BridgeBackend.kt`:

```kotlin
package io.github.iamjosephmj.bench

import android.content.Context
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.workRequest
import io.github.iamjosephmj.bridge.store.WorkEvent

class BridgeBackend(private val context: Context) : Backend {
    override val name = "bridge"

    override fun enqueueAll(items: List<CorpusItem>) {
        for (item in items) {
            Bridge.enqueue(workRequest(item.id, workerName = workerFor(item)) {
                if (item.profile == CorpusItem.Profile.UNMETERED_CHARGING) {
                    unmetered(); charging()
                }
                if (item.kind.chunks > 0) {
                    chunks(count = item.kind.chunks, estimatedUpBytes = item.kind.bytes)
                }
            })
        }
    }

    override fun collect(): List<RunRecord> = CORPUS.map { item ->
        val events = Bridge.events(item.id)
        val starts = events.filterIsInstance<WorkEvent.Started>()
        val chunks = events.filterIsInstance<WorkEvent.ChunkCompleted>()
        RunRecord(
            itemId = item.id, backend = name,
            enqueuedAt = events.filterIsInstance<WorkEvent.Enqueued>().lastOrNull()?.at ?: 0L,
            firstStartAt = starts.minOfOrNull { it.at },
            completedAt = events.filterIsInstance<WorkEvent.Finished>()
                .lastOrNull { it.success }?.at,
            attempts = starts.size,
            chunksReplayed = chunks.size - chunks.map { it.chunkIndex }.distinct().size)
    }

    companion object {
        fun workerFor(item: CorpusItem) =
            if (item.kind.chunks > 0) "bench-chunked" else "bench-plain-${item.kind.name}"
    }
}
```

`WorkManagerBackend.kt`:

```kotlin
package io.github.iamjosephmj.bench

import android.content.Context
import androidx.work.*
import java.util.concurrent.ConcurrentHashMap

/** WorkManager keeps no run history, so the bench self-instruments timestamps.
 *  Recorded in-process + flushed to SharedPreferences to survive process death. */
object WmRecorder {
    private const val PREFS = "wm-recorder"
    lateinit var appContext: Context
    private val prefs by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun mark(itemId: String, key: String, onlyFirst: Boolean = false) {
        val k = "$itemId:$key"
        if (onlyFirst && prefs.contains(k)) {
            if (key == "start") bumpAttempts(itemId)
            return
        }
        prefs.edit().putLong(k, System.currentTimeMillis()).apply()
        if (key == "start") bumpAttempts(itemId)
    }
    private fun bumpAttempts(itemId: String) {
        prefs.edit().putInt("$itemId:attempts",
            prefs.getInt("$itemId:attempts", 0) + 1).apply()
    }
    fun record(itemId: String): RunRecord = RunRecord(
        itemId = itemId, backend = "workmanager",
        enqueuedAt = prefs.getLong("$itemId:enqueue", 0L),
        firstStartAt = prefs.getLong("$itemId:start", 0L).takeIf { it != 0L },
        completedAt = prefs.getLong("$itemId:complete", 0L).takeIf { it != 0L },
        attempts = prefs.getInt("$itemId:attempts", 0),
        chunksReplayed = 0)   // WorkManager has no chunk concept; restarts re-run everything
}

class WmBenchWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {
    override fun doWork(): Result {
        val itemId = inputData.getString("itemId")!!
        val bytes = inputData.getLong("bytes", 0L)
        val chunks = inputData.getInt("chunks", 0)
        WmRecorder.mark(itemId, "start", onlyFirst = true)
        if (chunks > 0) {
            // No resume support: always from zero. That's the comparison point.
            for (i in 0 until chunks) {
                if (isStopped) return Result.retry()
                simulateChunk(bytes / chunks, applicationContext.cacheDir, "$itemId-$i")
            }
        } else {
            simulateChunk(bytes, applicationContext.cacheDir, itemId)
        }
        WmRecorder.mark(itemId, "complete")
        return Result.success()
    }
}

class WorkManagerBackend(private val context: Context) : Backend {
    override val name = "workmanager"

    override fun enqueueAll(items: List<CorpusItem>) {
        WmRecorder.appContext = context.applicationContext
        val wm = WorkManager.getInstance(context)
        for (item in items) {
            WmRecorder.mark(item.id, "enqueue")
            val constraints = if (item.profile == CorpusItem.Profile.UNMETERED_CHARGING) {
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true).build()
            } else Constraints.NONE
            wm.enqueueUniqueWork(item.id, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WmBenchWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(
                        "itemId" to item.id,
                        "bytes" to item.kind.bytes,
                        "chunks" to item.kind.chunks))
                    .build())
        }
    }

    override fun collect(): List<RunRecord> = CORPUS.map { WmRecorder.record(it.id) }
}
```

`Report.kt`:

```kotlin
package io.github.iamjosephmj.bench

import org.json.JSONArray
import org.json.JSONObject

object Report {
    fun toJson(records: List<RunRecord>, deviceInfo: Map<String, String>): String {
        val root = JSONObject()
        root.put("device", JSONObject(deviceInfo))
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("itemId", r.itemId); put("backend", r.backend)
                put("enqueuedAt", r.enqueuedAt)
                put("firstStartAt", r.firstStartAt); put("completedAt", r.completedAt)
                put("attempts", r.attempts); put("chunksReplayed", r.chunksReplayed)
                put("timeToFirstStartMs",
                    r.firstStartAt?.let { it - r.enqueuedAt })
                put("timeToCompleteMs",
                    r.completedAt?.let { it - r.enqueuedAt })
            })
        }
        root.put("records", arr)
        return root.toString()
    }
}
```

`BenchApp.kt` + `BenchReceiver.kt`:

```kotlin
package io.github.iamjosephmj.bench

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.api.*
import java.io.File

class BenchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Bridge.initialize(this) {
            worker("bench-plain-PING") { plain(CorpusItem.Kind.PING) }
            worker("bench-plain-MEDIUM_SYNC") { plain(CorpusItem.Kind.MEDIUM_SYNC) }
            worker("bench-chunked") { object : ChunkedWorker {
                override suspend fun runChunk(ctx: RunContext, chunkIndex: Int): RunResult {
                    val kind = CorpusItem.Kind.LARGE_CHUNKED
                    simulateChunk(kind.bytes / kind.chunks, cacheDir, "${ctx.workId}-$chunkIndex")
                    return RunResult.Success
                } } }
        }
    }
    private fun plain(kind: CorpusItem.Kind) = object : BridgeWorker {
        override suspend fun run(ctx: RunContext): RunResult {
            simulateChunk(kind.bytes, cacheDir, ctx.workId); return RunResult.Success
        }
    }
}

class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "bench.ENQUEUE_BRIDGE" -> BridgeBackend(context).enqueueAll(CORPUS)
            "bench.ENQUEUE_WM" -> WorkManagerBackend(context).enqueueAll(CORPUS)
            "bench.DUMP_REPORT" -> {
                val backend: Backend = if (intent.getStringExtra("backend") == "workmanager")
                    WorkManagerBackend(context) else BridgeBackend(context)
                val json = Report.toJson(backend.collect(), mapOf(
                    "model" to Build.MODEL, "manufacturer" to Build.MANUFACTURER,
                    "sdk" to Build.VERSION.SDK_INT.toString()))
                val out = File(context.getExternalFilesDir(null),
                    "report-${backend.name}-${System.currentTimeMillis()}.json")
                out.writeText(json)
                Log.i("Bench", "report written: ${out.absolutePath}")
            }
        }
    }
}
```

`bench/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name=".BenchApp" android:label="Bridge Bench">
        <receiver android:name=".BenchReceiver" android:exported="false">
            <intent-filter>
                <action android:name="bench.ENQUEUE_BRIDGE" />
                <action android:name="bench.ENQUEUE_WM" />
                <action android:name="bench.DUMP_REPORT" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

- [ ] **Step 5: Run tests + build**

Run: `./gradlew :bench:testDebugUnitTest :bench:assembleDebug`
Expected: `ReportTest` PASS; APK builds.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts bench
git commit -m "feat(bench): corpus, bridge+workmanager backends, JSON report"
```

---

### Task 15: Bench scenario scripts + report comparison

**Files:**
- Create: `bench/scripts/run-scenario.sh`
- Create: `bench/scripts/compare-reports.py` (stdlib only)
- Create: `bench/README.md`

**Interfaces:**
- Consumes: bench APK broadcast actions (Task 14), report JSON schema.
- Produces: `scenarios: baseline | force-stop | doze | reboot`; a comparison table on stdout.

- [ ] **Step 1: Write `run-scenario.sh`**

```bash
#!/usr/bin/env bash
# Usage: ./run-scenario.sh <bridge|workmanager> <baseline|force-stop|doze> [serial]
set -euo pipefail
BACKEND="$1"; SCENARIO="$2"; SERIAL="${3:-}"
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG="io.github.iamjosephmj.bench"

case "$BACKEND" in
  bridge) ACTION="bench.ENQUEUE_BRIDGE" ;;
  workmanager) ACTION="bench.ENQUEUE_WM" ;;
  *) echo "backend must be bridge|workmanager"; exit 1 ;;
esac

$ADB shell am broadcast --receiver-foreground -n "$PKG/.BenchReceiver" -a "$ACTION" >/dev/null
echo "enqueued corpus on $BACKEND"

case "$SCENARIO" in
  baseline)
    sleep 120 ;;
  force-stop)
    sleep 20
    $ADB shell am force-stop "$PKG"
    echo "force-stopped; relaunching in 10s"
    sleep 10
    $ADB shell monkey -p "$PKG" 1 >/dev/null 2>&1 || true   # relaunch → Bridge reconciles
    sleep 120 ;;
  doze)
    sleep 20
    $ADB shell dumpsys deviceidle force-idle
    echo "forced deep idle for 60s"
    sleep 60
    $ADB shell dumpsys deviceidle unforce
    $ADB shell dumpsys battery reset
    sleep 120 ;;
  *) echo "scenario must be baseline|force-stop|doze"; exit 1 ;;
esac

$ADB shell am broadcast --receiver-foreground -n "$PKG/.BenchReceiver" \
  -a bench.DUMP_REPORT --es backend "$BACKEND" >/dev/null
sleep 2
REMOTE=$($ADB shell "ls -t /sdcard/Android/data/$PKG/files/report-$BACKEND-*.json | head -1" | tr -d '\r')
OUT="reports/$(basename "$REMOTE" .json)-$SCENARIO.json"
mkdir -p reports
$ADB pull "$REMOTE" "$OUT" >/dev/null
echo "report: $OUT"
```

- [ ] **Step 2: Write `compare-reports.py`**

```python
#!/usr/bin/env python3
"""Usage: compare-reports.py <bridge-report.json> <workmanager-report.json>"""
import json, sys

def load(path):
    with open(path) as f:
        d = json.load(f)
    return {r["itemId"]: r for r in d["records"]}, d["device"]

bridge, device = load(sys.argv[1])
wm, _ = load(sys.argv[2])

print(f"device: {device.get('manufacturer')} {device.get('model')} (sdk {device.get('sdk')})")
hdr = f"{'item':28} {'metric':20} {'bridge':>12} {'workmanager':>12}"
print(hdr); print("-" * len(hdr))
for item in sorted(set(bridge) | set(wm)):
    b, w = bridge.get(item, {}), wm.get(item, {})
    for metric in ("timeToFirstStartMs", "timeToCompleteMs", "attempts", "chunksReplayed"):
        bv, wv = b.get(metric), w.get(metric)
        print(f"{item:28} {metric:20} {str(bv):>12} {str(wv):>12}")
lost_b = [i for i, r in bridge.items() if r.get("completedAt") is None]
lost_w = [i for i, r in wm.items() if r.get("completedAt") is None]
print(f"\nincomplete — bridge: {lost_b or 'none'} | workmanager: {lost_w or 'none'}")
```

- [ ] **Step 3: Write `bench/README.md`**

```markdown
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
```

- [ ] **Step 4: Verify scripts locally**

Run: `bash -n bench/scripts/run-scenario.sh && python3 -c "import ast; ast.parse(open('bench/scripts/compare-reports.py').read())" && chmod +x bench/scripts/*.{sh,py}`
Expected: no syntax errors.

- [ ] **Step 5: Commit**

```bash
git add bench
git commit -m "feat(bench): scenario driver scripts and report comparison"
```

---

### Task 16: M1 acceptance run + library README

**Files:**
- Create: `README.md` (project root)
- No new library code — this is the milestone gate.

- [ ] **Step 1: Full local verification**

Run: `./gradlew :bridge-runtime:testDebugUnitTest :bench:testDebugUnitTest :bridge-runtime:assembleDebug :bench:assembleDebug`
Expected: all green.

- [ ] **Step 2: Instrumented + bench acceptance on an emulator**

```bash
./gradlew :bridge-runtime:connectedDebugAndroidTest
./gradlew :bench:installDebug
(cd bench/scripts && ./run-scenario.sh bridge baseline && ./run-scenario.sh workmanager baseline)
(cd bench/scripts && ./run-scenario.sh bridge force-stop && ./run-scenario.sh workmanager force-stop)
(cd bench/scripts && ./compare-reports.py reports/report-bridge-*baseline*.json reports/report-workmanager-*baseline*.json)
```

Acceptance criteria (from spec M1):
- Instrumented smoke tests pass on the emulator.
- `force-stop` scenario: Bridge's `large_chunked-none` item shows `chunksReplayed == 0` and completes after relaunch; WorkManager's equivalent restarts from zero (attempts ≥ 2, full re-run).
- No incomplete Bridge items in `baseline`.

If the chunked force-stop criterion fails, debug before closing M1 — it is the signature claim.

- [ ] **Step 3: Write root `README.md`**

```markdown
# Bridge

A reimplementation of Android background work that uses what system_server
actually offers: JobWorkItem-multiplexed dispatch, an append-only event journal,
chunk-exact resumption, death forensics via ApplicationExitInfo, and measured
per-run cost via HealthStats.

- Design: `docs/superpowers/specs/2026-07-27-bridge-design.md`
- M1 plan: `docs/superpowers/plans/2026-07-27-bridge-m1-core-scheduler.md`
- Benchmark vs WorkManager: `bench/README.md`

Status: M1 (core scheduler v0.1) — see plan for progress.
```

- [ ] **Step 4: Commit**

```bash
git add README.md bench/scripts/reports 2>/dev/null || git add README.md
git commit -m "docs: project README; M1 acceptance run recorded"
```

---

## Self-Review Notes

- **Spec coverage (M1 scope):** L1 journal → Tasks 1–3; L2 host jobs/conformance/reconciliation → Tasks 5, 6, 10, 11, 12; L5 leases/black box/HealthStats → Tasks 7, 8, 9; minimal L6 → Tasks 4, 11; benchmark harness → Tasks 14–16; instrumented conformance seed → Task 13. Out-of-M1 spec items (signal hub, policy engine, compat façade, UIDT, windows, simulator) are deliberately absent per spec §8.
- **Deviation note:** spec §4.5 leases mention `JobParameters.getNetwork()` binding — deferred to M2 (needs API-34 plumbing through the drainer; journaled here so it isn't silently lost).
- **Type consistency check:** `WorkItemPayload(workId, generation)` used consistently across Tasks 6/10/12; `RunOutcome` across 9/10/12; `foldWorkState` across 2/3; `Importance.ordinal` ↔ `importance: Int` (MIN=0…HIGH=3) across 4/5/11.
