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
    /** `isHuaweiMobileServicesAvailable(context, minimumVersion)`; injectable for tests. */
    private val versionCheck: (Context, Int) -> Int = { ctx, minimum ->
        HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(ctx, minimum)
    },
) : ProviderBundle {
    private val appContext: Context = context.applicationContext ?: context

    override val kind: ProviderKind = ProviderKind.HMS

    private val locationBackend by lazy { HmsLocationBackend(appContext) }
    private val activityBackend by lazy { HmsActivityBackend(appContext) }
    private val geofenceBackend by lazy { HmsGeofenceBackend(appContext) }

    @Volatile
    private var lastStatus: Int? = null

    /** True if HMS Core reports SUCCESS; any failure (including a missing SDK class) counts as unavailable. */
    override fun isAvailable(): Boolean =
        try {
            val result = availabilityCheck(appContext)
            lastStatus = result
            if (result != ConnectionResult.SUCCESS) Logger.d(TAG, "HMS Core unavailable: result $result")
            result == ConnectionResult.SUCCESS
        } catch (t: Throwable) {
            lastStatus = null
            Logger.w(TAG, "HMS availability check failed", t)
            false
        }

    /** The `HuaweiApiAvailability` result of the last [isAvailable], or null. */
    override fun availabilityStatus(): Int? = lastStatus

    /**
     * A diagnostic when HMS Core is older than 6.3.0.301 ([MIN_RECOMMENDED_HMS_CORE]), the version Huawei's Location
     * Kit documentation asks for (recommendation 15). It does not change the provider choice. Null when HMS Core is
     * that version or newer, or the check fails.
     */
    override fun versionDiagnostic(): String? = try {
        val result = versionCheck(appContext, MIN_RECOMMENDED_HMS_CORE)
        if (result == ConnectionResult.SUCCESS) {
            null
        } else {
            "HMS Core is older than 6.3.0.301 or needs an update (isHuaweiMobileServicesAvailable result $result)"
        }
    } catch (t: Throwable) {
        Logger.w(TAG, "HMS Core version check failed", t)
        null
    }

    override fun location(): LocationBackend = locationBackend

    override fun activity(): ActivityBackend = activityBackend

    override fun geofence(): GeofenceBackend = geofenceBackend

    internal companion object {
        private const val TAG = "LT.HmsBundle"

        /** HMS Core 6.3.0.301, as `isHuaweiMobileServicesAvailable` expects a version (60300301). */
        const val MIN_RECOMMENDED_HMS_CORE = 60300301
    }
}
