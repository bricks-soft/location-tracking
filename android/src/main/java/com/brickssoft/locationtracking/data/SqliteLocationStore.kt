package com.brickssoft.locationtracking.data

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SQLite record queue (table `records` of [TrackingDatabase]). All work runs on [AppDispatchers.io]; failures are
 * thrown as `TrackingException(IO_ERROR)`.
 *
 * - Rows hold `RecordJson.toJson(record)` without `sent_at`. Inserting an existing uuid replaces the row (and
 *   resets its attempt counters).
 * - [list] is oldest first (`recorded_at`, then insertion order). A row that cannot be decoded is logged, skipped
 *   and deleted (a limited page is refilled with the next rows), so a bad row cannot block the queue. [count] still
 *   includes such a row until a [list] has seen it.
 * - Pruning runs on first use and after every [PRUNE_EVERY] inserts, for **every** event type: heartbeat and
 *   audit records are dropped like location records. First, records whose `recorded_at` is older than
 *   `persistence.maxDaysToPersist` days are deleted (skipped if that is <= 0); then, if
 *   `persistence.maxRecordsToPersist > 0`, only the newest N records (by `recorded_at`, then insertion order)
 *   are kept. Between prunes the queue may exceed `maxRecordsToPersist` by fewer than [PRUNE_EVERY] records.
 *   Pruning failures are logged, never thrown.
 *
 * Thread-safe: SQLiteDatabase serializes writes and multi-statement writes run in transactions; a lock guards the
 * insert counter and pruning. Reads run concurrently with writes (WAL).
 */
