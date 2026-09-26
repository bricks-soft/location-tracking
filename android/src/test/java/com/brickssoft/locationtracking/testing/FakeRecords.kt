package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.JsonUtil
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.record.RecordSink
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic [RecordFactory]: uuids "rec-1", "rec-2", ...; times from [clock]. isMoving / odometer /
 * activity come from [configStore]'s runtime when given, else from the mutable fields. [created] records
 * every record built.
 */
class FakeRecordFactory(
    private val clock: Clock = FakeClock(),
    private val configStore: ConfigStore? = null,
    @Volatile var backend: ProviderKind? = ProviderKind.GMS,
) : RecordFactory {
    private val counter = AtomicInteger()
    val created = CopyOnWriteArrayList<Record>()

    @Volatile
    var isMoving = false

    @Volatile
    var odometer = 0.0

    @Volatile
    var activity = ActivitySample.UNKNOWN

    @Volatile
    var battery = BatterySnapshot(0.5f, false)

    override fun create(
        event: RecordEvent,
        location: TrackedLocation?,
        extras: String?,
        geofence: GeofenceHit?,
        provider: ProviderState?,
        reason: String?,
    ): Record {
        val runtime = configStore?.runtime?.value
        val record = Record(
            uuid = "rec-${counter.incrementAndGet()}",
            event = event,
            location = location,
            recordedAt = clock.now(),
            elapsedRealtimeMs = clock.elapsedRealtime(),
            bootCount = clock.bootCount(),
            isMoving = runtime?.isMoving ?: isMoving,
            odometer = runtime?.odometer ?: odometer,
            activity = runtime?.activity ?: activity,
            battery = battery,
            backend = backend,
            extras = extras,
            geofence = geofence,
            provider = provider,
            reason = reason,
        )
        created += record
        return record
    }

    /** Minimal `InsertLocationInput` parsing: coords.latitude/longitude/accuracy, timestamp, event, is_moving, extras. */
    override fun fromExternal(input: JSONObject): Record {
        val coords = input.optJSONObject("coords") ?: JSONObject()
        val location = TrackedLocation(
            latitude = JsonUtil.optDouble(coords, "latitude") ?: 0.0,
            longitude = JsonUtil.optDouble(coords, "longitude") ?: 0.0,
            accuracy = JsonUtil.optFloat(coords, "accuracy") ?: 0f,
            time = Iso8601.parse(JsonUtil.optString(input, "timestamp")) ?: clock.now(),
        )
        val event = RecordEvent.fromWire(JsonUtil.optString(input, "event")) ?: RecordEvent.LOCATION
        val record = create(event, location, input.optJSONObject("extras")?.toString())
        val moving = JsonUtil.optBoolean(input, "is_moving") ?: return record
        return record.copy(isMoving = moving).also { created[created.lastIndex] = it }
    }
}

/**
 * Collecting [RecordSink]. If [store] is given, records are also inserted into it (passthrough).
 * Does not emit events, update runtime or touch heartbeat/sync (use the real DefaultRecordSink for that).
 */
class FakeRecordSink(private val store: LocationStore? = null) : RecordSink {
    val records = CopyOnWriteArrayList<Record>()

    override suspend fun submit(record: Record): Record {
        records += record
        store?.insert(record)
        return record
    }

    fun ofEvent(event: RecordEvent): List<Record> = records.filter { it.event == event }
}
