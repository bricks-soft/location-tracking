package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.data.GeofenceRuntime
import com.brickssoft.locationtracking.data.GeofenceStore
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent

/** In-memory [LocationStore]. `list` is oldest first (by recordedAt, then insertion order). */
class FakeLocationStore : LocationStore {
    private val records = LinkedHashMap<String, Record>()
    private val attemptCounts = HashMap<String, Int>()
    private val lastAttempts = HashMap<String, Long>()

    /** If set, `insert` throws it (e.g. to test I/O failures). */
    @Volatile
    var failInsertWith: Exception? = null

    /** Snapshot, oldest first. */
    val all: List<Record> get() = synchronized(this) { sorted(records.values) }

    fun attempts(uuid: String): Int = synchronized(this) { attemptCounts[uuid] ?: 0 }

    fun lastAttemptAt(uuid: String): Long? = synchronized(this) { lastAttempts[uuid] }

    override suspend fun insert(record: Record) {
        failInsertWith?.let { throw it }
        synchronized(this) { records[record.uuid] = record }
    }

    override suspend fun list(limit: Int, events: Set<RecordEvent>?): List<Record> = synchronized(this) {
        val matching = sorted(records.values).filter { events == null || it.event in events }
        if (limit >= 0) matching.take(limit) else matching
    }

    override suspend fun count(events: Set<RecordEvent>?): Int = synchronized(this) {
        records.values.count { events == null || it.event in events }
    }

    override suspend fun delete(uuids: Collection<String>): Int = synchronized(this) {
        uuids.count { records.remove(it) != null }
    }

    override suspend fun deleteAll(): Int = synchronized(this) {
        val n = records.size
        records.clear()
        n
    }

    override suspend fun markAttempt(uuids: Collection<String>, at: Long) = synchronized(this) {
        for (uuid in uuids) {
            if (uuid !in records) continue
            attemptCounts[uuid] = (attemptCounts[uuid] ?: 0) + 1
            lastAttempts[uuid] = at
        }
    }

    private fun sorted(values: Collection<Record>): List<Record> = values.sortedBy { it.recordedAt }
}

/** In-memory [GeofenceStore] (insertion ordered). */
class FakeGeofenceStore : GeofenceStore {
    private val geofences = LinkedHashMap<String, GeofenceSpec>()
    private val runtime = LinkedHashMap<String, GeofenceRuntime>()

    override suspend fun upsert(geofences: List<GeofenceSpec>) = synchronized(this) {
        for (g in geofences) this.geofences[g.identifier] = g
    }

    override suspend fun remove(ids: Collection<String>): Int = synchronized(this) {
        ids.count { id ->
            runtime.remove(id)
            geofences.remove(id) != null
        }
    }

    override suspend fun removeAll(): Int = synchronized(this) {
        val n = geofences.size
        geofences.clear()
        runtime.clear()
        n
    }

    override suspend fun all(): List<GeofenceSpec> = synchronized(this) { geofences.values.toList() }

    override suspend fun get(id: String): GeofenceSpec? = synchronized(this) { geofences[id] }

    override suspend fun count(): Int = synchronized(this) { geofences.size }

    override suspend fun setRuntime(id: String, runtime: GeofenceRuntime) = synchronized(this) {
        this.runtime[id] = runtime
    }

    override suspend fun runtimes(): Map<String, GeofenceRuntime> = synchronized(this) { runtime.toMap() }
}