class SqliteLocationStore(
    private val database: TrackingDatabase,
    private val configStore: ConfigStore,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) : LocationStore {
    private val pruneLock = Any()

    @Volatile
    private var initialPruneDone = false

    /** Guarded by [pruneLock]. */
    private var insertsSincePrune = 0

    override suspend fun insert(record: Record) = io("insert") { db ->
        val values = ContentValues(4).apply {
            put(RecordsTable.UUID, record.uuid)
            put(RecordsTable.EVENT, record.event.wire)
            put(RecordsTable.RECORDED_AT, record.recordedAt)
            put(RecordsTable.JSON, encoding("record ${record.uuid}") { RecordJson.toJson(record).toString() })
        }
        synchronized(pruneLock) {
            val rowId = db.insertWithOnConflict(RecordsTable.NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            if (rowId == -1L) throw TrackingException(ErrorCode.IO_ERROR, "record ${record.uuid} was not inserted")
            insertsSincePrune++
            if (insertsSincePrune >= PRUNE_EVERY) prune(db)
        }
    }

    override suspend fun list(limit: Int, events: Set<RecordEvent>?): List<Record> {
        if (limit == 0 || events?.isEmpty() == true) return emptyList()
        return io("list") { db ->
            var page = queryPage(db, limit, events)
            var passes = 0
            while (page.undecodable.isNotEmpty() && passes++ < MAX_REPAIR_PASSES) {
                val removed =
                    db.deleteUndecodable(RecordsTable.NAME, RecordsTable.UUID, RecordsTable.JSON, page.undecodable)
                Logger.w(TAG, "deleted $removed undecodable record(s)")
                // Without a limit every row was read already; with one, re-query to refill the page.
                if (limit < 0) break
                page = queryPage(db, limit, events)
            }
            page.records
        }
    }

    override suspend fun count(events: Set<RecordEvent>?): Int {
        if (events?.isEmpty() == true) return 0
        return io("count") { db ->
            if (events == null) {
                DatabaseUtils.queryNumEntries(db, RecordsTable.NAME).toInt()
            } else {
                DatabaseUtils.queryNumEntries(db, RecordsTable.NAME, eventFilter(events), eventArgs(events)).toInt()
            }
        }
    }

    override suspend fun delete(uuids: Collection<String>): Int {
        if (uuids.isEmpty()) return 0
        return io("delete") { db -> db.deleteIn(RecordsTable.NAME, RecordsTable.UUID, uuids) }
    }

    override suspend fun deleteAll(): Int = io("deleteAll") { db ->
        // "1" (not null) makes SQLite report the number of deleted rows.
        db.delete(RecordsTable.NAME, "1", null)
    }

    override suspend fun markAttempt(uuids: Collection<String>, at: Long) {
        if (uuids.isEmpty()) return
        io("markAttempt") { db ->
            db.inTransaction {
                // One parameter is `at`, so each chunk leaves room for it.
                for (chunk in uuids.distinct().chunked(MAX_SQL_PARAMS - 1)) {
                    db.executeUpdateDelete(
                        "UPDATE ${RecordsTable.NAME} " +
                            "SET ${RecordsTable.ATTEMPTS} = ${RecordsTable.ATTEMPTS} + 1, " +
                            "${RecordsTable.LAST_ATTEMPT_AT} = ? " +
                            "WHERE ${RecordsTable.UUID} IN (${placeholders(chunk.size)})",
                    ) {
                        bindLong(1, at)
                        chunk.forEachIndexed { i, uuid -> bindString(i + 2, uuid) }
                    }
                }
            }
        }
    }

    /** Opens the database on [AppDispatchers.io], prunes once per instance, then runs [block]. */
    private suspend fun <T> io(what: String, block: (SQLiteDatabase) -> T): T = withContext(dispatchers.io) {
        ioErrors("location store $what") {
            val db = database.writableDatabase
            pruneOnFirstUse(db)
            block(db)
        }
    }

    private fun pruneOnFirstUse(db: SQLiteDatabase) {
        if (initialPruneDone) return
        synchronized(pruneLock) {
            if (!initialPruneDone) {
                prune(db)
                initialPruneDone = true
            }
        }
    }

    /** Deletes expired and surplus records (see class docs). Caller holds [pruneLock]. Never throws. */
    private fun prune(db: SQLiteDatabase) {
        insertsSincePrune = 0
        val persistence = configStore.config.value.persistence
        try {
            var expired = 0
            var surplus = 0
            db.inTransaction {
                if (persistence.maxDaysToPersist > 0) {
                    val cutoff = clock.now() - persistence.maxDaysToPersist * DAY_MS
                    expired = db.executeUpdateDelete(
                        "DELETE FROM ${RecordsTable.NAME} WHERE ${RecordsTable.RECORDED_AT} < ?",
                    ) { bindLong(1, cutoff) }
                }
                if (persistence.maxRecordsToPersist > 0) {
                    surplus = db.executeUpdateDelete(
                        "DELETE FROM ${RecordsTable.NAME} WHERE rowid IN (SELECT rowid FROM ${RecordsTable.NAME} " +
                            "ORDER BY ${RecordsTable.RECORDED_AT} DESC, rowid DESC LIMIT -1 OFFSET ?)",
                    ) { bindLong(1, persistence.maxRecordsToPersist.toLong()) }
                }
            }
            if (expired + surplus > 0) {
                Logger.i(
                    TAG,
                    "pruned $expired record(s) older than ${persistence.maxDaysToPersist} day(s) and " +
                        "$surplus over the limit of ${persistence.maxRecordsToPersist}",
                )
            }
        } catch (e: Exception) {
            Logger.e(TAG, "pruning failed", e)
        }
    }

    private class Page(val records: List<Record>, val undecodable: List<UndecodableRow>)

    private fun queryPage(db: SQLiteDatabase, limit: Int, events: Set<RecordEvent>?): Page {
        val sql = StringBuilder("SELECT ${RecordsTable.UUID}, ${RecordsTable.JSON} FROM ${RecordsTable.NAME}")
        if (events != null) sql.append(" WHERE ").append(eventFilter(events))
        sql.append(" ORDER BY ${RecordsTable.RECORDED_AT} ASC, rowid ASC")
        if (limit > 0) sql.append(" LIMIT ").append(limit)
        val records = ArrayList<Record>()
        val undecodable = ArrayList<UndecodableRow>()
        db.rawQuery(sql.toString(), events?.let { eventArgs(it) }).use { cursor ->
            while (cursor.moveToNext()) {
                val uuid = cursor.getString(0)
                val json = cursor.getString(1)
                try {
                    records += RecordJson.fromJson(JSONObject(json))
                } catch (e: Exception) {
                    Logger.e(TAG, "skipping undecodable record $uuid", e)
                    undecodable += UndecodableRow(uuid, json)
                }
            }
        }
        return Page(records, undecodable)
    }

    private fun eventFilter(events: Set<RecordEvent>): String =
        "${RecordsTable.EVENT} IN (${placeholders(events.size)})"

    private fun eventArgs(events: Set<RecordEvent>): Array<String> = events.map { it.wire }.toTypedArray()

    companion object {
        /** Pruning runs after this many inserts (and on first use). */
        const val PRUNE_EVERY = 50

        /** Bounds the refill loop of [list] when many undecodable rows are found. */
        private const val MAX_REPAIR_PASSES = 10

        private const val DAY_MS = 86_400_000L
        private const val TAG = "LT.LocationStore"
    }
}
