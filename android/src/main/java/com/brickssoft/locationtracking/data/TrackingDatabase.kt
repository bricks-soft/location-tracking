package com.brickssoft.locationtracking.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import java.io.File

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
 * The plugin opens it at [location]: `noBackupFilesDir/location_tracking.db`, so the queue is not copied by Android
 * Auto Backup or device-to-device transfer (recommendation 7). A database left in the default databases folder by an
 * older version is moved there once, without losing records.
 *
 * @param name database file name or absolute path; `null` creates an in-memory database.
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

        /** Files SQLite may keep next to a database. */
        private val SIDE_FILES = listOf("-wal", "-shm", "-journal")

        /**
         * The absolute path of the plugin's database under `noBackupFilesDir`. A database still in the default
         * databases folder (an older version) is moved there first: its write-ahead log is folded into the main file
         * (`PRAGMA wal_checkpoint(TRUNCATE)` and close), then the file is renamed (same file system), then the old side
         * files are deleted. If anything fails, the old path is returned and nothing is lost. If both exist (a
         * downgrade and upgrade), the one under `noBackupFilesDir` is used and the old one is left in place.
         */
        fun location(context: Context): String {
            val app = context.applicationContext ?: context
            val target = File(app.noBackupFilesDir, Constants.DATABASE_NAME)
            val legacy = app.getDatabasePath(Constants.DATABASE_NAME)
            if (!legacy.exists()) return target.path
            if (target.exists()) {
                Logger.w(TAG, "databases/${Constants.DATABASE_NAME} and its no_backup copy both exist; using the no_backup one")
                return target.path
            }
            return try {
                SQLiteDatabase.openDatabase(legacy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
                target.parentFile?.mkdirs()
                if (!legacy.renameTo(target)) {
                    Logger.w(TAG, "could not move the database to no_backup; keeping it in databases/")
                    return legacy.path
                }
                for (suffix in SIDE_FILES) File(legacy.path + suffix).delete()
                Logger.i(TAG, "database moved to no_backup (kept out of Auto Backup and device transfer)")
                target.path
            } catch (e: Exception) {
                Logger.e(TAG, "could not move the database to no_backup; keeping it in databases/", e)
                legacy.path
            }
        }
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
