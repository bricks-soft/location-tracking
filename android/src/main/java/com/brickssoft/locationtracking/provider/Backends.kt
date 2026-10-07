package com.brickssoft.locationtracking.provider

import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation

/** A continuous location request. */
data class LocationRequestSpec(
    val accuracy: DesiredAccuracy,
    val intervalMs: Long,
    val fastestIntervalMs: Long,
    val distanceFilterM: Float,
)

/** Receives batches of fixes, on any thread. */
fun interface LocationListener {
    fun onLocations(locations: List<TrackedLocation>)
}

/** Location updates and one-shot fixes from one backend. */
interface LocationBackend {
    val kind: ProviderKind

    /**
     * Supports multiple listeners; requesting again with the same listener replaces its request. A `distanceFilterM`
     * of 0 means no distance filter (the engine applies its own elastic filter). Never throws: failures such as a
     * missing permission are logged and the listener receives nothing.
     */
    fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener)

    fun removeUpdates(listener: LocationListener)

    /** The OS's cached last location. Implementations may throw `TrackingException(PERMISSION_DENIED)`; callers catch. */
    suspend fun getLastLocation(): TrackedLocation?

    /** Returns null on timeout. @throws com.brickssoft.locationtracking.core.TrackingException PERMISSION_DENIED */
    suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation?
}

/** Activity recognition. Samples are delivered through a PendingIntent receiver to [ActivitySink]. */
interface ActivityBackend {
    val kind: ProviderKind
    val isSupported: Boolean

    /** Returns false if updates could not be requested. */
    fun start(intervalMs: Long): Boolean

    fun stop()
}

/** A geofence as registered with the OS. */
data class OsGeofence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val onEntry: Boolean,
    val onExit: Boolean,
    val onDwell: Boolean,
    val loiteringDelayMs: Int,
    val initialTriggerEntry: Boolean,
)

/** A transition reported by the OS; [location] is the triggering fix if the backend provides one. */
data class OsGeofenceTransition(val id: String, val action: GeofenceAction, val location: TrackedLocation?)

/** OS geofencing. Transitions are delivered through a PendingIntent receiver to [GeofenceTransitionSink]. */
interface GeofenceBackend {
    val kind: ProviderKind
    val supportsDwell: Boolean

    /** @throws com.brickssoft.locationtracking.core.TrackingException */
    suspend fun add(regions: List<OsGeofence>)

    /** @throws com.brickssoft.locationtracking.core.TrackingException */
    suspend fun remove(ids: List<String>)

    /** @throws com.brickssoft.locationtracking.core.TrackingException */
    suspend fun removeAll()
}

/**
 * The three backends of one SDK. Implementations are created only by reflection through a public
 * `(android.content.Context)` constructor; see [ProviderBundles].
 */
interface ProviderBundle {
    val kind: ProviderKind

    /** True if the SDK is packaged and usable on this device (e.g. `GoogleApiAvailability` SUCCESS). */
    fun isAvailable(): Boolean

    fun location(): LocationBackend

    fun activity(): ActivityBackend

    fun geofence(): GeofenceBackend
}

/** Selects the active backend from config `locationProvider` (see architecture "GMS/HMS selection"). */
interface ProviderFactory {
    val kind: ProviderKind

    fun location(): LocationBackend

    fun activity(): ActivityBackend

    fun geofence(): GeofenceBackend

    fun isAvailable(kind: ProviderKind): Boolean

    /** Re-evaluates the selection (e.g. after setConfig). Returns true if [kind] changed. */
    fun reselect(): Boolean
}

/** Implemented by the TrackingEngine. */
interface ActivitySink {
    suspend fun onActivitySamples(samples: List<ActivitySample>)
}

/** Implemented by the GeofenceManager. */
interface GeofenceTransitionSink {
    suspend fun onGeofenceTransitions(transitions: List<OsGeofenceTransition>)
}

/**
 * Implemented by the TrackingEngine (round 2, stationary GPS-off mode). While STATIONARY the engine registers one OS
 * geofence with id [com.brickssoft.locationtracking.core.Constants.STATIONARY_REGION_ID] directly with
 * `providers.geofence()`. The GeofenceManager forwards every transition with that id here, and never stores, records
 * or emits it.
 */
interface StationaryRegionSink {
    suspend fun onStationaryRegionTransition(transition: OsGeofenceTransition)

    companion object {
        /** Ignores every transition (tests and callers without an engine). */
        val NONE: StationaryRegionSink = object : StationaryRegionSink {
            override suspend fun onStationaryRegionTransition(transition: OsGeofenceTransition) = Unit
        }
    }
}

/** Fully qualified class names used for reflective loading. Strings only: never reference SDK types here. */
object ProviderBundles {
    const val GMS_BUNDLE = "com.brickssoft.locationtracking.provider.gms.GmsProviderBundle"
    const val HMS_BUNDLE = "com.brickssoft.locationtracking.provider.hms.HmsProviderBundle"
    const val ANDROID_BUNDLE = "com.brickssoft.locationtracking.provider.android.AndroidProviderBundle"

    /** Present only when play-services-location is packaged. */
    const val GMS_SDK_CLASS = "com.google.android.gms.location.LocationServices"

    /** Present only when com.huawei.hms:location is packaged. */
    const val HMS_SDK_CLASS = "com.huawei.hms.location.LocationServices"

    /** The receivers the GMS backend's activity and geofence results are delivered to. */
    val GMS_RECEIVERS = listOf(
        "com.brickssoft.locationtracking.provider.gms.GmsActivityReceiver",
        "com.brickssoft.locationtracking.provider.gms.GmsGeofenceReceiver",
    )

    /** The receivers the HMS backend's activity and geofence results are delivered to. */
    val HMS_RECEIVERS = listOf(
        "com.brickssoft.locationtracking.provider.hms.HmsActivityReceiver",
        "com.brickssoft.locationtracking.provider.hms.HmsGeofenceReceiver",
    )

    /** The SDK class whose presence means [kind]'s SDK is in the APK; null for ANDROID (part of the platform). */
    fun sdkClassName(kind: ProviderKind): String? = when (kind) {
        ProviderKind.GMS -> GMS_SDK_CLASS
        ProviderKind.HMS -> HMS_SDK_CLASS
        ProviderKind.ANDROID -> null
    }

    /** The receivers [kind]'s backend needs in the merged manifest; none for ANDROID. */
    fun receiverClassNames(kind: ProviderKind): List<String> = when (kind) {
        ProviderKind.GMS -> GMS_RECEIVERS
        ProviderKind.HMS -> HMS_RECEIVERS
        ProviderKind.ANDROID -> emptyList()
    }

    fun bundleClassName(kind: ProviderKind): String = when (kind) {
        ProviderKind.GMS -> GMS_BUNDLE
        ProviderKind.HMS -> HMS_BUNDLE
        ProviderKind.ANDROID -> ANDROID_BUNDLE
    }
}
