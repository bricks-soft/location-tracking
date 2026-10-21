package com.brickssoft.locationtracking.record

import android.database.sqlite.SQLiteFullException
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.RecordHooks
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import kotlin.coroutines.cancellation.CancellationException

/** Single entry point for persisting a record. */
interface RecordSink {
    /** Persists [record], updates runtime state, restarts the heartbeat window, emits events and triggers sync. */
    suspend fun submit(record: Record): Record
}

/**
 * Default [RecordSink]. Order of operations:
 * 1. `store.insert(record)`, then `hooks.dispatch(record)` (round 2, see below);
 * 2. `updateRuntime { lastRecordAt/Elapsed/BootCount, lastLocation = record.location ?: it.lastLocation,
 *    lastHeartbeatAt if HEARTBEAT }`;
 * 3. `heartbeat.onRecordRecorded(record)`;
 * 4. emit: LOCATION / CURRENT_POSITION / WATCH_POSITION -> Location; MOTIONCHANGE -> Location + MotionChange;
 *    HEARTBEAT -> Heartbeat; every other event is emitted by its producer;
 * 5. `syncer.onRecordInserted(record)`.
 *
 * If the insert fails, the failure is logged, steps 2-4 still run (live listeners still get the record) and
 * step 5 is skipped because the record is not queued. A failure because the storage is full (`SQLiteFullException`)
 * also emits a `StorageFull` event (recommendation 16), so the app can tell the user.
 *
 * Round 2: [hooks] receives every record right after step 1, also when the insert failed or the caller was cancelled
 * during the insert (a companion listener keeps its own audit trail, so a local database failure must not hide the
 * record from it). Because this happens before step 4, a native listener receives a record before the events that
 * carry it (see `api/NativeListeners`).
 */
class DefaultRecordSink(
    private val store: LocationStore,
    private val configStore: ConfigStore,
    private val heartbeat: HeartbeatScheduler,
    private val syncer: HttpSyncer,
    private val events: EventBus,
    private val hooks: RecordHooks = RecordHooks(),
    /** Free bytes of the app's storage, for the `StorageFull` event; null if unknown. */
    private val diskFreeBytes: () -> Long? = { null },
) : RecordSink {
    override suspend fun submit(record: Record): Record {
        val stored = try {
            store.insert(record)
            true
        } catch (e: CancellationException) {
            // The caller was cancelled during the insert; the row may be committed already (a blocking SQLite write
            // finishes before the coroutine sees the cancellation). The companion keeps its own audit, so it gets the
            // record in either case, like after a failed insert.
            hooks.dispatch(record)
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "failed to persist ${record.event.wire} record ${record.uuid}", e)
            if (isStorageFull(e)) {
                val free = try {
                    diskFreeBytes()
                } catch (x: Exception) {
                    null
                }
                events.emit(TrackingEvent.StorageFull(record.uuid, record.event, free))
            }
            false
        }

        hooks.dispatch(record)

        configStore.updateRuntime { runtime ->
            runtime.copy(
                lastRecordAt = record.recordedAt,
                lastRecordElapsed = record.elapsedRealtimeMs,
                lastRecordBootCount = record.bootCount,
                lastLocation = record.location ?: runtime.lastLocation,
                lastHeartbeatAt = if (record.event == RecordEvent.HEARTBEAT) record.recordedAt else runtime.lastHeartbeatAt,
            )
        }

        heartbeat.onRecordRecorded(record)

        when (record.event) {
            RecordEvent.LOCATION, RecordEvent.CURRENT_POSITION, RecordEvent.WATCH_POSITION ->
                events.emit(TrackingEvent.Location(record))
            RecordEvent.MOTIONCHANGE -> {
                events.emit(TrackingEvent.Location(record))
                events.emit(TrackingEvent.MotionChange(record.isMoving, record))
            }
            RecordEvent.HEARTBEAT -> events.emit(TrackingEvent.Heartbeat(record))
            RecordEvent.GEOFENCE, RecordEvent.TRACKING_START, RecordEvent.TRACKING_STOP, RecordEvent.PROVIDERCHANGE ->
                Unit // emitted by the producer
        }

        if (stored) syncer.onRecordInserted(record)
        return record
    }

    private companion object {
        const val TAG = "LT.RecordSink"

        /** True if [e] or one of its causes is `SQLiteFullException` (or an "out of disk space" I/O error). */
        fun isStorageFull(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
            it is SQLiteFullException || it.message?.contains("SQLITE_FULL") == true ||
                it.message?.contains("ENOSPC") == true
        }
    }
}
