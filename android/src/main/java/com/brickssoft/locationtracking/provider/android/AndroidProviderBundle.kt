package com.brickssoft.locationtracking.provider.android

import android.content.Context
import android.location.LocationManager
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.ProviderBundle

/**
 * The platform `LocationManager` backends, the fallback when neither GMS nor HMS is usable. Created reflectively via
 * its public (Context) constructor. Each backend is created once, on first use.
 */
class AndroidProviderBundle(context: Context) : ProviderBundle {
    private val appContext: Context = context.applicationContext ?: context
    override val kind: ProviderKind = ProviderKind.ANDROID

    private val location by lazy { AndroidLocationBackend(appContext) }
    private val activity by lazy { AndroidActivityBackend() }
    private val geofence by lazy { AndroidGeofenceBackend(appContext) }

    /** True if the device has a [LocationManager]. */
    override fun isAvailable(): Boolean = appContext.getSystemService(Context.LOCATION_SERVICE) is LocationManager

    override fun location(): LocationBackend = location

    override fun activity(): ActivityBackend = activity

    override fun geofence(): GeofenceBackend = geofence
}
