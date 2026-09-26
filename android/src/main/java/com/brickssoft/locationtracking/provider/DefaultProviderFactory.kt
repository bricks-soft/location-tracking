// STUB — owned by Unit 6 (Android fallback + factory). Replace this implementation.
package com.brickssoft.locationtracking.provider

import android.content.Context
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation

/** Selects GMS / HMS / Android by config and availability, loading bundles by reflection. Stub: always ANDROID, no-op. */
class DefaultProviderFactory(
    @Suppress("unused") private val context: Context,
    @Suppress("unused") private val configStore: ConfigStore,
) : ProviderFactory {
    override val kind: ProviderKind = ProviderKind.ANDROID

    override fun location(): LocationBackend = NoopLocation

    override fun activity(): ActivityBackend = NoopActivity

    override fun geofence(): GeofenceBackend = NoopGeofence

    override fun isAvailable(kind: ProviderKind): Boolean = kind == ProviderKind.ANDROID

    override fun reselect(): Boolean = false

    private object NoopLocation : LocationBackend {
        override val kind = ProviderKind.ANDROID

        override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) = Unit

        override fun removeUpdates(listener: LocationListener) = Unit

        override suspend fun getLastLocation(): TrackedLocation? = null

        override suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation? = null
    }

    private object NoopActivity : ActivityBackend {
        override val kind = ProviderKind.ANDROID
        override val isSupported = false

        override fun start(intervalMs: Long): Boolean = false

        override fun stop() = Unit
    }

    private object NoopGeofence : GeofenceBackend {
        override val kind = ProviderKind.ANDROID
        override val supportsDwell = false

        override suspend fun add(regions: List<OsGeofence>) = Unit

        override suspend fun remove(ids: List<String>) = Unit

        override suspend fun removeAll() = Unit
    }
}
