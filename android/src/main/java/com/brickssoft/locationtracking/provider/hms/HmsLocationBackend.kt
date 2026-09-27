package com.brickssoft.locationtracking.provider.hms

import android.content.Context
import android.location.Location
import android.os.Looper
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.huawei.hmf.tasks.TaskExecutors
import com.huawei.hms.location.FusedLocationProviderClient
import com.huawei.hms.location.LocationCallback
import com.huawei.hms.location.LocationRequest
import com.huawei.hms.location.LocationResult
import com.huawei.hms.location.LocationServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LocationBackend] over the HMS fused location client.
 *
 * - Each listener gets its own [LocationCallback] on the main looper; requesting again with the same listener
 *   replaces its request, and late fixes from a replaced callback are dropped.
 * - `getCurrentLocation` is a one-shot request (`numUpdates = 1`) whose callback is always removed.
 * - Permission failures surface as `TrackingException(PERMISSION_DENIED)`; other HMS failures are mapped by
 *   [toTrackingException].
 *
 * The client is created lazily, on first use.
 */
internal class HmsLocationBackend(
    clientProvider: () -> FusedLocationProviderClient,
    private val looperProvider: () -> Looper = { Looper.getMainLooper() },
) : LocationBackend {
    constructor(context: Context) : this({ LocationServices.getFusedLocationProviderClient(context) })

    override val kind: ProviderKind = ProviderKind.HMS

    private val client: FusedLocationProviderClient by lazy(clientProvider)

    /**
     * Active callback per listener. Guarded by [lock], which is also held across the HMS request/remove calls so a
     * concurrent request/remove for the same listener cannot leave an orphaned HMS registration. Those calls only
     * submit work and return a task; callbacks never take the lock (they check [ListenerCallback.active]).
     */
    private val callbacks = HashMap<LocationListener, ListenerCallback>()
    private val lock = Any()

    override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
        val request = buildLocationRequest(spec)
        synchronized(lock) {
            callbacks.remove(listener)?.let(::deactivateAndRemove)
            val callback = ListenerCallback(listener)
            callbacks[listener] = callback
            try {
                client.requestLocationUpdates(request, callback, looperProvider())
                    .addOnFailureListener(TaskExecutors.immediate()) { e ->
                        Logger.e(TAG, "requestLocationUpdates failed: ${describeHmsError(e)}", e)
                        forget(callback)
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "requestLocationUpdates failed: ${describeHmsError(e)}", e)
                forget(callback)
            }
        }
    }

    override fun removeUpdates(listener: LocationListener) {
        synchronized(lock) { callbacks.remove(listener)?.let(::deactivateAndRemove) }
    }

    override suspend fun getLastLocation(): TrackedLocation? = hmsCall("getLastLocation") {
        val location: Location? = client.lastLocation.await()
        location?.let(::toTrackedLocation)
    }

    override suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation? {
        val fix = CompletableDeferred<TrackedLocation>()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult?) {
                val location = result?.locations.orEmpty().lastOrNull { it != null } ?: return
                fix.complete(toTrackedLocation(location))
            }
        }
        var requested = false
        try {
            return withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
                hmsCall("getCurrentLocation") {
                    requested = true
                    client.requestLocationUpdates(buildOneShotRequest(accuracy), callback, looperProvider()).await()
                }
                fix.await()
            }
        } finally {
            if (requested) removeQuietly(callback)
        }
    }

    /** Number of listeners with an active request (for tests and diagnostics). */
    internal val activeListenerCount: Int get() = synchronized(lock) { callbacks.size }

    /** Drops [callback] after its request failed, unless it was already replaced or removed. */
    private fun forget(callback: ListenerCallback) {
        callback.active = false
        synchronized(lock) { if (callbacks[callback.listener] === callback) callbacks.remove(callback.listener) }
    }

    private fun deactivateAndRemove(callback: ListenerCallback) {
        callback.active = false
        removeQuietly(callback)
    }

    private fun removeQuietly(callback: LocationCallback) {
        try {
            client.removeLocationUpdates(callback)
                .addOnFailureListener(TaskExecutors.immediate()) { e ->
                    Logger.d(TAG, "removeLocationUpdates failed: ${describeHmsError(e)}")
                }
        } catch (e: Exception) {
            Logger.w(TAG, "removeLocationUpdates failed: ${describeHmsError(e)}", e)
        }
    }

    /** Delivers to [listener] until replaced or removed; late fixes after that are dropped. */
    private class ListenerCallback(val listener: LocationListener) : LocationCallback() {
        @Volatile
        var active = true

        override fun onLocationResult(result: LocationResult?) {
            if (!active) return
            val fixes = result?.locations.orEmpty().mapNotNull { location -> location?.let(::toTrackedLocation) }
            if (fixes.isEmpty()) return
            try {
                listener.onLocations(fixes)
            } catch (e: Exception) {
                Logger.e(TAG, "location listener failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "LT.HmsLocation"

        /** Interval of the one-shot request behind `getCurrentLocation`. */
        internal const val ONE_SHOT_INTERVAL_MS = 1_000L
    }
}

/** HMS priority for a desired accuracy. */
internal fun hmsPriority(accuracy: DesiredAccuracy): Int = when (accuracy) {
    DesiredAccuracy.HIGH -> LocationRequest.PRIORITY_HIGH_ACCURACY
    DesiredAccuracy.BALANCED -> LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
    DesiredAccuracy.LOW -> LocationRequest.PRIORITY_LOW_POWER
    DesiredAccuracy.PASSIVE -> LocationRequest.PRIORITY_NO_POWER
}

/** Continuous request: negative values are clamped to 0 and the fastest interval never exceeds the interval. */
internal fun buildLocationRequest(spec: LocationRequestSpec): LocationRequest {
    val interval = spec.intervalMs.coerceAtLeast(0L)
    return LocationRequest.create()
        .setPriority(hmsPriority(spec.accuracy))
        .setInterval(interval)
        .setFastestInterval(spec.fastestIntervalMs.coerceIn(0L, interval))
        .setSmallestDisplacement(spec.distanceFilterM.coerceAtLeast(0f))
        .setCoordinateType(LocationRequest.COORDINATE_TYPE_WGS84)
}

/** One-shot request for `getCurrentLocation`. */
internal fun buildOneShotRequest(accuracy: DesiredAccuracy): LocationRequest =
    LocationRequest.create()
        .setPriority(hmsPriority(accuracy))
        .setInterval(HmsLocationBackend.ONE_SHOT_INTERVAL_MS)
        .setFastestInterval(HmsLocationBackend.ONE_SHOT_INTERVAL_MS / 2)
        .setNumUpdates(1)
        .setCoordinateType(LocationRequest.COORDINATE_TYPE_WGS84)

/**
 * [TrackedLocation.from] plus the extras HMS uses on some devices and API levels: `mockLocation` marks a mock fix
 * and `verticalAccuracy` fills a missing altitude accuracy.
 */
@Suppress("DEPRECATION") // Bundle.get: the value types of these extras vary between HMS versions
internal fun toTrackedLocation(location: Location): TrackedLocation {
    val base = TrackedLocation.from(location)
    val extras = try {
        location.extras?.takeUnless { it.isEmpty }
    } catch (_: RuntimeException) {
        null // a bundle that cannot be unparcelled carries nothing we need
    } ?: return base
    val mock = extras.get(FusedLocationProviderClient.KEY_MOCK_LOCATION) as? Boolean ?: false
    val verticalAccuracy = (extras.get(FusedLocationProviderClient.KEY_VERTICAL_ACCURACY) as? Number)
        ?.toFloat()
        ?.takeIf { it > 0f && it.isFinite() }
    return base.copy(
        isMock = base.isMock || mock,
        altitudeAccuracy = base.altitudeAccuracy ?: verticalAccuracy,
    )
}
