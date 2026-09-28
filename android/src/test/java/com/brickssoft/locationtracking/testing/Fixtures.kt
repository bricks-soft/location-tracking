package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.HeartbeatMeta
import com.brickssoft.locationtracking.model.LatLng
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import kotlin.math.cos

/** Builders for test data. Defaults match the golden record of architecture §2. */
object Fixtures {
    const val LAT = 24.7136
    const val LNG = 46.6753

    /** 2026-09-26T10:15:30.123Z */
    const val FIX_TIME = 1_790_417_730_123L

    /** 2026-09-26T10:15:30.456Z (= FakeClock.DEFAULT_NOW) */
    const val RECORDED_AT = FakeClock.DEFAULT_NOW

    private const val METERS_PER_DEGREE = 111_320.0

    fun location(
        latitude: Double = LAT,
        longitude: Double = LNG,
        accuracy: Float = 5.2f,
        altitude: Double? = 612.3,
        altitudeAccuracy: Float? = 3.0f,
        speed: Float? = 13.4f,
        speedAccuracy: Float? = 0.8f,
        heading: Float? = 271.5f,
        headingAccuracy: Float? = 5.0f,
        time: Long = FIX_TIME,
        elapsedRealtimeNanos: Long = 0L,
        provider: String? = null,
        isMock: Boolean = false,
    ) = TrackedLocation(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        altitude = altitude,
        altitudeAccuracy = altitudeAccuracy,
        speed = speed,
        speedAccuracy = speedAccuracy,
        heading = heading,
        headingAccuracy = headingAccuracy,
        time = time,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        provider = provider,
        isMock = isMock,
    )

    /** [from] moved by the given meters (equirectangular approximation) and [timeDeltaMs] later. */
    fun moved(from: TrackedLocation, northMeters: Double, eastMeters: Double = 0.0, timeDeltaMs: Long = 1_000L) =
        from.copy(
            latitude = from.latitude + northMeters / METERS_PER_DEGREE,
            longitude = from.longitude + eastMeters / (METERS_PER_DEGREE * cos(Math.toRadians(from.latitude))),
            time = from.time + timeDeltaMs,
            elapsedRealtimeNanos = if (from.elapsedRealtimeNanos == 0L) 0L else from.elapsedRealtimeNanos + timeDeltaMs * 1_000_000,
        )

    fun record(
        uuid: String = "0d6c6a1e-6c1e-4b8e-9d8f-2b6f3f0f7a11",
        event: RecordEvent = RecordEvent.LOCATION,
        location: TrackedLocation? = location(),
        recordedAt: Long = RECORDED_AT,
        elapsedRealtimeMs: Long = FakeClock.DEFAULT_ELAPSED,
        bootCount: Int = FakeClock.DEFAULT_BOOT_COUNT,
        isMoving: Boolean = true,
        odometer: Double = 1532.4,
        activity: ActivitySample = ActivitySample(ActivityType.IN_VEHICLE, 92),
        battery: BatterySnapshot = BatterySnapshot(0.81f, false),
        backend: ProviderKind? = ProviderKind.GMS,
        extras: String? = null,
        geofence: GeofenceHit? = null,
        provider: ProviderState? = null,
        reason: String? = null,
        heartbeat: HeartbeatMeta? = null,
    ) = Record(
        uuid = uuid,
        event = event,
        location = location,
        recordedAt = recordedAt,
        elapsedRealtimeMs = elapsedRealtimeMs,
        bootCount = bootCount,
        isMoving = isMoving,
        odometer = odometer,
        activity = activity,
        battery = battery,
        backend = backend,
        extras = extras,
        geofence = geofence,
        provider = provider,
        reason = reason,
        heartbeat = heartbeat,
    )

    fun providerState(
        enabled: Boolean = true,
        gps: Boolean = true,
        network: Boolean = true,
        permission: PermissionLevel = PermissionLevel.ALWAYS,
        accuracy: AccuracyLevel = AccuracyLevel.PRECISE,
        backend: ProviderKind = ProviderKind.GMS,
    ) = ProviderState(enabled, gps, network, permission, accuracy, backend)

    fun circle(
        identifier: String = "home",
        latitude: Double = LAT,
        longitude: Double = LNG,
        radius: Float = 100f,
        notifyOnEntry: Boolean = true,
        notifyOnExit: Boolean = true,
        notifyOnDwell: Boolean = false,
        loiteringDelay: Long = 30_000L,
        extras: String? = null,
    ) = GeofenceSpec(
        identifier = identifier,
        latitude = latitude,
        longitude = longitude,
        radius = radius,
        notifyOnEntry = notifyOnEntry,
        notifyOnExit = notifyOnExit,
        notifyOnDwell = notifyOnDwell,
        loiteringDelay = loiteringDelay,
        extras = extras,
    )

    /** A polygon (default: ~200 m square centred on [LAT]/[LNG]); the enclosing circle is left at 0. */
    fun polygon(
        identifier: String = "zone",
        vertices: List<LatLng> = square(LAT, LNG, 100.0),
        notifyOnEntry: Boolean = true,
        notifyOnExit: Boolean = true,
        notifyOnDwell: Boolean = false,
        loiteringDelay: Long = 30_000L,
        extras: String? = null,
    ) = GeofenceSpec(
        identifier = identifier,
        latitude = 0.0,
        longitude = 0.0,
        radius = 0f,
        vertices = vertices,
        notifyOnEntry = notifyOnEntry,
        notifyOnExit = notifyOnExit,
        notifyOnDwell = notifyOnDwell,
        loiteringDelay = loiteringDelay,
        extras = extras,
    )

    /** Four vertices [halfSideMeters] north/south/east/west of the centre, counter-clockwise from south-west. */
    fun square(latitude: Double, longitude: Double, halfSideMeters: Double): List<LatLng> {
        val dLat = halfSideMeters / METERS_PER_DEGREE
        val dLng = halfSideMeters / (METERS_PER_DEGREE * cos(Math.toRadians(latitude)))
        return listOf(
            LatLng(latitude - dLat, longitude - dLng),
            LatLng(latitude - dLat, longitude + dLng),
            LatLng(latitude + dLat, longitude + dLng),
            LatLng(latitude + dLat, longitude - dLng),
        )
    }
}
