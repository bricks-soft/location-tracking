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
import com.brickssoft.locationtracking.provider.LocationSubscriptions
import com.brickssoft.locationtracking.provider.OsRegistration
import com.brickssoft.locationtracking.provider.RegistrationEvents
import com.huawei.hmf.tasks.TaskExecutors
import com.huawei.hms.location.FusedLocationProviderClient
import com.huawei.hms.location.LocationAvailability
import com.huawei.hms.location.LocationCallback
import com.huawei.hms.location.LocationRequest
import com.huawei.hms.location.LocationResult
import com.huawei.hms.location.LocationServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LocationBackend] over the HMS fused location client.
 *
 * - Listener bookkeeping is [LocationSubscriptions]: each request gets its own [LocationCallback] on the main looper; a
 *   request for the same listener removes the previous callback first, and late fixes of a replaced callback are
 *   dropped. A failure of `requestLocationUpdates` (thrown, or reported later by its task) reaches the listener's
 *   `onError` (recommendation 3). The first delivered fix counts as a successful registration: Huawei does not
 *   guarantee that the task succeeds before the first result.
 * - `getCurrentLocation` is a one-shot request (`numUpdates = 1`) whose callback is always removed. It returns the
 *   first fix even while the request task has not completed, and a failed request ends it at once with the error
 *   instead of waiting for the timeout.
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

    private val subscriptions = LocationSubscriptions(TAG) { it.toTrackingException("requestLocationUpdates") }

    override val availabilityHint: Boolean? get() = subscriptions.availabilityHint

    override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
        val request = buildLocationRequest(spec)
        subscriptions.request(listener, spec) { events -> Registration(request, events) }
    }

    override fun removeUpdates(listener: LocationListener) {
        subscriptions.remove(listener)
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
                val task = hmsCall("getCurrentLocation") {
                    requested = true
                    client.requestLocationUpdates(buildOneShotRequest(accuracy), callback, looperProvider())
                }
                task.addOnFailureListener(TaskExecutors.immediate()) { e ->
                    fix.completeExceptionally(e.toTrackingException("getCurrentLocation"))
                }
                task.addOnCanceledListener(TaskExecutors.immediate()) {
                    fix.completeExceptionally(HmsTaskCanceledException().toTrackingException("getCurrentLocation"))
                }
                fix.await()
            }
        } finally {
            if (requested) removeQuietly(callback)
        }
    }

    /** Number of listeners with an active request (for tests and diagnostics). */
    internal val activeListenerCount: Int get() = subscriptions.count

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

    /** One `requestLocationUpdates` with its own callback. */
    private inner class Registration(
        private val request: LocationRequest,
        private val events: RegistrationEvents,
    ) : OsRegistration {
        private val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult?) {
                events.locations(result?.locations.orEmpty().mapNotNull { location -> location?.let(::toTrackedLocation) })
            }

            override fun onLocationAvailability(availability: LocationAvailability?) {
                availability?.let { events.availability(it.isLocationAvailable) }
            }
        }

        override fun start(done: (Exception?) -> Unit) {
            client.requestLocationUpdates(request, callback, looperProvider())
                .addOnSuccessListener(TaskExecutors.immediate()) { done(null) }
                .addOnFailureListener(TaskExecutors.immediate()) { e -> done(e) }
                .addOnCanceledListener(TaskExecutors.immediate()) { done(HmsTaskCanceledException()) }
        }

        override fun remove() = removeQuietly(callback)
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
}

/** One-shot request for `getCurrentLocation`. */
internal fun buildOneShotRequest(accuracy: DesiredAccuracy): LocationRequest =
    LocationRequest.create()
        .setPriority(hmsPriority(accuracy))
        .setInterval(HmsLocationBackend.ONE_SHOT_INTERVAL_MS)
        .setFastestInterval(HmsLocationBackend.ONE_SHOT_INTERVAL_MS / 2)
        .setNumUpdates(1)

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
