package com.brickssoft.locationtracking.provider.gms

import android.content.Context
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.ProviderBundle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices

/**
 * Google Play services backends: fused location, activity recognition and geofencing.
 *
 * Created reflectively through the public `(Context)` constructor. The other parameters exist so tests can inject
 * the availability check and the GMS clients. Each backend (and its client) is created on first use.
 */
class GmsProviderBundle @JvmOverloads constructor(
    context: Context,
    private val availabilityCheck: (Context) -> Int = {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(it)
    },
    private val fusedClient: (Context) -> FusedLocationProviderClient = {
        LocationServices.getFusedLocationProviderClient(it)
    },
    private val activityClient: (Context) -> ActivityRecognitionClient = { ActivityRecognition.getClient(it) },
    private val geofencingClient: (Context) -> GeofencingClient = { LocationServices.getGeofencingClient(it) },
) : ProviderBundle {
    private val appContext: Context = context.applicationContext ?: context

    override val kind: ProviderKind = ProviderKind.GMS

    private val locationBackend by lazy { GmsLocationBackend(fusedClient(appContext)) }
    private val activityBackend by lazy { GmsActivityBackend(appContext, activityClient(appContext)) }
    private val geofenceBackend by lazy { GmsGeofenceBackend(appContext, geofencingClient(appContext)) }

    /** True if `GoogleApiAvailability` reports SUCCESS; false on any other result or failure. */
    override fun isAvailable(): Boolean = try {
        val status = availabilityCheck(appContext)
        if (status != ConnectionResult.SUCCESS) Logger.d(TAG, "Google Play services unavailable: status $status")
        status == ConnectionResult.SUCCESS
    } catch (t: Throwable) {
        Logger.w(TAG, "Google Play services availability check failed", t)
        false
    }

    override fun location(): LocationBackend = locationBackend

    override fun activity(): ActivityBackend = activityBackend

    override fun geofence(): GeofenceBackend = geofenceBackend

    private companion object {
        const val TAG = "LT.Gms"
    }
}
