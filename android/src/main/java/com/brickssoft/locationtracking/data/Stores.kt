package com.brickssoft.locationtracking.data

import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent

/**
 * The record queue. Pruning (`maxDaysToPersist` / `maxRecordsToPersist`) is internal: on open and every 50 inserts.
 */
interface LocationStore {
    suspend fun insert(record: Record)

    /** Oldest first; `limit < 0` = all; `events == null` = every event. */
    suspend fun list(limit: Int = -1, events: Set<RecordEvent>? = null): List<Record>

    suspend fun count(events: Set<RecordEvent>? = null): Int

    suspend fun delete(uuids: Collection<String>): Int

    suspend fun deleteAll(): Int

    /** Increments `attempts` and sets `last_attempt_at`. */
    suspend fun markAttempt(uuids: Collection<String>, at: Long)
}

/** Per-geofence runtime used for polygon hit-testing and dwell synthesis. */
data class GeofenceRuntime(val insideCircle: Boolean, val insidePolygon: Boolean, val enteredAt: Long?)

interface GeofenceStore {
    suspend fun upsert(geofences: List<GeofenceSpec>)

    suspend fun remove(ids: Collection<String>): Int

    suspend fun removeAll(): Int

    suspend fun all(): List<GeofenceSpec>

    suspend fun get(id: String): GeofenceSpec?

    suspend fun count(): Int

    suspend fun setRuntime(id: String, runtime: GeofenceRuntime)

    suspend fun runtimes(): Map<String, GeofenceRuntime>
}
