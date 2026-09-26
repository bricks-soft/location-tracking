package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.OsGeofence
import com.brickssoft.locationtracking.provider.ProviderFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scripted [LocationBackend].
 * - [requests] records every `requestUpdates`; [active] holds the current request per listener.
 * - [emit] delivers fixes to every active listener.
 * - [lastLocation] is returned by `getLastLocation`.
 * - `getCurrentLocation` pops [currentLocationQueue] if non-empty, else returns [currentLocation];
 *   [currentLocationCalls] records (accuracy, timeoutMs).
 */
class FakeLocationBackend(override val kind: ProviderKind = ProviderKind.GMS) : LocationBackend {
    val requests = CopyOnWriteArrayList<Pair<LocationRequestSpec, LocationListener>>()
    val removed = CopyOnWriteArrayList<LocationListener>()
    private val activeRequests = LinkedHashMap<LocationListener, LocationRequestSpec>()

    @Volatile
    var lastLocation: TrackedLocation? = null

    @Volatile
    var currentLocation: TrackedLocation? = null
    val currentLocationQueue = ArrayDeque<TrackedLocation?>()
    val currentLocationCalls = CopyOnWriteArrayList<Pair<DesiredAccuracy, Long>>()

    @Volatile
    var lastLocationCalls = 0

    /** Current request per listener (a copy). */
    val active: Map<LocationListener, LocationRequestSpec> get() = synchronized(activeRequests) { activeRequests.toMap() }

    /** True if at least one listener is registered. */
    val isRequesting: Boolean get() = synchronized(activeRequests) { activeRequests.isNotEmpty() }

    override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
        requests += spec to listener
        synchronized(activeRequests) { activeRequests[listener] = spec }
    }

    override fun removeUpdates(listener: LocationListener) {
        removed += listener
        synchronized(activeRequests) { activeRequests.remove(listener) }
    }

    override suspend fun getLastLocation(): TrackedLocation? {
        lastLocationCalls++
        return lastLocation
    }

    override suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation? {
        currentLocationCalls += accuracy to timeoutMs
        return synchronized(currentLocationQueue) {
            if (currentLocationQueue.isNotEmpty()) currentLocationQueue.removeFirst() else currentLocation
        }
    }

    /** Delivers [locations] as one batch to every active listener. */
    fun emit(vararg locations: TrackedLocation) {
        val listeners = synchronized(activeRequests) { activeRequests.keys.toList() }
        for (l in listeners) l.onLocations(locations.toList())
    }
}

/** Scripted [ActivityBackend]; [startResult] is returned by `start`. */
class FakeActivityBackend(
    override val kind: ProviderKind = ProviderKind.GMS,
    override val isSupported: Boolean = true,
) : ActivityBackend {
    val startCalls = CopyOnWriteArrayList<Long>()

    @Volatile
    var stopCalls = 0

    @Volatile
    var running = false

    @Volatile
    var startResult = true

    override fun start(intervalMs: Long): Boolean {
        startCalls += intervalMs
        running = startResult
        return startResult
    }

    override fun stop() {
        stopCalls++
        running = false
    }
}

/**
 * In-memory [GeofenceBackend]. [registered] holds the OS-registered regions by id.
 * If [failWith] is set, every call throws it.
 */
class FakeGeofenceBackend(
    override val supportsDwell: Boolean = true,
    override val kind: ProviderKind = ProviderKind.GMS,
) : GeofenceBackend {
    private val regions = LinkedHashMap<String, OsGeofence>()
    val addCalls = CopyOnWriteArrayList<List<OsGeofence>>()
    val removeCalls = CopyOnWriteArrayList<List<String>>()

    @Volatile
    var removeAllCalls = 0

    @Volatile
    var failWith: TrackingException? = null

    val registered: Map<String, OsGeofence> get() = synchronized(regions) { regions.toMap() }

    override suspend fun add(regions: List<OsGeofence>) {
        failWith?.let { throw it }
        addCalls += regions
        synchronized(this.regions) { for (r in regions) this.regions[r.id] = r }
    }

    override suspend fun remove(ids: List<String>) {
        failWith?.let { throw it }
        removeCalls += ids
        synchronized(regions) { ids.forEach { regions.remove(it) } }
    }

    override suspend fun removeAll() {
        failWith?.let { throw it }
        removeAllCalls++
        synchronized(regions) { regions.clear() }
    }
}

/**
 * [ProviderFactory] over fake backends. [kind] is mutable; [available] drives `isAvailable`;
 * `reselect` returns [reselectResult] and counts [reselectCalls].
 */
class FakeProviderFactory(
    @Volatile override var kind: ProviderKind = ProviderKind.GMS,
    val locationBackend: FakeLocationBackend = FakeLocationBackend(kind),
    val activityBackend: FakeActivityBackend = FakeActivityBackend(kind),
    val geofenceBackend: FakeGeofenceBackend = FakeGeofenceBackend(true, kind),
) : ProviderFactory {
    val available: MutableSet<ProviderKind> = mutableSetOf(kind, ProviderKind.ANDROID)

    @Volatile
    var reselectResult = false

    @Volatile
    var reselectCalls = 0

    override fun location(): LocationBackend = locationBackend

    override fun activity(): ActivityBackend = activityBackend

    override fun geofence(): GeofenceBackend = geofenceBackend

    override fun isAvailable(kind: ProviderKind): Boolean = kind in available

    override fun reselect(): Boolean {
        reselectCalls++
        return reselectResult
    }
}
