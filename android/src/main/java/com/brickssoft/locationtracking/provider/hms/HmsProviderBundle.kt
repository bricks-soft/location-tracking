package com.brickssoft.locationtracking.provider.hms

import android.content.Context
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.ProviderBundle
import com.huawei.hms.api.ConnectionResult
import com.huawei.hms.api.HuaweiApiAvailability

/**
 * HMS (Huawei Location Kit) backends. Created reflectively through the public `(Context)` constructor, only after
 * the HMS SDK class was found on the classpath. Construction touches no HMS API; each backend is created on first
 * use and then reused, so listener and registration state survives repeated `location()`/`activity()`/`geofence()`
 * calls.
 *
 * @param availabilityCheck returns an HMS `ConnectionResult` code for the device; injectable for tests.
 */
class HmsProviderBundle @JvmOverloads constructor(
    context: Context,
    private val availabilityCheck: (Context) -> Int = { ctx ->
        HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(ctx)
    },
) : ProviderBundle {
    private val appContext: Context = context.applicationContext ?: context

    override val kind: ProviderKind = ProviderKind.HMS

    private val locationBackend by lazy { HmsLocationBackend(appContext) }
    private val activityBackend by lazy { HmsActivityBackend(appContext) }
    private val geofenceBackend by lazy { HmsGeofenceBackend(appContext) }

    /** True if HMS Core reports SUCCESS; any failure (including a missing SDK class) counts as unavailable. */
    override fun isAvailable(): Boolean =
        try {
            val result = availabilityCheck(appContext)
            if (result != ConnectionResult.SUCCESS) Logger.d(TAG, "HMS Core unavailable: result $result")
            result == ConnectionResult.SUCCESS
        } catch (t: Throwable) {
            Logger.w(TAG, "HMS availability check failed", t)
            false
        }

    override fun location(): LocationBackend = locationBackend

    override fun activity(): ActivityBackend = activityBackend

    override fun geofence(): GeofenceBackend = geofenceBackend

    private companion object {
        const val TAG = "LT.HmsBundle"
    }
}
