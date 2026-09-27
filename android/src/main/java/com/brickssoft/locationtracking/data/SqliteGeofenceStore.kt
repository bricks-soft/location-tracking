package com.brickssoft.locationtracking.data

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceJson
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.JsonUtil
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SQLite geofence registry (table `geofences` of [TrackingDatabase]). All work runs on [AppDispatchers.io];
 * failures are thrown as `TrackingException(IO_ERROR)`.
 *
 * - Specs are stored as `GeofenceJson`; [all] is ordered by identifier.
 * - [upsert] replaces the spec but keeps an existing runtime; [remove] and [removeAll] delete the runtime too.
 * - [setRuntime] stores `{insideCircle, insidePolygon, enteredAt}` and is ignored for an unknown identifier.
 * - A row whose spec cannot be decoded is logged, skipped and deleted.
 *
 * Thread-safe: SQLiteDatabase serializes writes and [upsert] runs in one transaction. Reads run concurrently with
 * writes (WAL).
 */
class SqliteGeofenceStore(
    private val database: TrackingDatabase,
    private val dispatchers: AppDispatchers,
) : GeofenceStore {
    override suspend fun upsert(geofences: List<GeofenceSpec>) {
        if (geofences.isEmpty()) return
        io("upsert") { db ->
            val rows = geofences.map { g ->
                g.identifier to encoding("geofence '${g.identifier}'") { GeofenceJson.toJson(g).toString() }
            }
            db.inTransaction {
                for ((id, json) in rows) {
                    // Update first so an existing runtime_json is kept (no UPSERT syntax before SQLite 3.24 / API 30).
                    val values = ContentValues(2).apply { put(GeofencesTable.JSON, json) }
                    val updated = db.update(GeofencesTable.NAME, values, "${GeofencesTable.ID} = ?", arrayOf(id))
                    if (updated == 0) {
                        values.put(GeofencesTable.ID, id)
                        db.insertOrThrow(GeofencesTable.NAME, null, values)
                    }
                }
            }
        }
    }

    override suspend fun remove(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        return io("remove") { db -> db.deleteIn(GeofencesTable.NAME, GeofencesTable.ID, ids) }
    }

    override suspend fun removeAll(): Int = io("removeAll") { db ->
        // "1" (not null) makes SQLite report the number of deleted rows.
        db.delete(GeofencesTable.NAME, "1", null)
    }

    override suspend fun all(): List<GeofenceSpec> = io("all") { db -> query(db, null) }

    override suspend fun get(id: String): GeofenceSpec? = io("get") { db -> query(db, id).firstOrNull() }

    override suspend fun count(): Int = io("count") { db ->
        DatabaseUtils.queryNumEntries(db, GeofencesTable.NAME).toInt()
    }

    override suspend fun setRuntime(id: String, runtime: GeofenceRuntime) {
        io("setRuntime") { db ->
            val values = ContentValues(1).apply {
                put(GeofencesTable.RUNTIME_JSON, GeofenceRuntimeJson.toJson(runtime).toString())
            }
            val updated = db.update(GeofencesTable.NAME, values, "${GeofencesTable.ID} = ?", arrayOf(id))
            if (updated == 0) Logger.d(TAG, "ignoring runtime of unknown geofence '$id'")
        }
    }

    override suspend fun runtimes(): Map<String, GeofenceRuntime> = io("runtimes") { db ->
        val result = LinkedHashMap<String, GeofenceRuntime>()
        db.rawQuery(
            "SELECT ${GeofencesTable.ID}, ${GeofencesTable.RUNTIME_JSON} FROM ${GeofencesTable.NAME} " +
                "WHERE ${GeofencesTable.RUNTIME_JSON} IS NOT NULL ORDER BY ${GeofencesTable.ID}",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val runtime = JsonUtil.parseObject(cursor.getString(1))?.let { GeofenceRuntimeJson.fromJson(it) }
                if (runtime != null) result[id] = runtime else Logger.w(TAG, "skipping undecodable runtime of '$id'")
            }
        }
        result
    }

    private suspend fun <T> io(what: String, block: (SQLiteDatabase) -> T): T = withContext(dispatchers.io) {
        ioErrors("geofence store $what") { block(database.writableDatabase) }
    }

    /** Specs ordered by identifier ([id] = null: all). Undecodable rows are logged, skipped and deleted. */
    private fun query(db: SQLiteDatabase, id: String?): List<GeofenceSpec> {
        val result = ArrayList<GeofenceSpec>()
        val undecodable = ArrayList<UndecodableRow>()
        val where = if (id != null) " WHERE ${GeofencesTable.ID} = ?" else ""
        db.rawQuery(
            "SELECT ${GeofencesTable.ID}, ${GeofencesTable.JSON} FROM ${GeofencesTable.NAME}$where " +
                "ORDER BY ${GeofencesTable.ID}",
            id?.let { arrayOf(it) },
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val rowId = cursor.getString(0)
                val json = cursor.getString(1)
                try {
                    result += GeofenceJson.fromJson(JSONObject(json))
                } catch (e: Exception) {
                    Logger.e(TAG, "skipping undecodable geofence '$rowId'", e)
                    undecodable += UndecodableRow(rowId, json)
                }
            }
        }
        if (undecodable.isNotEmpty()) {
            val removed = db.deleteUndecodable(GeofencesTable.NAME, GeofencesTable.ID, GeofencesTable.JSON, undecodable)
            Logger.w(TAG, "deleted $removed undecodable geofence(s)")
        }
        return result
    }

    private companion object {
        const val TAG = "LT.GeofenceStore"
    }
}

/** `runtime_json` codec: `{"insideCircle":bool,"insidePolygon":bool,"enteredAt":ms|null}`. */
internal object GeofenceRuntimeJson {
    fun toJson(runtime: GeofenceRuntime): JSONObject = JSONObject()
        .put("insideCircle", runtime.insideCircle)
        .put("insidePolygon", runtime.insidePolygon)
        .put("enteredAt", JsonUtil.orNull(runtime.enteredAt))

    /** Lenient: missing flags are false, a missing `enteredAt` is null. */
    fun fromJson(json: JSONObject): GeofenceRuntime = GeofenceRuntime(
        insideCircle = JsonUtil.optBoolean(json, "insideCircle") ?: false,
        insidePolygon = JsonUtil.optBoolean(json, "insidePolygon") ?: false,
        enteredAt = JsonUtil.optLong(json, "enteredAt"),
    )
}
