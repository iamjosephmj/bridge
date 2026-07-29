package io.github.iamjosephmj.bridge.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

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
) : EventJournal {
    private val db = JournalDb(context.applicationContext, dbName)
    private var lastWriteTask: FutureTask<Unit>? = null

    override fun append(event: WorkEvent) = appendAll(listOf(event))

    override fun appendAll(events: List<WorkEvent>) {
        val task = FutureTask {
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
        lastWriteTask = task
        ioExecutor.execute(task)
        task.get()  // Block until write completes; propagate exceptions
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
                Log.e("BridgeJournal", "prune failed", ex)
                throw ex
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

    override fun close() {
        lastWriteTask?.get()  // Wait for any pending write to complete
        ioExecutor.execute { db.close() }
    }
}
