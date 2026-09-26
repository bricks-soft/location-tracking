// STUB — owned by Unit 10 (SQLite). Replace this implementation.
package com.brickssoft.locationtracking.data

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent

/** SQLite record queue. Stub: stores nothing. */
@Suppress("unused")
class SqliteLocationStore(
    private val database: TrackingDatabase,
    private val configStore: ConfigStore,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) : LocationStore {
    override suspend fun insert(record: Record) = Unit

    override suspend fun list(limit: Int, events: Set<RecordEvent>?): List<Record> = emptyList()

    override suspend fun count(events: Set<RecordEvent>?): Int = 0

    override suspend fun delete(uuids: Collection<String>): Int = 0

    override suspend fun deleteAll(): Int = 0

    override suspend fun markAttempt(uuids: Collection<String>, at: Long) = Unit
}
