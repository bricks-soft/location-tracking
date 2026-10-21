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
import android.os.SystemClock
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
import com.brickssoft.locationtracking.provider.LocationSubscriptions
import com.brickssoft.locationtracking.provider.OsRegistration
import com.brickssoft.locationtracking.provider.RegistrationEvents
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LocationBackend] over the platform [LocationManager] (through `LocationManagerCompat`), used when neither GMS nor
 * HMS is available.
 *
 * - Providers per request ([LocationProviders.forRequest]): HIGH and BALANCED use `gps` and `network` together, as two
 *   platform listeners (recommendation 12), so a phone indoors without satellites still gets network fixes. While a GPS
 *   fix arrived in the last [gpsPriorityMs], network fixes are dropped, so GPS and network points do not alternate
 *   (a network fix can be 1–2 km off). LOW uses one provider (network, else gps); PASSIVE the passive provider. When
 *   neither gps nor network is enabled, `fused` (Android 12+) is used; when nothing is enabled, the existing providers
 *   are registered anyway and start delivering once enabled.
 * - Listener bookkeeping is [LocationSubscriptions]; a request that no provider accepts reaches the listener's
 *   `onError` (PERMISSION_DENIED when every provider refused with a [SecurityException], else UNAVAILABLE).
 * - Updates arrive on the main looper. While any listener is registered, `PROVIDERS_CHANGED_ACTION` registers each
 *   request again when its provider set changed.
 * - [getCurrentLocation] asks the enabled providers in parallel and returns the most accurate fix that arrived within
 *   [CURRENT_LOCATION_GRACE_MS] of the first one.
 */
