package io.github.iamjosephmj.bridge.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Small string key/value store persisted in the journal's SQLite database (`kv` table),
 * with an in-memory-first read path: every row is loaded once at construction and all
 * reads are served from the in-memory map — synchronous, allocation-free, no I/O. Writes
 * update memory first, then persist through the same database in a transaction, so kv
 * state shares one durability story with the work journal (same file, same WAL) instead
 * of a second one in SharedPreferences.
 *
 * Why not Jetpack DataStore: DataStore reads are Flow/suspend-shaped and its writes are
 * serialized through a coroutine actor over a separate file. Bridge needs synchronous
 * reads at dispatch decision points (gateway selection in [io.github.iamjosephmj.bridge.dispatch.SelectingJobGateway],
 * jobId mapping in [io.github.iamjosephmj.bridge.dispatch.OneToOneJobGateway]) on
 * whatever thread the dispatcher happens to run, and wants a single durability story
 * with the journal DB. A memory map over a table in the journal's database gives both;
 * DataStore would give neither without a `runBlocking` bridge at every decision point.
 */
class KvStore internal constructor(private val db: SQLiteDatabase) {

    private val map = HashMap<String, String>()

    init {
        // Belt and braces alongside the schema migration: the table must exist before load.
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)")
        db.rawQuery("SELECT key, value FROM kv", null).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
        }
    }

    @Synchronized fun get(key: String): String? = map[key]

    @Synchronized fun getInt(key: String, default: Int): Int =
        map[key]?.toIntOrNull() ?: default

    fun put(key: String, value: String) = putAll(mapOf(key to value))

    fun putInt(key: String, value: Int) = put(key, value.toString())

    /** Atomically writes several entries: memory first, then one DB transaction. */
    @Synchronized
    fun putAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        map.putAll(entries)
        db.beginTransaction()
        try {
            for ((k, v) in entries) {
                db.execSQL("INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)",
                    arrayOf(k, v))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * One-time import of the legacy SharedPreferences files ("bridge.conformance",
     * "bridge.jobids") into kv keys ("conformance.*", "jobid.*"). Values already present
     * in kv are never overwritten — kv is the source of truth once a key exists. After
     * the first pass the [KEY_MIGRATED] marker short-circuits every later call, so later
     * writes to the (now unused) prefs files can never leak back in.
     */
    fun importLegacyPrefs(context: Context) {
        if (get(KEY_MIGRATED) != null) return
        val imported = mutableMapOf<String, String>()
        val conf = context.getSharedPreferences("bridge.conformance", Context.MODE_PRIVATE)
        conf.getString("mode", null)?.let { imported["conformance.mode"] = it }
        if (conf.contains("failures")) {
            imported["conformance.failures"] = conf.getInt("failures", 0).toString()
        }
        val jobIds = context.getSharedPreferences("bridge.jobids", Context.MODE_PRIVATE)
        for ((key, value) in jobIds.all) {
            when {
                key == "next-id" -> imported["jobid.next"] = value.toString()
                key.startsWith("id.") ->
                    imported["jobid." + key.removePrefix("id.")] = value.toString()
            }
        }
        putAll(imported.filterKeys { get(it) == null } + (KEY_MIGRATED to "1"))
    }

    companion object {
        const val KEY_MIGRATED = "migrated.prefs"
    }
}
