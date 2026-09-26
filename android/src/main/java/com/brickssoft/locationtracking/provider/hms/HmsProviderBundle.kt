// STUB — owned by Unit 5 (HMS). Replace this implementation.
package com.brickssoft.locationtracking.provider.hms

import android.content.Context
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.OsGeofence
import com.brickssoft.locationtracking.provider.ProviderBundle

/** Created reflectively via its public (Context) constructor. Stub: unavailable, no-op backends. */
class HmsProviderBundle(@Suppress("unused") private val context: Context) : ProviderBundle {
    override val kind: ProviderKind = ProviderKind.HMS

    override fun isAvailable(): Boolean = false

    override fun location(): LocationBackend = NoopLocation

    override fun activity(): ActivityBackend = NoopActivity

    override fun geofence(): GeofenceBackend = NoopGeofence

    private object NoopLocation : LocationBackend {
        override val kind = ProviderKind.HMS

        override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) = Unit

        override fun removeUpdates(listener: LocationListener) = Unit

        override suspend fun getLastLocation(): TrackedLocation? = null

        override suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation? = null
    }

    private object NoopActivity : ActivityBackend {
        override val kind = ProviderKind.HMS
        override val isSupported = false

        override fun start(intervalMs: Long): Boolean = false

        override fun stop() = Unit
    }

    private object NoopGeofence : GeofenceBackend {
        override val kind = ProviderKind.HMS
        override val supportsDwell = false

        override suspend fun add(regions: List<OsGeofence>) = Unit

        override suspend fun remove(ids: List<String>) = Unit

        override suspend fun removeAll() = Unit
    }
}
