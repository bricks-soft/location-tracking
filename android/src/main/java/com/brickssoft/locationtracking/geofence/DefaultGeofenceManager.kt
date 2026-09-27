package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.data.GeofenceRuntime
import com.brickssoft.locationtracking.data.GeofenceStore
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.OsGeofence
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.provider.StationaryRegionSink
import com.brickssoft.locationtracking.record.RecordSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default [GeofenceManager]: validation, persistence, OS registration, polygon hit-testing and dwell synthesis.
 *
 * - **Circles** are registered as they are and their transitions come from the OS. When the backend has no dwell
 *   support, DWELL is synthesized with [DwellTracker] (continuously inside for `loiteringDelay` after ENTER).
 * - **Polygons** are registered as their padded enclosing circle ([PolygonMath.enclosingCircle]) with ENTER, EXIT
 *   and an initial ENTER trigger. The circle state only drives [needsContinuousLocation]. Polygon ENTER and EXIT
 *   come from [onLocation] by ray casting, with hysteresis: a fix changes the state when its accuracy circle lies
 *   entirely on one side of the boundary, or when two consecutive fixes agree. Leaving the circle also means
 *   leaving the polygon. Polygon DWELL is always synthesized.
 * - `initialTriggerEntry=false` silences the first polygon classification when it reflects the state at
 *   registration: when it happens within [INITIAL_TRIGGER_WINDOW_MS] of registering, or follows a circle ENTER
 *   received within that window (the OS's initial trigger). A circle ENTER after the window, or a fresh fix
 *   outside the enclosing circle at registration, means the polygon was outside. Without a fresh fix, a real
 *   entry within the window cannot be told apart from the initial state and is silent too.
 * - Every notified transition becomes a `geofence` record (submitted to [RecordSink], so it is persisted and
 *   uploaded) plus a [TrackingEvent.Geofence].
 *
 * Per-geofence runtime ([GeofenceRuntime]) is persisted, restored when first loaded while tracking is enabled and
 * reset by [onTrackingStopped]. `insideCircle` is the state of the (enclosing) circle. `insidePolygon` is the
 * polygon state. `enteredAt` is the wall time at which the current inside state began (circles) or at which the
 * current polygon state was established (polygons: null = not classified yet, [ENTERED_BEFORE_MONITORING] = a
 * silent initial inside). A restored state may be stale, so a restored circle ENTER is not treated as a duplicate,
 * and a fix that is certainly outside a restored inside circle reports the EXIT missed while the process was
 * gone. A synthesized dwell is re-armed after a restart only if its deadline has not passed.
 *
 * State changes are serialized with a [Mutex]; [onLocation] only launches work on [scope].
 */
class DefaultGeofenceManager(
    private val geofenceStore: GeofenceStore,
    private val providers: ProviderFactory,
    private val configStore: ConfigStore,
    private val recordFactory: RecordFactory,
    private val recordSink: RecordSink,
    private val events: EventBus,
    private val clock: Clock,
    private val scope: CoroutineScope,
    // Unit 2 routes STATIONARY_REGION_ID transitions here (never stored, recorded or emitted).
    @Suppress("unused")
    private val stationarySink: Lazy<StationaryRegionSink> = lazyOf(StationaryRegionSink.NONE),
) : GeofenceManager {
    private class Entry(val spec: GeofenceSpec, var runtime: GeofenceRuntime) {
        /** Circle: the OS reported (or a fix confirmed) the state in this process; restored state may be stale. */
        var observed = false

        /** Polygon: an unclassified state is the initial state until this time (the OS initial-trigger window). */
        var initialUntil = Long.MIN_VALUE

        /** Polygon: the OS initial circle ENTER arrived, so the first classification is the initial state. */
        var initialLatched = false

        /** Polygon: the last fix classified, so the same fix from two sources counts once. */
        var lastFix: TrackedLocation? = null

        /** Polygon hysteresis: the unconfirmed classification and how many consecutive fixes agreed with it. */
        var candidate: Boolean? = null
        var candidateCount = 0

        fun resetCandidate() {
            candidate = null
            candidateCount = 0
        }
    }

    private val mutex = Mutex()

    // Guarded by [mutex].
    private val entries = LinkedHashMap<String, Entry>()
    private var registeredBackend: GeofenceBackend? = null

    /** The last fix of the location stream (for ordering). */
    private var lastStreamFix: TrackedLocation? = null

    /** True after [onTrackingStarted], false after [onTrackingStopped], null in a process that saw neither. */
    @Volatile
    private var session: Boolean? = null

    @Volatile
    private var loaded = false

    /** True while fixes matter: active polygons, or restored inside circles to reconcile. */
    @Volatile
    private var wantsFixes = false

    @Volatile
    private var lastFix: TrackedLocation? = null

    private val dwell = DwellTracker(scope, clock) { id, token -> mutex.withLock { fireDwellLocked(id, token) } }

    private val needs = MutableStateFlow(false)
    override val needsContinuousLocation: StateFlow<Boolean> = needs.asStateFlow()

    override suspend fun add(geofences: List<GeofenceSpec>) {
        if (geofences.isEmpty()) return
        val incoming = LinkedHashMap<String, GeofenceSpec>()
        for (spec in geofences) validated(spec).let { incoming[it.identifier] = it }
        val specs = incoming.values.toList()

        mutex.withLock {
            ensureLoadedLocked()
            val newIds = specs.map { it.identifier }.filter { it !in entries }
            val total = entries.size + newIds.size
            if (total > Constants.MAX_GEOFENCES) {
                throw TrackingException(
                    ErrorCode.TOO_MANY_GEOFENCES,
                    "at most ${Constants.MAX_GEOFENCES} geofences are supported (this would make $total)",
                )
            }
            val replaced = specs.mapNotNull { entries[it.identifier]?.spec }
            val backend = if (configStore.runtime.value.enabled) backendLocked() else null
            if (backend != null) {
                try {
                    val deactivated = replaced.filter { isActive(it) }.map { it.identifier } -
                        specs.filter { isActive(it) }.map { it.identifier }.toSet()
                    if (deactivated.isNotEmpty()) backend.remove(deactivated)
                    registerLocked(backend, specs, throwOnError = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    rollbackRegistrationLocked(backend, newIds, replaced)
                    throw e.toTrackingException(ErrorCode.INTERNAL, "failed to register geofences")
                }
            }
            try {
                geofenceStore.upsert(specs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (backend != null) rollbackRegistrationLocked(backend, newIds, replaced)
                throw e.toTrackingException(ErrorCode.IO_ERROR, "failed to store geofences")
            }
            val now = clock.now()
            for (spec in specs) {
                val previous = entries[spec.identifier]
                dwell.cancel(spec.identifier)
                val entry = Entry(spec, UNKNOWN)
                entries[spec.identifier] = entry
                if (previous != null && previous.runtime != UNKNOWN) persistRuntime(spec.identifier, UNKNOWN)
                if (backend != null) openInitialWindowLocked(entry, now)
            }
            refreshDerivedLocked()
            Logger.i(TAG, "added ${specs.size} geofence(s): ${specs.joinToString { it.identifier }}")
            events.emit(TrackingEvent.GeofencesChange(on = specs, off = emptyList()))
        }
    }

    override suspend fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            ensureLoadedLocked()
            val requested = ids.distinct()
            val existing = requested.filter { it in entries }
            if (existing.size < requested.size) {
                Logger.d(TAG, "remove: unknown geofence(s) ${(requested - existing.toSet()).joinToString()}")
            }
            if (existing.isEmpty()) return
            try {
                geofenceStore.remove(existing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.toTrackingException(ErrorCode.IO_ERROR, "failed to remove geofences")
            }
            if (configStore.runtime.value.enabled) {
                removeFromOsLocked(backendLocked(), existing.filter { isActive(entries.getValue(it).spec) })
            }
            for (id in existing) {
                dwell.cancel(id)
                entries.remove(id)
            }
            refreshDerivedLocked()
            Logger.i(TAG, "removed ${existing.size} geofence(s): ${existing.joinToString()}")
            events.emit(TrackingEvent.GeofencesChange(on = emptyList(), off = existing))
        }
    }

    override suspend fun removeAll() {
        mutex.withLock {
            ensureLoadedLocked()
            val ids = entries.keys.toList()
            try {
                geofenceStore.removeAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.toTrackingException(ErrorCode.IO_ERROR, "failed to remove geofences")
            }
            if (configStore.runtime.value.enabled) {
                val current = providers.geofence()
                for (backend in listOfNotNull(registeredBackend, current).distinctBy { it.kind }) {
                    try {
                        backend.removeAll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "failed to unregister all geofences from ${backend.kind.wire}", e)
                    }
                }
                registeredBackend = current
            }
            dwell.cancelAll()
            entries.clear()
            refreshDerivedLocked()
            if (ids.isEmpty()) return
            Logger.i(TAG, "removed all ${ids.size} geofence(s)")
            events.emit(TrackingEvent.GeofencesChange(on = emptyList(), off = ids))
        }
    }

    override suspend fun list(): List<GeofenceSpec> = geofenceStore.all()

    override suspend fun get(id: String): GeofenceSpec? = geofenceStore.get(id)

    override suspend fun onTrackingStarted(mode: TrackingMode) {
        mutex.withLock {
            session = true
            try {
                ensureLoadedLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "cannot load geofences; none registered", e)
                return
            }
            val current = providers.geofence()
            registeredBackend?.takeIf { it.kind != current.kind }?.let {
                removeFromOsLocked(it, registrableIdsLocked())
            }
            registeredBackend = current
            registerAllLocked(current)
            refreshDwellTimersLocked()
            refreshDerivedLocked()
            Logger.i(TAG, "tracking started (${mode.wire}): ${entries.size} geofence(s), backend ${current.kind.wire}")
        }
    }

    override suspend fun onTrackingStopped() {
        mutex.withLock {
            session = false
            dwell.cancelAll()
            lastStreamFix = null
            try {
                ensureLoadedLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "cannot load geofences to unregister them", e)
                refreshDerivedLocked()
                return
            }
            removeFromOsLocked(registeredBackend ?: providers.geofence(), registrableIdsLocked())
            registeredBackend = null
            for (entry in entries.values) {
                entry.observed = false
                entry.initialLatched = false
                entry.lastFix = null
                entry.resetCandidate()
                if (entry.runtime != UNKNOWN) persistRuntime(entry, UNKNOWN)
            }
            refreshDerivedLocked()
            Logger.i(TAG, "tracking stopped: ${entries.size} geofence(s) unregistered")
        }
    }

    override fun onLocation(location: TrackedLocation) {
        val previous = lastFix
        if (previous == null || location.time >= previous.time) lastFix = location
        if (session == false || !configStore.runtime.value.enabled) return
        if (loaded && !wantsFixes && !dwell.hasArmed()) return
        scope.launch {
            mutex.withLock {
                try {
                    handleLocationLocked(location)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "failed to process location for geofences", e)
                }
            }
        }
    }

    override suspend fun onGeofenceTransitions(transitions: List<OsGeofenceTransition>) {
        if (transitions.isEmpty()) return
        mutex.withLock {
            if (session == false || !configStore.runtime.value.enabled) {
                Logger.w(TAG, "ignoring ${transitions.size} geofence transition(s): tracking is stopped")
                return
            }
            try {
                ensureLoadedLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "cannot load geofences; dropping ${transitions.size} transition(s)", e)
                return
            }
            for (transition in transitions) {
                val entry = entries[transition.id]
                if (entry == null) {
                    Logger.w(TAG, "ignoring ${transition.action} for unknown geofence '${transition.id}'")
                    continue
                }
                Logger.d(TAG, "OS transition ${transition.action} '${transition.id}'")
                if (entry.spec.isPolygon) {
                    onPolygonCircleTransitionLocked(entry, transition)
                } else {
                    onCircleTransitionLocked(entry, transition)
                }
            }
            refreshDerivedLocked()
        }
    }

    // ---- circles

    private suspend fun onCircleTransitionLocked(entry: Entry, transition: OsGeofenceTransition) {
        val spec = entry.spec
        val synthesizeDwell = synthesizesDwell(spec, activeBackend())
        val wasObserved = entry.observed
        entry.observed = true
        when (transition.action) {
            GeofenceAction.ENTER -> {
                // With ENTER and EXIT both registered, a second ENTER while inside (the OS initial trigger after
                // re-registering) is a duplicate. Restored state may be stale, so it only counts once observed.
                val tracksState = (spec.notifyOnEntry || synthesizeDwell) && (spec.notifyOnExit || synthesizeDwell)
                if (tracksState && wasObserved && entry.runtime.insideCircle) {
                    Logger.d(TAG, "duplicate ENTER '${spec.identifier}' ignored")
                    return
                }
                val now = clock.now()
                persistRuntime(entry, GeofenceRuntime(insideCircle = true, insidePolygon = false, enteredAt = now))
                if (spec.notifyOnEntry) notifyLocked(spec, GeofenceAction.ENTER, transition.location)
                if (synthesizeDwell) dwell.arm(spec.identifier, now + spec.loiteringDelay, token = now)
            }
            GeofenceAction.EXIT -> {
                dwell.cancel(spec.identifier)
                persistRuntime(entry, UNKNOWN)
                if (spec.notifyOnExit) notifyLocked(spec, GeofenceAction.EXIT, transition.location)
            }
            GeofenceAction.DWELL -> {
                dwell.cancel(spec.identifier)
                if (!entry.runtime.insideCircle) {
                    val now = clock.now()
                    persistRuntime(entry, GeofenceRuntime(insideCircle = true, insidePolygon = false, enteredAt = now))
                }
                if (spec.notifyOnDwell) notifyLocked(spec, GeofenceAction.DWELL, transition.location)
            }
        }
    }

    /** A restored inside circle is checked against fixes: certainly outside means the OS EXIT was lost. */
    private suspend fun reconcileCircleLocked(entry: Entry, location: TrackedLocation) {
        val spec = entry.spec
        val accuracy = accuracyOf(location)
        val fromCenter = distanceFromCenter(spec, location)
        when {
            fromCenter - accuracy > spec.radius -> {
                Logger.i(TAG, "'${spec.identifier}' was left while tracking was not running")
                entry.observed = true
                dwell.cancel(spec.identifier)
                persistRuntime(entry, UNKNOWN)
                if (spec.notifyOnExit) notifyLocked(spec, GeofenceAction.EXIT, location)
            }
            fromCenter + accuracy <= spec.radius -> entry.observed = true
        }
    }

    // ---- polygons

    private suspend fun onPolygonCircleTransitionLocked(entry: Entry, transition: OsGeofenceTransition) {
        when (transition.action) {
            GeofenceAction.ENTER -> {
                var runtime = entry.runtime.copy(insideCircle = true)
                if (runtime.enteredAt == null) {
                    if (clock.now() <= entry.initialUntil) {
                        entry.initialLatched = true // the OS initial trigger: we were in the circle at registration
                    } else {
                        runtime = runtime.copy(insidePolygon = false, enteredAt = clock.now()) // a real entry
                    }
                }
                if (runtime != entry.runtime) persistRuntime(entry, runtime)
                val location = transition.location ?: return
                val previous = lastStreamFix
                if (previous == null || location.time >= previous.time) evaluatePolygonLocked(entry, location)
            }
            GeofenceAction.EXIT -> {
                // The enclosing circle contains the polygon, so leaving it means leaving the polygon.
                val wasInside = entry.runtime.insidePolygon
                val stateSince = entry.runtime.enteredAt.takeUnless { wasInside } ?: clock.now()
                dwell.cancel(entry.spec.identifier)
                entry.resetCandidate()
                entry.initialLatched = false
                persistRuntime(entry, GeofenceRuntime(insideCircle = false, insidePolygon = false, stateSince))
                if (wasInside && entry.spec.notifyOnExit) {
                    notifyLocked(entry.spec, GeofenceAction.EXIT, transition.location)
                }
            }
            GeofenceAction.DWELL -> Logger.d(TAG, "ignoring OS DWELL for polygon '${entry.spec.identifier}'")
        }
    }

    private suspend fun handleLocationLocked(location: TrackedLocation) {
        if (session == false || !configStore.runtime.value.enabled) return
        ensureLoadedLocked()
        val previous = lastStreamFix
        if (previous != null) {
            val olderBy = previous.time - location.time
            // Skip a fix older than the previous one (unless the gap is so large that the earlier timestamp must have
            // been bogus) and the same fix delivered twice.
            if (olderBy in 1..MAX_REORDER_MS || (olderBy == 0L && sameFix(previous, location))) return
        }
        lastStreamFix = location
        for (entry in entries.values) {
            when {
                entry.spec.isPolygon -> evaluatePolygonLocked(entry, location)
                !entry.observed && entry.runtime.insideCircle && isActive(entry.spec) ->
                    reconcileCircleLocked(entry, location)
            }
        }
        for ((id, token) in dwell.takeDue()) fireDwellLocked(id, token)
        refreshDerivedLocked()
    }

    /** Classifies [location] against the polygon of [entry] and applies a confirmed state change. */
    private suspend fun evaluatePolygonLocked(entry: Entry, location: TrackedLocation) {
        val spec = entry.spec
        val vertices = spec.vertices ?: return
        if (!isActive(spec)) return
        entry.lastFix?.let { if (sameFix(it, location)) return } // e.g. from an OS transition, then the stream
        entry.lastFix = location
        val accuracy = accuracyOf(location)
        val fromCenter = distanceFromCenter(spec, location)
        val inside: Boolean
        val certain: Boolean
        if (fromCenter - accuracy > spec.radius) {
            inside = false
            certain = true
            // Certainly outside the enclosing circle: covers a late or lost OS EXIT and stale restored state.
            if (entry.runtime.insideCircle) persistRuntime(entry, entry.runtime.copy(insideCircle = false))
        } else {
            inside = PolygonMath.contains(vertices, location.latitude, location.longitude)
            certain = accuracy == 0.0 ||
                PolygonMath.distanceToBoundary(vertices, location.latitude, location.longitude) >= accuracy
        }
        val known = entry.runtime.enteredAt != null
        if (known && inside == entry.runtime.insidePolygon) {
            entry.resetCandidate()
            return
        }
        if (!certain) {
            if (entry.candidate == inside) {
                entry.candidateCount++
            } else {
                entry.candidate = inside
                entry.candidateCount = 1
            }
            if (entry.candidateCount < CONSISTENT_FIXES) return
        }
        entry.resetCandidate()

        val now = clock.now()
        val silent = !known && !configStore.config.value.geofence.initialTriggerEntry &&
            (entry.initialLatched || now <= entry.initialUntil)
        entry.initialLatched = false
        if (inside && silent) {
            persistRuntime(entry, entry.runtime.copy(insidePolygon = true, enteredAt = ENTERED_BEFORE_MONITORING))
        } else if (inside) {
            persistRuntime(entry, entry.runtime.copy(insidePolygon = true, enteredAt = now))
            if (spec.notifyOnEntry) notifyLocked(spec, GeofenceAction.ENTER, location)
            if (spec.notifyOnDwell) dwell.arm(spec.identifier, now + spec.loiteringDelay, token = now)
        } else {
            dwell.cancel(spec.identifier)
            persistRuntime(entry, entry.runtime.copy(insidePolygon = false, enteredAt = now))
            if (known && spec.notifyOnExit) notifyLocked(spec, GeofenceAction.EXIT, location)
        }
    }

    // ---- dwell

    /** Emits a synthesized DWELL if [id] is still inside since [token] (the `enteredAt` the timer was armed with). */
    private suspend fun fireDwellLocked(id: String, token: Long) {
        if (session == false || !configStore.runtime.value.enabled) return
        val entry = entries[id] ?: return
        val inside = if (entry.spec.isPolygon) entry.runtime.insidePolygon else entry.runtime.insideCircle
        if (!inside || entry.runtime.enteredAt != token || !entry.spec.notifyOnDwell) return
        notifyLocked(entry.spec, GeofenceAction.DWELL, null)
    }

    /** Aligns synthesized dwell timers with the current backend and the (possibly restored) runtime. */
    private fun refreshDwellTimersLocked() {
        val now = clock.now()
        val backend = activeBackend()
        for (entry in entries.values) {
            val spec = entry.spec
            val synthesized = spec.notifyOnDwell && (spec.isPolygon || synthesizesDwell(spec, backend))
            if (!synthesized) {
                dwell.cancel(spec.identifier)
                continue
            }
            if (dwell.isArmed(spec.identifier)) continue
            val enteredAt = entry.runtime.enteredAt ?: continue
            val inside = if (spec.isPolygon) entry.runtime.insidePolygon else entry.runtime.insideCircle
            val deadline = enteredAt + spec.loiteringDelay
            // A deadline that passed while the process was gone is assumed to have fired already.
            if (inside && enteredAt != ENTERED_BEFORE_MONITORING && deadline > now) {
                dwell.arm(spec.identifier, deadline, token = enteredAt)
            }
        }
    }

    // ---- records

    /** Creates, submits and emits a geofence record. Never throws (except cancellation). */
    private suspend fun notifyLocked(spec: GeofenceSpec, action: GeofenceAction, location: TrackedLocation?) {
        try {
            val hit = GeofenceHit(spec.identifier, action, spec.extras)
            val record = recordFactory.create(RecordEvent.GEOFENCE, location ?: bestKnownLocation(), geofence = hit)
            try {
                recordSink.submit(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "failed to submit geofence record ${record.uuid}", e)
            }
            Logger.i(TAG, "geofence $action '${spec.identifier}'")
            events.emit(TrackingEvent.Geofence(spec.identifier, action, record, spec.extras))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "failed to record geofence $action '${spec.identifier}'", e)
        }
    }

    private fun bestKnownLocation(): TrackedLocation? {
        val fix = lastFix
        val last = configStore.runtime.value.lastLocation
        return when {
            fix == null -> last
            last == null -> fix
            else -> if (fix.time >= last.time) fix else last
        }
    }

    // ---- OS registration

    private fun activeBackend(): GeofenceBackend = registeredBackend ?: providers.geofence()

    /** The backend to register with; moves the registrations if the provider changed since they were made. */
    private suspend fun backendLocked(): GeofenceBackend {
        val current = providers.geofence()
        val previous = registeredBackend
        registeredBackend = current
        if (previous != null && previous.kind != current.kind) {
            Logger.i(TAG, "geofence backend changed ${previous.kind.wire} -> ${current.kind.wire}")
            removeFromOsLocked(previous, registrableIdsLocked())
            registerAllLocked(current)
            refreshDwellTimersLocked()
        }
        return current
    }

    /** Registers every stored geofence; the OS's initial triggers follow, so polygons open their initial window. */
    private suspend fun registerAllLocked(backend: GeofenceBackend) {
        val now = clock.now()
        for (entry in entries.values) openInitialWindowLocked(entry, now)
        registerLocked(backend, entries.values.map { it.spec }, throwOnError = false)
    }

    /**
     * Starts the initial-trigger window of an unclassified polygon being registered. A fresh known fix that is
     * certainly outside its enclosing circle settles the state as outside instead, so an entry soon after
     * registration is not mistaken for the OS's initial trigger.
     */
    private suspend fun openInitialWindowLocked(entry: Entry, now: Long) {
        entry.initialUntil = now + INITIAL_TRIGGER_WINDOW_MS
        entry.initialLatched = false
        val spec = entry.spec
        if (!spec.isPolygon || entry.runtime.enteredAt != null) return
        val fix = bestKnownLocation() ?: return
        if (now - fix.time > INITIAL_TRIGGER_WINDOW_MS) return
        if (distanceFromCenter(spec, fix) - accuracyOf(fix) > spec.radius) {
            persistRuntime(entry, GeofenceRuntime(insideCircle = false, insidePolygon = false, enteredAt = now))
        }
    }

    private suspend fun registerLocked(backend: GeofenceBackend, specs: List<GeofenceSpec>, throwOnError: Boolean) {
        val initialTriggerEntry = configStore.config.value.geofence.initialTriggerEntry
        val regions = specs.mapNotNull { toOsGeofence(it, backend, initialTriggerEntry) }
        for (batch in regions.chunked(BATCH_SIZE)) {
            try {
                backend.add(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (throwOnError) throw e
                Logger.e(TAG, "failed to register ${batch.size} geofence(s) with ${backend.kind.wire}", e)
            }
        }
    }

    private suspend fun removeFromOsLocked(backend: GeofenceBackend, ids: List<String>) {
        for (batch in ids.chunked(BATCH_SIZE)) {
            try {
                backend.remove(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "failed to unregister ${batch.size} geofence(s) from ${backend.kind.wire}", e)
            }
        }
    }

    /** Best effort: unregisters [newIds] and re-registers the [replaced] versions after a failed add. */
    private suspend fun rollbackRegistrationLocked(
        backend: GeofenceBackend,
        newIds: List<String>,
        replaced: List<GeofenceSpec>,
    ) {
        removeFromOsLocked(backend, newIds)
        registerLocked(backend, replaced, throwOnError = false)
    }

    /** Ids of the geofences that [toOsGeofence] registers. */
    private fun registrableIdsLocked(): List<String> =
        entries.values.filter { isActive(it.spec) }.map { it.spec.identifier }

    // ---- state

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val specs = geofenceStore.all()
        val runtimes = geofenceStore.runtimes()
        // Runtime is only meaningful while tracking; stale values from a stopped session are discarded.
        val keepRuntime = configStore.runtime.value.enabled
        entries.clear()
        for (stored in specs) {
            val spec = if (stored.isPolygon && !(stored.radius > 0f)) withEnclosingCircle(stored) else stored
            val runtime = runtimes[spec.identifier]
            entries[spec.identifier] = Entry(spec, if (keepRuntime) runtime ?: UNKNOWN else UNKNOWN)
            if (!keepRuntime && runtime != null && runtime != UNKNOWN) persistRuntime(spec.identifier, UNKNOWN)
        }
        loaded = true
        refreshDerivedLocked()
    }

    private fun refreshDerivedLocked() {
        val polygons = entries.values.filter { it.spec.isPolygon && isActive(it.spec) }
        wantsFixes = polygons.isNotEmpty() ||
            entries.values.any { !it.spec.isPolygon && !it.observed && it.runtime.insideCircle && isActive(it.spec) }
        needs.value = session == true && polygons.any { it.runtime.insideCircle || it.runtime.insidePolygon }
    }

    private suspend fun persistRuntime(entry: Entry, runtime: GeofenceRuntime) {
        entry.runtime = runtime
        persistRuntime(entry.spec.identifier, runtime)
    }

    private suspend fun persistRuntime(id: String, runtime: GeofenceRuntime) {
        try {
            geofenceStore.setRuntime(id, runtime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "failed to persist runtime of geofence '$id'", e)
        }
    }

    private companion object {
        const val TAG = "LT.Geofences"

        /** Geofences per OS registration call. */
        const val BATCH_SIZE = 25

        /** Consecutive agreeing fixes that confirm a polygon transition when accuracy straddles the boundary. */
        const val CONSISTENT_FIXES = 2

        /** How long after registration the OS's initial circle ENTER is expected. */
        const val INITIAL_TRIGGER_WINDOW_MS = 120_000L

        /** A fix older than the last evaluated one by up to this much is out of order and skipped. */
        const val MAX_REORDER_MS = 120_000L

        /** `enteredAt` of a polygon found inside by its silent initial classification (no ENTER, no DWELL). */
        const val ENTERED_BEFORE_MONITORING = 0L

        /** Longest identifier accepted (the GMS / HMS request-id limit). */
        const val MAX_IDENTIFIER_LENGTH = 100

        /** Smallest polygon area (m²) that is not considered degenerate. */
        const val MIN_POLYGON_AREA_M2 = 1.0

        /** Runtime of a geofence whose state is not known. */
        val UNKNOWN = GeofenceRuntime(insideCircle = false, insidePolygon = false, enteredAt = null)

        fun isActive(spec: GeofenceSpec): Boolean = spec.notifyOnEntry || spec.notifyOnExit || spec.notifyOnDwell

        /** Circle DWELL is synthesized when the backend cannot report it. */
        fun synthesizesDwell(spec: GeofenceSpec, backend: GeofenceBackend): Boolean =
            !spec.isPolygon && spec.notifyOnDwell && !backend.supportsDwell

        fun sameFix(a: TrackedLocation, b: TrackedLocation): Boolean =
            a.time == b.time && a.latitude == b.latitude && a.longitude == b.longitude

        fun distanceFromCenter(spec: GeofenceSpec, location: TrackedLocation): Double =
            PolygonMath.distanceMeters(spec.latitude, spec.longitude, location.latitude, location.longitude)

        fun accuracyOf(location: TrackedLocation): Double =
            location.accuracy.toDouble().takeIf { it.isFinite() && it > 0 } ?: 0.0

        fun toOsGeofence(spec: GeofenceSpec, backend: GeofenceBackend, initialTriggerEntry: Boolean): OsGeofence? {
            if (!isActive(spec)) return null
            val loiteringDelay = spec.loiteringDelay.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            if (spec.isPolygon) {
                // The circle only tells us when hit-testing is needed; the initial ENTER is always wanted.
                return OsGeofence(
                    id = spec.identifier,
                    latitude = spec.latitude,
                    longitude = spec.longitude,
                    radius = spec.radius,
                    onEntry = true,
                    onExit = true,
                    onDwell = false,
                    loiteringDelayMs = loiteringDelay,
                    initialTriggerEntry = true,
                )
            }
            val synthesizeDwell = synthesizesDwell(spec, backend)
            return OsGeofence(
                id = spec.identifier,
                latitude = spec.latitude,
                longitude = spec.longitude,
                radius = spec.radius,
                onEntry = spec.notifyOnEntry || synthesizeDwell,
                onExit = spec.notifyOnExit || synthesizeDwell,
                onDwell = spec.notifyOnDwell && backend.supportsDwell,
                loiteringDelayMs = loiteringDelay,
                initialTriggerEntry = initialTriggerEntry,
            )
        }

        /**
         * Checks [spec] and, for a polygon, fills in its enclosing circle.
         *
         * @throws TrackingException INVALID_ARGUMENT
         */
        fun validated(spec: GeofenceSpec): GeofenceSpec {
            val id = spec.identifier
            if (id.isBlank()) invalid("geofence identifier is required")
            if (id.length > MAX_IDENTIFIER_LENGTH) {
                invalid("geofence identifier is longer than $MAX_IDENTIFIER_LENGTH characters")
            }
            if (spec.loiteringDelay < 0) invalid("geofence '$id': loiteringDelay must be >= 0")
            val vertices = spec.vertices
            if (vertices == null) {
                if (!PolygonMath.isValidLatLng(spec.latitude, spec.longitude)) {
                    invalid("geofence '$id': invalid latitude/longitude (${spec.latitude}, ${spec.longitude})")
                }
                if (!(spec.radius.isFinite() && spec.radius > 0f)) invalid("geofence '$id': radius must be > 0")
                return spec
            }
            if (vertices.size < 3) invalid("geofence '$id': a polygon needs at least 3 vertices")
            vertices.forEachIndexed { i, v ->
                if (!PolygonMath.isValidLatLng(v.latitude, v.longitude)) {
                    invalid("geofence '$id': invalid vertices[$i] (${v.latitude}, ${v.longitude})")
                }
            }
            if (vertices.maxOf { it.longitude } - vertices.minOf { it.longitude } > 180.0) {
                invalid("geofence '$id': polygons crossing the anti-meridian are not supported")
            }
            if (vertices.distinct().size < 3 || PolygonMath.areaSquareMeters(vertices) < MIN_POLYGON_AREA_M2) {
                invalid("geofence '$id': the polygon has no area")
            }
            return withEnclosingCircle(spec)
        }

        fun withEnclosingCircle(spec: GeofenceSpec): GeofenceSpec {
            val circle = PolygonMath.enclosingCircle(spec.vertices ?: return spec)
            return spec.copy(latitude = circle.latitude, longitude = circle.longitude, radius = circle.radius.toFloat())
        }

        fun invalid(message: String): Nothing = throw TrackingException(ErrorCode.INVALID_ARGUMENT, message)

        fun Exception.toTrackingException(code: ErrorCode, message: String): TrackingException = when (this) {
            is TrackingException -> this
            is SecurityException -> TrackingException(ErrorCode.PERMISSION_DENIED, "$message: ${this.message}", this)
            else -> TrackingException(code, "$message: ${this.message}", this)
        }
    }
}
