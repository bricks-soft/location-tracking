// STUB — owned by Unit 10 (SQLite). Replace this implementation.
package com.brickssoft.locationtracking.data

import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.model.GeofenceSpec

/** SQLite geofence registry. Stub: stores nothing. */
@Suppress("unused")
class SqliteGeofenceStore(
    private val database: TrackingDatabase,
    private val dispatchers: AppDispatchers,
) : GeofenceStore {
    override suspend fun upsert(geofences: List<GeofenceSpec>) = Unit

    override suspend fun remove(ids: Collection<String>): Int = 0

    override suspend fun removeAll(): Int = 0

    override suspend fun all(): List<GeofenceSpec> = emptyList()

    override suspend fun get(id: String): GeofenceSpec? = null

    override suspend fun count(): Int = 0

    override suspend fun setRuntime(id: String, runtime: GeofenceRuntime) = Unit

    override suspend fun runtimes(): Map<String, GeofenceRuntime> = emptyMap()
}