class AndroidLocationBackend(
    context: Context,
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager,
) : LocationBackend {
    private val appContext: Context = context.applicationContext ?: context
    override val kind: ProviderKind = ProviderKind.ANDROID

    private val subscriptions = LocationSubscriptions(TAG, ::mapError)

    /** The providers each listener's current registration uses. */
    private val registeredProviders = ConcurrentHashMap<LocationListener, List<String>>()

    /** Guards [providersReceiver]. */
    private val lock = Any()
    private var providersReceiver: BroadcastReceiver? = null

    override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
        val lm = locationManager ?: run {
            Logger.e(TAG, "no LocationManager: cannot request location updates")
            listener.onError(TrackingException(ErrorCode.UNAVAILABLE, "This device has no LocationManager"))
            return
        }
        val providers = LocationProviders.forRequest(lm, spec.accuracy)
        registeredProviders[listener] = providers
        subscriptions.request(listener, spec) { events -> PlatformRegistration(lm, spec, providers, events) }
        observeProviderChanges()
        Logger.d(TAG, "requested $providers updates: $spec")
    }

    override fun removeUpdates(listener: LocationListener) {
        subscriptions.remove(listener)
        registeredProviders.remove(listener)
        if (subscriptions.count == 0) stopObservingProviderChanges()
    }

    override suspend fun getLastLocation(): TrackedLocation? {
        val lm = locationManager ?: return null
        return LocationProviders.freshestLastKnown(lm)?.let(TrackedLocation::from)
    }

    /**
     * Requests one fix from every enabled provider for [accuracy] in parallel (PASSIVE is treated as LOW). The platform
     * gives up after about 30 s, so each request is repeated until [timeoutMs] elapses. After the first fix, the
     * others get [CURRENT_LOCATION_GRACE_MS] to deliver a more accurate one. Returns null on timeout or when no provider
     * is enabled.
     *
     * @throws TrackingException PERMISSION_DENIED if every provider refused with a [SecurityException].
     */
    override suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation? {
        val lm = locationManager ?: return null
        val effective = if (accuracy == DesiredAccuracy.PASSIVE) DesiredAccuracy.LOW else accuracy
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { awaitCurrentLocation(lm, effective) }
            ?.let(TrackedLocation::from)
    }

    private suspend fun awaitCurrentLocation(lm: LocationManager, accuracy: DesiredAccuracy): Location? =
        coroutineScope {
            val providers = LocationProviders.candidates(lm, accuracy, enabledOnly = true)
            if (providers.isEmpty()) return@coroutineScope null
            val fixes = Channel<Location>(Channel.UNLIMITED)
            val denied = AtomicInteger(0)
            val requests = providers.map { provider ->
                launch {
                    try {
                        repeatCurrentLocation(lm, provider)?.let { fixes.send(it) }
                    } catch (e: SecurityException) {
                        denied.incrementAndGet()
                    } catch (e: IllegalArgumentException) {
                        Logger.w(TAG, "provider $provider rejected getCurrentLocation", e)
                    }
                }
            }
            launch {
                requests.joinAll()
                fixes.close(
                    if (denied.get() == providers.size) {
                        TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission denied")
                    } else {
                        null
                    },
                )
            }
            try {
                var best = fixes.receive()
                withTimeoutOrNull(CURRENT_LOCATION_GRACE_MS) {
                    for (next in fixes) if (next.accuracy < best.accuracy) best = next
                }
                best
            } catch (e: ClosedReceiveChannelException) {
                null
            } finally {
                requests.forEach { it.cancel() }
                coroutineContext.cancelChildren()
            }
        }

    /** One fix from [provider], asking again while it is enabled and gives up without one. */
    private suspend fun repeatCurrentLocation(lm: LocationManager, provider: String): Location? {
        while (LocationProviders.isEnabled(lm, provider)) {
            currentLocation(lm, provider)?.let { return it }
            delay(CURRENT_LOCATION_RETRY_DELAY_MS)
        }
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

    /** Registers each request again whose provider set changed. Runs on the main thread. */
    private fun onProvidersChanged() {
        val lm = locationManager ?: return
        for ((listener, spec) in subscriptions.requests()) {
            val wanted = LocationProviders.forRequest(lm, spec.accuracy)
            val current = registeredProviders[listener]
            if (wanted == current) continue
            Logger.i(TAG, "location providers changed: $current -> $wanted")
            registeredProviders[listener] = wanted
            subscriptions.request(listener, spec) { events -> PlatformRegistration(lm, spec, wanted, events) }
        }
    }

    private fun observeProviderChanges() {
        synchronized(lock) {
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
    }

    private fun stopObservingProviderChanges() {
        val receiver = synchronized(lock) { providersReceiver.also { providersReceiver = null } } ?: return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Logger.w(TAG, "unregisterReceiver failed", e)
        }
    }

    private fun mapError(e: Exception): TrackingException = when (e) {
        is TrackingException -> e
        is SecurityException -> TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission denied", e)
        else -> TrackingException(ErrorCode.UNAVAILABLE, "Location updates failed: ${e.message}", e)
    }

    /**
     * One platform listener per provider of [providers]. Registration succeeds if at least one provider accepts the
     * request. Network fixes are dropped while a GPS fix is recent (see the class docs).
     */
    private inner class PlatformRegistration(
        private val lm: LocationManager,
        private val spec: LocationRequestSpec,
        private val providers: List<String>,
        private val events: RegistrationEvents,
    ) : OsRegistration {
        private val listeners = ArrayList<ProviderListener>()

        /** `SystemClock.elapsedRealtime()` of the last GPS fix delivered, or null. */
        @Volatile
        private var lastGpsFixAt: Long? = null
        private val gpsPriorityMs = gpsPriorityMs(spec)

        @SuppressLint("MissingPermission")
        override fun start(done: (Exception?) -> Unit) {
            var denied: SecurityException? = null
            for (provider in providers) {
                val listener = ProviderListener(provider)
                try {
                    LocationManagerCompat.requestLocationUpdates(
                        lm, provider, requestFor(spec), listener, Looper.getMainLooper(),
                    )
                    synchronized(listeners) { listeners += listener }
                } catch (e: SecurityException) {
                    Logger.w(TAG, "provider $provider refused location updates", e)
                    denied = e
                } catch (e: IllegalArgumentException) {
                    Logger.w(TAG, "provider $provider rejected location updates", e)
                }
            }
            val accepted = synchronized(listeners) { listeners.isNotEmpty() }
            when {
                accepted -> done(null)
                denied != null -> done(denied)
                else -> done(
                    TrackingException(ErrorCode.UNAVAILABLE, "No usable location provider for ${spec.accuracy.wire} (tried $providers)"),
                )
            }
        }

        override fun remove() {
            val registered = synchronized(listeners) { listeners.toList().also { listeners.clear() } }
            for (listener in registered) {
                try {
                    LocationManagerCompat.removeUpdates(lm, listener)
                } catch (e: Exception) {
                    Logger.w(TAG, "removeUpdates failed for ${listener.provider}", e)
                }
            }
        }

        private fun deliver(provider: String, locations: List<Location>) {
            if (locations.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            if (provider == LocationManager.GPS_PROVIDER) {
                lastGpsFixAt = now
            } else if (provider == LocationManager.NETWORK_PROVIDER && LocationManager.GPS_PROVIDER in providers) {
                val gps = lastGpsFixAt
                if (gps != null && now - gps < gpsPriorityMs) {
                    Logger.v(TAG, "network fix dropped: a GPS fix arrived ${(now - gps) / 1_000} s ago")
                    return
                }
            }
            events.locations(locations.map(TrackedLocation::from))
        }

        private inner class ProviderListener(val provider: String) : LocationListenerCompat {
            override fun onLocationChanged(location: Location) = deliver(provider, listOf(location))

            override fun onLocationChanged(locations: MutableList<Location>) = deliver(provider, locations)
        }
    }

    internal companion object {
        private const val TAG = "LT.AndroidLocation"

        /** Pause between two platform `getCurrentLocation` attempts that returned no fix. */
        const val CURRENT_LOCATION_RETRY_DELAY_MS = 1_000L

        /** After the first current-location fix, how long the other providers may still deliver a better one. */
        const val CURRENT_LOCATION_GRACE_MS = 3_000L

        /** Network fixes are dropped for at least this long after a GPS fix. */
        const val GPS_PRIORITY_MIN_MS = 10_000L

        /** How long after a GPS fix network fixes are dropped: twice the request interval, at least 10 s. */
        fun gpsPriorityMs(spec: LocationRequestSpec): Long = maxOf(GPS_PRIORITY_MIN_MS, 2 * spec.intervalMs)

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
