package io.github.iamjosephmj.bridge.signals

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val SCHEMA_VERSION = 1

private class SignalDb(context: Context, name: String) :
    SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE transitions (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            at INTEGER NOT NULL,
            payload TEXT NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
}

class SqliteTransitionStore(
    context: Context,
    dbName: String = "bridge-signals.db",
) : TransitionStore {
    private val db = SignalDb(context.applicationContext, dbName)

    override fun append(at: Long, payload: String) {
        db.writableDatabase.insert("transitions", null, ContentValues().apply {
            put("at", at); put("payload", payload)
        })
    }

    override fun all(): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        db.readableDatabase.rawQuery(
            "SELECT at, payload FROM transitions ORDER BY seq", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to c.getString(1)
        }
        return out
    }

    override fun count(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM transitions", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    override fun oldestAt(): Long? =
        db.readableDatabase.rawQuery(
            "SELECT at FROM transitions ORDER BY seq LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    override fun deleteOldest(n: Int) {
        db.writableDatabase.execSQL(
            "DELETE FROM transitions WHERE seq IN (SELECT seq FROM transitions ORDER BY seq LIMIT ?)",
            arrayOf<Any>(n))
    }
}
