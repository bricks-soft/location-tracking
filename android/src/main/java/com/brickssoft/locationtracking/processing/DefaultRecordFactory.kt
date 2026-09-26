// STUB — owned by Unit 9 (Processing). Replace this implementation.
package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.ProviderFactory
import org.json.JSONObject
import java.util.UUID

/** Builds records. Stub: clock metadata only; no runtime state, battery, backend or extras merging. */
@Suppress("unused")
class DefaultRecordFactory(
    private val configStore: ConfigStore,
    private val device: DeviceMonitor,
    private val providers: ProviderFactory,
    private val clock: Clock,
) : RecordFactory {
    override fun create(
        event: RecordEvent,
        location: TrackedLocation?,
        extras: String?,
        geofence: GeofenceHit?,
        provider: ProviderState?,
        reason: String?,
    ): Record = Record(
        uuid = UUID.randomUUID().toString(),
        event = event,
        location = location,
        recordedAt = clock.now(),
        elapsedRealtimeMs = clock.elapsedRealtime(),
        bootCount = clock.bootCount(),
        isMoving = false,
        odometer = 0.0,
        activity = ActivitySample.UNKNOWN,
        battery = BatterySnapshot.UNKNOWN,
        backend = null,
        extras = extras,
        geofence = geofence,
        provider = provider,
        reason = reason,
    )

    override fun fromExternal(input: JSONObject): Record = create(RecordEvent.LOCATION, null)
}
