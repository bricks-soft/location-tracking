package com.brickssoft.locationtracking.provider.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LocationBackend] over the platform [LocationManager] (through `LocationManagerCompat`), used when neither GMS nor
 * HMS is available.
 *
 * - Provider per accuracy: HIGH uses gps, BALANCED/LOW use network, each falling back to whichever is enabled;
 *   PASSIVE uses the passive provider (see [LocationProviders.candidates]).
 * - Updates arrive on the main looper. While any listener is registered, `PROVIDERS_CHANGED_ACTION` moves each
 *   registration to its best enabled provider.
 * - A [SecurityException] from the platform surfaces as a [TrackingException] with [ErrorCode.PERMISSION_DENIED].
 */
class AndroidLocationBackend(
    context: Context,
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager,
) : LocationBackend {
    private val appContext: Context = context.applicationContext ?: context
    override val kind: ProviderKind = ProviderKind.ANDROID

    /** Serializes (re-)registrations; [registrations] is also read lock-free by the delivery path. */
    private val lock = Any()
    private val registrations = ConcurrentHashMap<LocationListener, Registration>()
    private var providersReceiver: BroadcastReceiver? = null

    override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
        val lm = locationManager ?: return Logger.e(TAG, "no LocationManager: cannot request location updates")
        synchronized(lock) {
            val providers = LocationProviders.candidates(lm, spec.accuracy)
            val registration = register(lm, spec, listener, providers)
                ?: return Logger.e(TAG, "no usable location provider for ${spec.accuracy.wire} (tried $providers)")
            registrations.put(listener, registration)?.let { unregister(lm, it) }
            observeProviderChanges()
            Logger.d(TAG, "requested ${registration.provider} updates: $spec")
        }
    }

    override fun removeUpdates(listener: LocationListener) {
        val lm = locationManager ?: return
        synchronized(lock) {
            val registration = registrations.remove(listener) ?: return
            unregister(lm, registration)
            if (registrations.isEmpty()) stopObservingProviderChanges()
        }
    }

    override suspend fun getLastLocation(): TrackedLocation? {
        val lm = locationManager ?: return null
        return LocationProviders.freshestLastKnown(lm)?.let(TrackedLocation::from)
    }

    /**
     * Requests one fix from the best enabled provider for [accuracy] (PASSIVE is treated as LOW). The platform gives
     * up after about 30 s, so the request is repeated until [timeoutMs] elapses. Returns null on timeout or when no
     * provider is enabled.
     */
    override suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation? {
        val lm = locationManager ?: return null
        val effective = if (accuracy == DesiredAccuracy.PASSIVE) DesiredAccuracy.LOW else accuracy
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { awaitCurrentLocation(lm, effective) }
            ?.let(TrackedLocation::from)
    }

    private suspend fun awaitCurrentLocation(lm: LocationManager, accuracy: DesiredAccuracy): Location? {
        while (true) {
            val enabled = LocationProviders.candidates(lm, accuracy, enabledOnly = true)
            if (enabled.isEmpty()) return null
            currentLocationFromFirst(lm, enabled)?.let { return it }
            delay(CURRENT_LOCATION_RETRY_DELAY_MS)
        }
    }

    /** Asks the first provider of [providers] that does not refuse with a [SecurityException]. */
    private suspend fun currentLocationFromFirst(lm: LocationManager, providers: List<String>): Location? {
        var denied: SecurityException? = null
        for (provider in providers) {
            try {
                return currentLocation(lm, provider)
            } catch (e: SecurityException) {
                denied = e
            } catch (e: IllegalArgumentException) {
                Logger.w(TAG, "provider $provider rejected getCurrentLocation", e)
            }
        }
        if (denied != null) throw TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission denied", denied)
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            val resumed = AtomicBoolean(false)
            cont.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, DIRECT_EXECUTOR) { location ->
                    if (resumed.compareAndSet(false, true)) cont.resume(location)
                }
            } catch (e: Exception) {
                if (resumed.compareAndSet(false, true)) cont.resumeWithException(e)
            }
        }

    /**
     * Registers [listener] on the first of [providers] that accepts the request.
     *
     * @return null if no provider accepted it for a reason other than permissions.
     * @throws TrackingException [ErrorCode.PERMISSION_DENIED] if a provider refused with a [SecurityException] and
     *   no other accepted.
     */
    @SuppressLint("MissingPermission")
    private fun register(
        lm: LocationManager,
        spec: LocationRequestSpec,
        listener: LocationListener,
        providers: List<String>,
    ): Registration? {
        var denied: SecurityException? = null
        for (provider in providers) {
            val registration = Registration(listener, spec, provider)
            try {
                LocationManagerCompat.requestLocationUpdates(
                    lm, provider, requestFor(spec), registration, Looper.getMainLooper(),
                )
                return registration
            } catch (e: SecurityException) {
                Logger.w(TAG, "provider $provider refused location updates", e)
                denied = e
            } catch (e: IllegalArgumentException) {
                Logger.w(TAG, "provider $provider rejected location updates", e)
            }
        }
        if (denied != null) throw TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission denied", denied)
        return null
    }

    private fun unregister(lm: LocationManager, registration: Registration) {
        try {
            LocationManagerCompat.removeUpdates(lm, registration)
        } catch (e: Exception) {
            Logger.w(TAG, "removeUpdates failed for ${registration.provider}", e)
        }
    }

    /** Moves every registration whose best enabled provider changed to that provider. Runs on the main thread. */
    private fun onProvidersChanged() {
        val lm = locationManager ?: return
        synchronized(lock) {
            for ((listener, current) in registrations.entries.toList()) {
                val best = LocationProviders.candidates(lm, current.spec.accuracy, enabledOnly = true).firstOrNull()
                if (best == null || best == current.provider) continue
                val next = try {
                    register(lm, current.spec, listener, listOf(best))
                } catch (e: TrackingException) {
                    null
                } ?: continue
                registrations[listener] = next
                unregister(lm, current)
                Logger.i(TAG, "location provider changed: ${current.provider} -> $best")
            }
        }
    }

    private fun observeProviderChanges() {
        if (providersReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onProvidersChanged()
        }
        try {
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            providersReceiver = receiver
        } catch (e: Exception) {
            Logger.w(TAG, "cannot observe location provider changes", e)
        }
    }

    private fun stopObservingProviderChanges() {
        val receiver = providersReceiver ?: return
        providersReceiver = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Logger.w(TAG, "unregisterReceiver failed", e)
        }
    }

    /** One platform registration of [listener] on [provider]; stale ones (replaced or removed) drop their fixes. */
    private inner class Registration(
        val listener: LocationListener,
        val spec: LocationRequestSpec,
        val provider: String,
    ) : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = deliver(listOf(location))

        override fun onLocationChanged(locations: MutableList<Location>) = deliver(locations)

        private fun deliver(locations: List<Location>) {
            if (locations.isEmpty() || registrations[listener] !== this) return
            try {
                listener.onLocations(locations.map(TrackedLocation::from))
            } catch (e: Exception) {
                Logger.e(TAG, "location listener failed", e)
            }
        }
    }

    internal companion object {
        private const val TAG = "LT.AndroidLocation"

        /** Pause between two platform `getCurrentLocation` attempts that returned no fix. */
        const val CURRENT_LOCATION_RETRY_DELAY_MS = 1_000L

        private val DIRECT_EXECUTOR = Executor { it.run() }

        /** Maps [spec] to a platform request; values are clamped to what `LocationRequestCompat` accepts. */
        fun requestFor(spec: LocationRequestSpec): LocationRequestCompat {
            val interval = spec.intervalMs.coerceAtLeast(0)
            val distance = spec.distanceFilterM.takeIf { it.isFinite() && it > 0f } ?: 0f
            val quality = when (spec.accuracy) {
                DesiredAccuracy.HIGH -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
                DesiredAccuracy.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                DesiredAccuracy.LOW, DesiredAccuracy.PASSIVE -> LocationRequestCompat.QUALITY_LOW_POWER
            }
            return LocationRequestCompat.Builder(interval)
                .setQuality(quality)
                .setMinUpdateIntervalMillis(spec.fastestIntervalMs.coerceIn(0, interval))
                .setMinUpdateDistanceMeters(distance)
                .build()
        }
    }
}
