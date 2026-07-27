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
