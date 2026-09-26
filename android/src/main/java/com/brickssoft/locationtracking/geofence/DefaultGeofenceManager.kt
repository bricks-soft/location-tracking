// STUB — owned by Unit 13 (Geofences). Replace this implementation.
package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.data.GeofenceStore
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Geofence manager. Stub: keeps no geofences. */
@Suppress("unused")
class DefaultGeofenceManager(
    private val geofenceStore: GeofenceStore,
    private val providers: ProviderFactory,
    private val configStore: ConfigStore,
    private val recordFactory: RecordFactory,
    private val recordSink: RecordSink,
    private val events: EventBus,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : GeofenceManager {
    override val needsContinuousLocation: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun add(geofences: List<GeofenceSpec>) = Unit

    override suspend fun remove(ids: List<String>) = Unit

    override suspend fun removeAll() = Unit

    override suspend fun list(): List<GeofenceSpec> = emptyList()

    override suspend fun get(id: String): GeofenceSpec? = null

    override suspend fun onTrackingStarted(mode: TrackingMode) = Unit

    override suspend fun onTrackingStopped() = Unit

    override fun onLocation(location: TrackedLocation) = Unit

    override suspend fun onGeofenceTransitions(transitions: List<OsGeofenceTransition>) = Unit
}
