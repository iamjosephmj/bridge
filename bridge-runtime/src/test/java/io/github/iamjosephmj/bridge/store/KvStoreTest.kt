package io.github.iamjosephmj.bridge.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KvStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `put then get roundtrips and survives a reload`() {
        val db = SQLiteDatabase.create(null)
        val kv = KvStore(db)
        kv.put("a", "1")
        kv.putInt("b", 42)
        assertThat(kv.get("a")).isEqualTo("1")
        assertThat(kv.getInt("b", -1)).isEqualTo(42)
        val reloaded = KvStore(db)   // fresh in-memory map, same database
        assertThat(reloaded.get("a")).isEqualTo("1")
        assertThat(reloaded.getInt("b", -1)).isEqualTo(42)
        assertThat(reloaded.get("missing")).isNull()
        assertThat(reloaded.getInt("missing", 7)).isEqualTo(7)
    }

    @Test fun `putAll writes every entry atomically`() {
        val db = SQLiteDatabase.create(null)
        KvStore(db).putAll(mapOf("x" to "1", "y" to "2"))
        val reloaded = KvStore(db)
        assertThat(reloaded.get("x")).isEqualTo("1")
        assertThat(reloaded.get("y")).isEqualTo("2")
    }

    @Test fun `legacy SharedPreferences import maps conformance and jobid keys`() {
        context.getSharedPreferences("bridge.conformance", Context.MODE_PRIVATE).edit()
            .putString("mode", "ONE_TO_ONE").putInt("failures", 2).commit()
        context.getSharedPreferences("bridge.jobids", Context.MODE_PRIVATE).edit()
            .putInt("next-id", 720_005).putInt("id.w1", 720_004).commit()
        val kv = KvStore(SQLiteDatabase.create(null))
        kv.importLegacyPrefs(context)
        assertThat(kv.get("conformance.mode")).isEqualTo("ONE_TO_ONE")
        assertThat(kv.getInt("conformance.failures", -1)).isEqualTo(2)
        assertThat(kv.getInt("jobid.next", -1)).isEqualTo(720_005)
        assertThat(kv.getInt("jobid.w1", -1)).isEqualTo(720_004)
        assertThat(kv.get(KvStore.KEY_MIGRATED)).isEqualTo("1")
    }

    @Test fun `import runs once - later prefs writes never leak back in`() {
        context.getSharedPreferences("bridge.conformance", Context.MODE_PRIVATE).edit()
            .putString("mode", "ONE_TO_ONE").commit()
        val kv = KvStore(SQLiteDatabase.create(null))
        kv.importLegacyPrefs(context)
        context.getSharedPreferences("bridge.jobids", Context.MODE_PRIVATE).edit()
            .putInt("id.w9", 999_999).commit()
        kv.importLegacyPrefs(context)   // marker short-circuits
        assertThat(kv.get("jobid.w9")).isNull()
        assertThat(kv.get("conformance.mode")).isEqualTo("ONE_TO_ONE")
    }

    @Test fun `import never overwrites values already in kv`() {
        context.getSharedPreferences("bridge.conformance", Context.MODE_PRIVATE).edit()
            .putString("mode", "ONE_TO_ONE").commit()
        val kv = KvStore(SQLiteDatabase.create(null))
        kv.put("conformance.mode", "MULTIPLEXED")
        kv.importLegacyPrefs(context)
        assertThat(kv.get("conformance.mode")).isEqualTo("MULTIPLEXED")
    }

    @Test fun `import with no legacy prefs still marks migrated`() {
        val kv = KvStore(SQLiteDatabase.create(null))
        kv.importLegacyPrefs(context)
        assertThat(kv.get(KvStore.KEY_MIGRATED)).isEqualTo("1")
    }

    @Test fun `journal schema v1 upgrades in place - kv table appears, events survive`() {
        val name = "up-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        // Hand-build a v1 database: journal tables, no kv, version stamped 1.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL("""CREATE TABLE events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                work_id TEXT NOT NULL, at INTEGER NOT NULL, payload TEXT NOT NULL)""")
            v1.execSQL("CREATE INDEX idx_events_work ON events(work_id)")
            v1.execSQL("""CREATE TABLE work_state (
                work_id TEXT PRIMARY KEY, run_state TEXT NOT NULL, snapshot TEXT NOT NULL)""")
            v1.execSQL("INSERT INTO events (work_id, at, payload) VALUES ('w1', 1, 'p')")
            v1.version = 1
        }
        val journal = Journal(context, name, Executor { it.run() })
        val kv = journal.kvStore()   // onUpgrade must have created the kv table
        kv.put("k", "v")
        assertThat(KvStore(SQLiteDatabase.openDatabase(
            file.path, null, SQLiteDatabase.OPEN_READWRITE)).get("k")).isEqualTo("v")
        journal.close()
    }

    @Test fun `opening a newer-versioned db downgrades tolerantly - no throw, data kept`() {
        // App-rollback simulation: a "future" build stamped the db version 3 (with some
        // hypothetical extra table); today's code opens it at SCHEMA_VERSION 2. The
        // default SQLiteOpenHelper onDowngrade throws — ours must not, and must leave
        // the journal + kv usable with existing rows intact.
        val name = "down-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v3 ->
            v3.execSQL("""CREATE TABLE events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                work_id TEXT NOT NULL, at INTEGER NOT NULL, payload TEXT NOT NULL)""")
            v3.execSQL("CREATE INDEX idx_events_work ON events(work_id)")
            v3.execSQL("""CREATE TABLE work_state (
                work_id TEXT PRIMARY KEY, run_state TEXT NOT NULL, snapshot TEXT NOT NULL)""")
            v3.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT)")
            v3.execSQL("INSERT INTO kv (key, value) VALUES ('survivor', 'yes')")
            v3.execSQL("CREATE TABLE future_stuff (id INTEGER PRIMARY KEY)")   // ignored
            v3.version = 3
        }
        val journal = Journal(context, name, Executor { it.run() })
        val kv = journal.kvStore()   // triggers the open → onDowngrade; must not throw
        assertThat(kv.get("survivor")).isEqualTo("yes")
        kv.put("post-downgrade", "ok")
        assertThat(KvStore(SQLiteDatabase.openDatabase(
            file.path, null, SQLiteDatabase.OPEN_READWRITE)).get("post-downgrade"))
            .isEqualTo("ok")
        journal.close()
        // The helper re-stamped the version down, so the next open is a plain v2 open.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertThat(it.version).isEqualTo(2)
        }
    }
}
