package com.brickssoft.premisemonitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** One stored entry: its insertion sequence number and its JSON text. */
internal data class StoredEntry(val seq: Long, val json: String)

/**
 * The audit log: PremiseMonitor's own SQLite database (`pm_audit.db`), independent of the tracking plugin's queue.
 *
 * - Entries keep their insertion order (`seq`); uploads and [recent] use it.
 * - `uploaded` is set once the audit endpoint answered 2xx.
 * - Bounded: [prune] (every [pruneEvery] appends, and when first used) keeps the newest [keepNewest] entries, deletes
 *   older entries that were uploaded, and keeps at most [hardCap] entries in all (the oldest go first, uploaded or
 *   not), so the log cannot grow without limit while no audit URL is set or the endpoint is down.
 *
 * Thread-safe: `SQLiteDatabase` serializes access; appends run on `PM-native`, uploads on `PM-upload`.
 */
internal class AuditStore(
    context: Context,
    name: String? = DB_NAME,
    private val keepNewest: Int = KEEP_NEWEST,
    private val hardCap: Int = HARD_CAP,
    private val pruneEvery: Int = PRUNE_EVERY,
) : SQLiteOpenHelper(context, name, null, DB_VERSION) {
    private var appendsSincePrune = 0
    private var prunedOnce = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, at TEXT NOT NULL, " +
                "json TEXT NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX ${TABLE}_pending ON $TABLE (uploaded, seq)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Appends [entry] (it must have `id` and `at`); returns its seq. */
    @Synchronized
    fun append(entry: JSONObject): Long {
        val values = ContentValues().apply {
            put("id", entry.getString("id"))
            put("at", entry.getString("at"))
            put("json", entry.toString())
            put("uploaded", 0)
        }
        val seq = writableDatabase.insertOrThrow(TABLE, null, values)
        if (!prunedOnce || ++appendsSincePrune >= pruneEvery) prune()
        return seq
    }

    /** The oldest [limit] entries not uploaded yet, oldest first. */
    fun pending(limit: Int): List<StoredEntry> {
        val result = ArrayList<StoredEntry>()
        readableDatabase.rawQuery(
            "SELECT seq, json FROM $TABLE WHERE uploaded = 0 ORDER BY seq LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(StoredEntry(cursor.getLong(0), cursor.getString(1)))
        }
        return result
    }

    fun markUploaded(seqs: List<Long>) {
        if (seqs.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (chunk in seqs.chunked(SQL_CHUNK)) {
                val args = chunk.joinToString(",") { "?" }
                db.execSQL("UPDATE $TABLE SET uploaded = 1 WHERE seq IN ($args)", chunk.map { it.toString() }.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pendingCount(): Int = count("SELECT COUNT(*) FROM $TABLE WHERE uploaded = 0")

    fun count(): Int = count("SELECT COUNT(*) FROM $TABLE")

    /** `at` of the newest entry, or null. */
    fun lastEntryAt(): String? =
        readableDatabase.rawQuery("SELECT at FROM $TABLE ORDER BY seq DESC LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /** The newest [limit] entries (all when [limit] <= 0), oldest first (newest last). */
    fun recent(limit: Int): List<String> {
        val result = ArrayList<String>()
        val sql = if (limit > 0) {
            "SELECT json FROM $TABLE ORDER BY seq DESC LIMIT $limit"
        } else {
            "SELECT json FROM $TABLE ORDER BY seq DESC"
        }
        readableDatabase.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        result.reverse()
        return result
    }

    /** Applies the bounds (see the class comment). */
    @Synchronized
    fun prune() {
        prunedOnce = true
        appendsSincePrune = 0
        val db = writableDatabase
        seqAtOffset(keepNewest)?.let { oldestKept ->
            db.execSQL("DELETE FROM $TABLE WHERE uploaded = 1 AND seq < ?", arrayOf(oldestKept.toString()))
        }
        seqAtOffset(hardCap)?.let { oldestKept ->
            val dropped = count("SELECT COUNT(*) FROM $TABLE WHERE seq < $oldestKept")
            db.execSQL("DELETE FROM $TABLE WHERE seq < ?", arrayOf(oldestKept.toString()))
            if (dropped > 0) PmLog.w(TAG, "audit log over $hardCap entries: dropped the oldest $dropped")
        }
    }

    /** The seq of the [n]-th newest entry (1-based), or null if there are fewer than [n] entries. */
    private fun seqAtOffset(n: Int): Long? {
        if (n <= 0) return null
        return readableDatabase.rawQuery(
            "SELECT seq FROM $TABLE ORDER BY seq DESC LIMIT 1 OFFSET ${n - 1}",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun count(sql: String): Int =
        readableDatabase.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    internal companion object {
        const val DB_NAME = "pm_audit.db"
        private const val DB_VERSION = 1
        private const val TABLE = "entries"
        const val KEEP_NEWEST = 1000
        const val HARD_CAP = 10_000
        const val PRUNE_EVERY = 50
        private const val SQL_CHUNK = 500
        private const val TAG = "PM.Store"
    }
}
