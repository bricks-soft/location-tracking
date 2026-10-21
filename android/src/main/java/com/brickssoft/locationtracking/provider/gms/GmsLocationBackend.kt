package com.brickssoft.locationtracking.provider.gms

import android.os.Looper
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.LocationSubscriptions
import com.brickssoft.locationtracking.provider.OsRegistration
import com.brickssoft.locationtracking.provider.RegistrationEvents
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fused location provider backend.
 *
 * Listener bookkeeping is [LocationSubscriptions]: every request gets its own [LocationCallback] on the main looper,
 * a request for the same listener removes the previous callback first, and a failure of `requestLocationUpdates`
 * (thrown, or reported later by its Task) reaches the listener's `onError` (recommendation 3).
 * `onLocationAvailability` is kept as [availabilityHint].
 *
 * Only [getCurrentLocation] throws: the [LocationBackend] contract declares no exception for the other methods.
 */
internal class GmsLocationBackend(
    private val client: FusedLocationProviderClient,
    private val looper: () -> Looper = Looper::getMainLooper,
) : LocationBackend {
    override val kind: ProviderKind = ProviderKind.GMS

    private val subscriptions = LocationSubscriptions(TAG) { GmsErrors.map(it, "requestLocationUpdates") }

    override val availabilityHint: Boolean? get() = subscriptions.availabilityHint

    override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
        val request = try {
            buildLocationRequest(spec)
        } catch (e: Exception) {
            Logger.e(TAG, "invalid location request $spec", e)
            listener.onError(GmsErrors.map(e, "requestLocationUpdates"))
            return
        }
        subscriptions.request(listener, spec) { events -> Registration(request, events) }
        Logger.d(TAG, "location updates requested: $spec")
    }

    override fun removeUpdates(listener: LocationListener) {
        subscriptions.remove(listener)
    }

    /** One `requestLocationUpdates` with its own callback. */
    private inner class Registration(
        private val request: LocationRequest,
        private val events: RegistrationEvents,
    ) : OsRegistration {
        private val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                events.locations(result.locations.map(TrackedLocation::from))
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                events.availability(availability.isLocationAvailable)
            }
        }

        override fun start(done: (Exception?) -> Unit) {
            client.requestLocationUpdates(request, callback, looper()).addOnCompleteListener(DirectExecutor) { task ->
                done(task.exception ?: if (task.isCanceled) IllegalStateException("request cancelled by GMS") else null)
            }
        }

        override fun remove() {
            try {
                client.removeLocationUpdates(callback).logFailure(TAG, "removeLocationUpdates")
            } catch (e: Exception) {
                Logger.w(TAG, "removeLocationUpdates failed", e)
            }
        }
    }

    /**
     * The cached fused location, or null if there is none or GMS cannot provide it (including a missing permission;
     * logged). Never throws, so callers such as the heartbeat can always fall back to a record without coords.
     */
    override suspend fun getLastLocation(): TrackedLocation? = try {
        client.lastLocation.await()?.let(TrackedLocation::from)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        Logger.w(TAG, "getLastLocation cancelled by GMS")
        null
    } catch (e: Exception) {
        val mapped = GmsErrors.map(e, "getLastLocation")
        Logger.w(TAG, "${mapped.code}: ${mapped.message}", e)
        null
    }

    /**
     * Requests one fix computed now (a cached fix is accepted only for PASSIVE, which never computes one itself).
     * Returns null if none arrives within [timeoutMs]: GMS gives up after the request duration, and the coroutine
     * timeout is a safety net that also cancels the GMS request.
     *
     * @throws TrackingException PERMISSION_DENIED without location permission, otherwise see [GmsErrors.map]
     *   (UNAVAILABLE for GMS API failures).
     */
    override suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation? {
        if (timeoutMs <= 0) return null
        val cancellation = CancellationTokenSource()
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(accuracy.toGmsPriority())
                .setDurationMillis(timeoutMs)
                .apply { if (accuracy != DesiredAccuracy.PASSIVE) setMaxUpdateAgeMillis(0) }
                .build()
            withTimeoutOrNull(timeoutMs) {
                client.getCurrentLocation(request, cancellation.token).await(cancellation)
            }?.let(TrackedLocation::from)
        } catch (e: CancellationException) {
            // Either the caller was cancelled (rethrown here) or GMS cancelled the task itself (no fix).
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            throw GmsErrors.map(e, "getCurrentLocation")
        } finally {
            cancellation.cancel()
        }
    }

    companion object {
        private const val TAG = "LT.GmsLocation"

        /**
         * `LocationRequest.Builder(priority, interval)` with the fastest interval (clamped to [0, interval]) and the
         * distance filter (at least 0).
         */
        fun buildLocationRequest(spec: LocationRequestSpec): LocationRequest {
            val interval = spec.intervalMs.coerceAtLeast(0)
            val distance = spec.distanceFilterM.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
            return LocationRequest.Builder(spec.accuracy.toGmsPriority(), interval)
                .setMinUpdateIntervalMillis(spec.fastestIntervalMs.coerceIn(0, interval))
                .setMinUpdateDistanceMeters(distance)
                .build()
        }

        fun DesiredAccuracy.toGmsPriority(): Int = when (this) {
            DesiredAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
            DesiredAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            DesiredAccuracy.LOW -> Priority.PRIORITY_LOW_POWER
            DesiredAccuracy.PASSIVE -> Priority.PRIORITY_PASSIVE
        }
    }
}
