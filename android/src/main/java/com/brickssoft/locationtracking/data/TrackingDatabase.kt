package com.brickssoft.locationtracking.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger

/**
 * SQLiteOpenHelper for [Constants.DATABASE_NAME] (schema [VERSION]), shared by [SqliteLocationStore] and
 * [SqliteGeofenceStore]. Write-ahead logging is enabled, so readers never block the single writer.
 *
 * Schema v1:
 * - `records`: the upload queue. `json` is `RecordJson.toJson(record)` without `sent_at`; `event` and `recorded_at`
 *   duplicate the JSON for filtering, ordering and pruning; `attempts` / `last_attempt_at` track failed uploads.
 *   Indexed on `recorded_at` and on `(event, recorded_at)`.
 * - `geofences`: `json` is `GeofenceJson.toJson(spec)`; `runtime_json` is `{insideCircle, insidePolygon, enteredAt}`
 *   or null.
 *
 * @param name database file name; `null` creates an in-memory database.
 */
class TrackingDatabase(
    context: Context,
    name: String? = Constants.DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext ?: context, name, null, VERSION) {

    init {
        // Must be set before the database is first opened.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ${RecordsTable.NAME} (" +
                "${RecordsTable.UUID} TEXT PRIMARY KEY NOT NULL, " +
                "${RecordsTable.EVENT} TEXT NOT NULL, " +
                "${RecordsTable.RECORDED_AT} INTEGER NOT NULL, " +
                "${RecordsTable.JSON} TEXT NOT NULL, " +
                "${RecordsTable.ATTEMPTS} INTEGER NOT NULL DEFAULT 0, " +
                "${RecordsTable.LAST_ATTEMPT_AT} INTEGER)",
        )
        db.execSQL(
            "CREATE INDEX ${RecordsTable.INDEX_RECORDED_AT} ON ${RecordsTable.NAME}(${RecordsTable.RECORDED_AT})",
        )
        db.execSQL(
            "CREATE INDEX ${RecordsTable.INDEX_EVENT_RECORDED_AT} " +
                "ON ${RecordsTable.NAME}(${RecordsTable.EVENT}, ${RecordsTable.RECORDED_AT})",
        )
        db.execSQL(
            "CREATE TABLE ${GeofencesTable.NAME} (" +
                "${GeofencesTable.ID} TEXT PRIMARY KEY NOT NULL, " +
                "${GeofencesTable.JSON} TEXT NOT NULL, " +
                "${GeofencesTable.RUNTIME_JSON} TEXT)",
        )
        Logger.i(TAG, "created database schema v$VERSION")
    }

    /**
     * Migration strategy (only v1 exists today). `SQLiteOpenHelper` runs this inside a transaction.
     *
     * Every schema change bumps [VERSION] and adds one step here, applied in order:
     * `for (v in oldVersion + 1..newVersion) when (v) { 2 -> migrateTo2(db) ... }`.
     * - `records` must never be dropped or recreated: it holds queued heartbeat and audit records that have not
     *   been uploaded yet. Steps are additive (`ALTER TABLE ... ADD COLUMN` with a default, `CREATE INDEX IF NOT
     *   EXISTS`), or, for incompatible changes, create `records_new`, `INSERT INTO records_new SELECT ... FROM
     *   records`, drop the old table and rename the new one, all in this transaction.
     * - The `json` column is self-describing, so new columns can be backfilled from it in the same step.
     * - `geofences` follows the same rule (the app registered those regions and expects them back).
     * - Steps must be idempotent (check `PRAGMA table_info` before `ADD COLUMN`, use `IF NOT EXISTS`), because
     *   [onDowngrade] keeps a newer schema and a later upgrade runs the same steps again.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Logger.i(TAG, "database upgrade v$oldVersion -> v$newVersion: no migration steps defined")
    }

    /**
     * Keeps the newer schema and its data (the default would make every open fail, losing the queue). Because
     * migrations are additive, this version's statements still work on it.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Logger.w(TAG, "database downgrade v$oldVersion -> v$newVersion: keeping the newer schema and its data")
    }

    companion object {
        /** Current schema version. */
        const val VERSION = 1

        private const val TAG = "LT.Database"
    }
}

/** Column names of the `records` table. */
internal object RecordsTable {
    const val NAME = "records"
    const val UUID = "uuid"
    const val EVENT = "event"
    const val RECORDED_AT = "recorded_at"
    const val JSON = "json"
    const val ATTEMPTS = "attempts"
    const val LAST_ATTEMPT_AT = "last_attempt_at"
    const val INDEX_RECORDED_AT = "records_recorded_at"
    const val INDEX_EVENT_RECORDED_AT = "records_event_recorded_at"
}

/** Column names of the `geofences` table. */
internal object GeofencesTable {
    const val NAME = "geofences"
    const val ID = "id"
    const val JSON = "json"
    const val RUNTIME_JSON = "runtime_json"
}
