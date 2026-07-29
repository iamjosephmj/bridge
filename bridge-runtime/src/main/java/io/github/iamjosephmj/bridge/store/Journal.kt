package io.github.iamjosephmj.bridge.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val SCHEMA_VERSION = 2

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
        db.execSQL(CREATE_KV)
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 -> v2: kv table for scheduler bookkeeping (KvStore). Additive, so IF NOT
        // EXISTS is the whole migration and existing installs keep their journal intact.
        db.execSQL(CREATE_KV)
    }
    override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Tolerant on purpose — the default SQLiteOpenHelper behavior is to THROW, which
        // turns an app-store rollback (or sideloading an older build over a newer one)
        // into a crash loop at process start: the on-disk db keeps the newer version
        // stamp, and every open dies before Bridge can run at all. Rollbacks do happen
        // in the wild, so we never throw here. All schema history is additive, so a
        // newer db is a superset of what this code needs: ensure every table this
        // version reads exists (IF NOT EXISTS is a no-op when it already does) and
        // carry on; SQLiteOpenHelper then re-stamps the version down for us. Any
        // extra tables/columns a future version added are simply ignored.
        db.execSQL("""CREATE TABLE IF NOT EXISTS events (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            work_id TEXT NOT NULL,
            at INTEGER NOT NULL,
            payload TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_work ON events(work_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_state (
            work_id TEXT PRIMARY KEY,
            run_state TEXT NOT NULL,
            snapshot TEXT NOT NULL)""")
        db.execSQL(CREATE_KV)
    }
    private companion object {
        const val CREATE_KV = "CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)"
    }
}

class Journal(
    context: Context,
    dbName: String = "bridge.db",
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor(),
) : EventJournal {
    private val db = JournalDb(context.applicationContext, dbName)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(WorkEvent) -> Unit>()

    /**
     * The [KvStore] living in this journal's database — the narrow doorway to the `kv`
     * table (the [android.database.sqlite.SQLiteDatabase] itself is never handed out).
     * Lazy so the full-table load happens once, on first use.
     */
    private val kv by lazy { KvStore(db.writableDatabase) }
    fun kvStore(): KvStore = kv

    override fun append(event: WorkEvent) = appendAll(listOf(event))

    override fun addListener(listener: (WorkEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    internal fun listenerCount(): Int = listeners.size

    override fun appendAll(events: List<WorkEvent>) {
        writeAll(events)
        // Fire outside the DB lock, and only after a successful commit.
        for (l in listeners) for (e in events) {
            try { l(e) } catch (ex: Exception) { Log.e("BridgeJournal", "listener failed", ex) }
        }
    }

    @Synchronized
    private fun writeAll(events: List<WorkEvent>) {
        try {
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
        } catch (ex: Exception) {
            Log.e("BridgeJournal", "appendAll failed", ex)
            throw ex
        }
    }

    override fun events(workId: String): List<WorkEvent> {
        val out = mutableListOf<WorkEvent>()
        db.readableDatabase.rawQuery(
            "SELECT payload FROM events WHERE work_id = ? ORDER BY seq", arrayOf(workId)).use { c ->
            while (c.moveToNext()) out += EventCodec.decode(c.getString(0))
        }
        return out
    }

    override fun state(workId: String): WorkState? = foldWorkState(events(workId))

    override fun liveWork(): List<WorkState> = statesWhere(
        "run_state IN ('ENQUEUED','DISPATCHED','RUNNING')")

    override fun runningWork(): List<WorkState> = statesWhere("run_state = 'RUNNING'")

    override fun allWork(): List<WorkState> = statesWhere("1=1")

    private fun statesWhere(where: String): List<WorkState> {
        val ids = mutableListOf<String>()
        db.readableDatabase.rawQuery(
            "SELECT work_id FROM work_state WHERE $where", null).use { c ->
            while (c.moveToNext()) ids += c.getString(0)
        }
        return ids.mapNotNull { state(it) }
    }

    override fun prune(olderThanMs: Long, now: Long) {
        ioExecutor.execute {
            try {
                val w = db.writableDatabase
                w.beginTransaction()
                try {
                    val cutoff = now - olderThanMs
                    // Delete all events for terminal work items whose latest event is older than cutoff
                    w.execSQL("""DELETE FROM events WHERE work_id IN
                        (SELECT work_id FROM work_state
                         WHERE run_state IN ('SUCCEEDED','FAILED','CANCELLED')
                         AND work_id IN
                           (SELECT work_id FROM events
                            GROUP BY work_id
                            HAVING MAX(at) < ?))""",
                        arrayOf<Any>(cutoff))
                    // Delete orphaned work_state rows
                    w.execSQL("""DELETE FROM work_state WHERE
                        run_state IN ('SUCCEEDED','FAILED','CANCELLED') AND
                        work_id NOT IN (SELECT DISTINCT work_id FROM events)""")
                    w.setTransactionSuccessful()
                } finally { w.endTransaction() }
            } catch (ex: Exception) {
                // Log only: rethrowing here would kill the executor thread silently.
                Log.e("BridgeJournal", "prune failed", ex)
            }
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

    @Synchronized
    override fun close() {
        // Appends are synchronous under this lock; only prune work may still be queued.
        ioExecutor.execute { db.close() }
    }
}
