package com.brickssoft.locationtracking.position

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * Default [PositionService].
 *
 * - [getCurrentPosition] requires foreground location permission and enabled location services. With
 *   `maximumAgeMs > 0` it returns the freshest known fix (runtime or backend last location) if it is young
 *   enough. Otherwise it samples a temporary backend request for up to `samples` fixes, stops early on a fix of
 *   [ACCURATE_ENOUGH_M] or better, and keeps the most accurate fix. The timeout bounds the whole call; at the
 *   timeout the best fix so far is returned, or TIMEOUT is thrown if there is none.
 * - [watchPosition] registers one backend listener per watch id. Each fix becomes a `watch_position` record,
 *   optionally persisted through [recordSink], and is delivered in order from [scope] (see [PositionWatch]).
 *
 * Mock fixes are ignored everywhere while `geolocation.filter.rejectMockLocations` is set.
 */
class DefaultPositionService(
    private val providers: ProviderFactory,
    private val configStore: ConfigStore,
    private val permissions: PermissionManager,
    private val device: DeviceMonitor,
    private val recordFactory: RecordFactory,
    private val recordSink: RecordSink,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : PositionService {
    private val watches = ConcurrentHashMap<String, PositionWatch>()

    override suspend fun getCurrentPosition(o: CurrentPositionOptions): Record {
        if (!permissions.hasForegroundLocation()) {
            throw TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission is not granted")
        }
        if (!device.providerState().enabled) {
            throw TrackingException(ErrorCode.LOCATION_DISABLED, "Location services are disabled")
        }
        val geolocation = configStore.config.value.geolocation
        val rejectMock = geolocation.filter.rejectMockLocations
        val timeoutMs = o.timeoutMs ?: geolocation.locationTimeout
        val backend = providers.location()
        val startedAt = clock.elapsedRealtime()

        // The timeout covers the whole call, including the last-location lookup of the maximumAge check.
        val location = cachedLocation(backend, o.maximumAgeMs, rejectMock, timeoutMs)
            ?: sample(backend, o, timeoutMs - (clock.elapsedRealtime() - startedAt), rejectMock)
            ?: throw TrackingException(ErrorCode.TIMEOUT, "No location fix within ${timeoutMs}ms")
        val record = recordFactory.create(RecordEvent.CURRENT_POSITION, location, extras = o.extras)
        return if (o.persist) recordSink.submit(record) else record
    }

    override fun watchPosition(id: String, o: WatchPositionOptions, callback: (Record?, TrackingException?) -> Unit) {
        watches.remove(id)?.stop()
        if (!permissions.hasForegroundLocation()) {
            deliverError(id, callback, TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission is not granted"))
            return
        }
        if (!device.providerState().enabled) {
            // Not an error: the backend starts delivering once location services are switched on.
            Logger.w(TAG, "watch $id: location services are disabled")
        }
        val interval = o.intervalMs.coerceAtLeast(0L)
        val spec = LocationRequestSpec(o.desiredAccuracy, interval, interval / 2, 0f)
        val watch = PositionWatch(id, providers.location(), spec, callback) { fix -> watchRecord(fix, o) }
        // A concurrent watchPosition() for the same id may have registered in the meantime: last one wins.
        watches.put(id, watch)?.stop()
        val error = watch.start(scope) ?: return
        watches.remove(id, watch)
        deliverError(id, callback, error)
    }

    override fun clearWatch(id: String): Boolean {
        val watch = watches.remove(id) ?: return false
        watch.stop()
        return true
    }

    override fun clearAllWatches() {
        for ((id, watch) in watches) {
            if (watches.remove(id, watch)) watch.stop()
        }
    }

    /**
     * Freshest acceptable known fix no older than [maximumAgeMs], or null (always null when [maximumAgeMs] <= 0).
     * The backend lookup takes at most [LAST_LOCATION_TIMEOUT_MS] and never longer than [timeoutMs].
     */
    private suspend fun cachedLocation(
        backend: LocationBackend,
        maximumAgeMs: Long,
        rejectMock: Boolean,
        timeoutMs: Long,
    ): TrackedLocation? {
        if (maximumAgeMs <= 0) return null
        val known = listOfNotNull(
            configStore.runtime.value.lastLocation,
            lastBackendLocation(backend, min(LAST_LOCATION_TIMEOUT_MS, timeoutMs)),
        )
        val freshest = known.filter { accepts(it, rejectMock) }.minByOrNull { ageOf(it) } ?: return null
        val age = ageOf(freshest)
        if (age > maximumAgeMs) return null
        Logger.d(TAG, "getCurrentPosition: using known fix (age ${age}ms, accuracy ${freshest.accuracy}m)")
        return freshest
    }

    private suspend fun lastBackendLocation(backend: LocationBackend, timeoutMs: Long): TrackedLocation? = try {
        withTimeoutOrNull(timeoutMs) { backend.getLastLocation() }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // the caller was cancelled: propagate
        Logger.w(TAG, "getLastLocation was cancelled by the backend", e)
        null
    } catch (e: Exception) {
        Logger.w(TAG, "getLastLocation failed", e)
        null
    }

    /**
     * Age of [fix] in ms: the larger of its wall-clock age and, when the fix carries an elapsed-realtime timestamp,
     * its elapsed-realtime age. A wall-clock change therefore cannot make an old fix look fresh, and a timestamp from
     * before a reboot only ever yields a smaller elapsed age, which the wall-clock age then dominates.
     */
    private fun ageOf(fix: TrackedLocation): Long {
        val wallAge = clock.now() - fix.time
        if (fix.elapsedRealtimeNanos <= 0L) return wallAge
        return max(wallAge, clock.elapsedRealtime() - fix.elapsedRealtimeNanos / NANOS_PER_MS)
    }

    /**
     * Collects fixes from a temporary backend request until [SampleCollector] is satisfied or [timeoutMs] expires,
     * and returns the best one, or null if no acceptable fix arrived. The listener is always removed.
     * Historical fixes computed before the request (by the elapsed-realtime clock) are ignored.
     */
    private suspend fun sample(
        backend: LocationBackend,
        o: CurrentPositionOptions,
        timeoutMs: Long,
        rejectMock: Boolean,
    ): TrackedLocation? {
        if (timeoutMs <= 0) return null
        val collector = SampleCollector(o.samples)
        val fixes = Channel<TrackedLocation>(Channel.UNLIMITED)
        val listener = LocationListener { batch -> batch.forEach { fixes.trySend(it) } }
        val spec = LocationRequestSpec(o.desiredAccuracy, SAMPLE_INTERVAL_MS, SAMPLE_FASTEST_INTERVAL_MS, 0f)
        val requestedAt = clock.elapsedRealtime()
        try {
            backend.requestUpdates(spec, listener)
            withTimeoutOrNull(timeoutMs) {
                for (fix in fixes) {
                    if (accepts(fix, rejectMock) && !isStaleSample(fix, requestedAt) && collector.add(fix)) break
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller was cancelled: propagate
            throw backendError(e)
        } catch (e: Exception) {
            throw backendError(e)
        } finally {
            fixes.close()
            removeQuietly(backend, listener)
        }
        val best = collector.best ?: return null
        Logger.d(TAG, "getCurrentPosition: ${collector.count} sample(s), best accuracy ${best.accuracy}m")
        return best
    }

    private fun isStaleSample(fix: TrackedLocation, requestedAt: Long): Boolean =
        fix.elapsedRealtimeNanos > 0L && fix.elapsedRealtimeNanos / NANOS_PER_MS < requestedAt - STALE_SAMPLE_TOLERANCE_MS

    /** Record for a watch fix, persisted if requested; null if the fix is rejected by the mock rule. */
    private suspend fun watchRecord(fix: TrackedLocation, o: WatchPositionOptions): Record? {
        if (!accepts(fix, configStore.config.value.geolocation.filter.rejectMockLocations)) return null
        val record = recordFactory.create(RecordEvent.WATCH_POSITION, fix, extras = o.extras)
        return if (o.persist) recordSink.submit(record) else record
    }

    private fun deliverError(id: String, callback: (Record?, TrackingException?) -> Unit, error: TrackingException) {
        Logger.w(TAG, "watch $id failed: ${error.code} ${error.message}")
        try {
            callback(null, error)
        } catch (e: Exception) {
            Logger.e(TAG, "watch $id callback failed", e)
        }
    }

    internal companion object {
        const val TAG = "LT.Position"

        /** Interval of the temporary sampling request of getCurrentPosition, ms. */
        const val SAMPLE_INTERVAL_MS = 1_000L

        /** Fastest interval of the temporary sampling request of getCurrentPosition, ms. */
        const val SAMPLE_FASTEST_INTERVAL_MS = 500L

        /** A fix at least this accurate (m) ends sampling early. */
        const val ACCURATE_ENOUGH_M = 10f

        /** Upper bound for the backend's last-location lookup of the maximumAge check, ms. */
        const val LAST_LOCATION_TIMEOUT_MS = 5_000L

        /** A sample computed more than this long before the sampling request started is historical, ms. */
        const val STALE_SAMPLE_TOLERANCE_MS = 2_000L

        private const val NANOS_PER_MS = 1_000_000L

        /** False for mock fixes while [rejectMock] is set. */
        fun accepts(fix: TrackedLocation, rejectMock: Boolean): Boolean = !(rejectMock && fix.isMock)

        /** Maps a backend failure to the error reported to JS. */
        fun backendError(e: Exception): TrackingException = when (e) {
            is TrackingException -> e
            is SecurityException -> TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission is not granted", e)
            else -> TrackingException(ErrorCode.UNAVAILABLE, "Location updates are unavailable: ${e.message}", e)
        }

        fun removeQuietly(backend: LocationBackend, listener: LocationListener) {
            try {
                backend.removeUpdates(listener)
            } catch (e: Exception) {
                Logger.w(TAG, "removeUpdates failed", e)
            }
        }
    }
}

/**
 * Best-of-N selection for getCurrentPosition. [add] returns true once [wanted] fixes were collected or the best
 * fix is at least [DefaultPositionService.ACCURATE_ENOUGH_M] accurate. A fix without a usable accuracy (0, negative
 * or NaN) ranks last; on equal accuracy the fix with the newer (or equal) fix time wins. Not thread-safe.
 */
internal class SampleCollector(wanted: Int) {
    private val wanted = wanted.coerceAtLeast(1)

    var best: TrackedLocation? = null
        private set

    var count = 0
        private set

    fun add(fix: TrackedLocation): Boolean {
        count++
        val current = best
        val winner = if (current == null || isBetter(fix, current)) fix else current
        best = winner
        return count >= wanted || rank(winner) <= DefaultPositionService.ACCURATE_ENOUGH_M
    }

    private fun isBetter(fix: TrackedLocation, current: TrackedLocation): Boolean {
        val a = rank(fix)
        val b = rank(current)
        return a < b || (a == b && fix.time >= current.time)
    }

    private fun rank(fix: TrackedLocation): Float = if (fix.accuracy > 0f) fix.accuracy else Float.POSITIVE_INFINITY
}
