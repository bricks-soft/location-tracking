package com.brickssoft.locationtracking.provider.hms

import android.content.Context
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.OsGeofence
import com.huawei.hmf.tasks.TaskExecutors
import com.huawei.hms.location.Geofence
import com.huawei.hms.location.GeofenceRequest
import com.huawei.hms.location.GeofenceService
import com.huawei.hms.location.LocationServices

/**
 * [GeofenceBackend] over the HMS geofence service. Transitions arrive as broadcasts to [HmsGeofenceReceiver]
 * through [HmsPendingIntents.geofence].
 *
 * - Regions are circles that never expire; ENTER/EXIT/DWELL map to HMS conversions, and DWELL uses the region's
 *   loitering delay.
 * - `initialTriggerEntry` maps to the request's init conversions (ENTER and DWELL), so regions are sent in one
 *   request per distinct value; if a later request fails, the earlier ones of the same call are deleted again.
 *   Duplicate ids within one call keep the last region.
 * - A region with no transition to report is skipped: HMS rejects geofences without conversions.
 * - Every failure is thrown as a `TrackingException` (see [toTrackingException]).
 *
 * The service is created lazily, on first use.
 */
internal class HmsGeofenceBackend(
    private val context: Context,
    serviceProvider: () -> GeofenceService = { LocationServices.getGeofenceService(context) },
) : GeofenceBackend {
    override val kind: ProviderKind = ProviderKind.HMS
    override val supportsDwell: Boolean = true

    private val service: GeofenceService by lazy(serviceProvider)

    override suspend fun add(regions: List<OsGeofence>) {
        val (unique, silent) = regions.associateBy { it.id }.values.partition { hmsConversions(it) != 0 }
        for (region in silent) Logger.w(TAG, "geofence '${region.id}' has no transition to report; not registered")
        if (unique.isEmpty()) return
        val registered = ArrayList<String>(unique.size)
        for ((initConversions, group) in unique.groupBy(::hmsInitConversions)) {
            try {
                hmsCall("createGeofenceList") {
                    val request = GeofenceRequest.Builder()
                        .createGeofenceList(group.map(::buildHmsGeofence))
                        .setInitConversions(initConversions)
                        .setCoordinateType(GeofenceRequest.COORDINATE_TYPE_WGS_84)
                        .build()
                    service.createGeofenceList(request, HmsPendingIntents.geofence(context)).await()
                }
            } catch (e: Exception) {
                rollBack(registered)
                throw e
            }
            group.mapTo(registered) { it.id }
        }
        Logger.d(TAG, "registered ${registered.size} geofence(s)")
    }

    /** Best effort: a failed `add` must not leave the earlier requests of the same call registered. */
    private fun rollBack(ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            service.deleteGeofenceList(ids).addOnFailureListener(TaskExecutors.immediate()) { e ->
                Logger.w(TAG, "rolling back ${ids.size} geofence(s) failed: ${describeHmsError(e)}", e)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "rolling back ${ids.size} geofence(s) failed: ${describeHmsError(e)}", e)
        }
    }

    override suspend fun remove(ids: List<String>) {
        val unique = ids.distinct()
        if (unique.isEmpty()) return
        hmsCall("deleteGeofenceList") { service.deleteGeofenceList(unique).await() }
    }

    override suspend fun removeAll() {
        hmsCall("deleteGeofenceList") { service.deleteGeofenceList(HmsPendingIntents.geofence(context)).await() }
    }

    private companion object {
        const val TAG = "LT.HmsGeofence"
    }
}

/** HMS conversion flags of a region (0 if it reports nothing). */
internal fun hmsConversions(region: OsGeofence): Int {
    var flags = 0
    if (region.onEntry) flags = flags or Geofence.ENTER_GEOFENCE_CONVERSION
    if (region.onExit) flags = flags or Geofence.EXIT_GEOFENCE_CONVERSION
    if (region.onDwell) flags = flags or Geofence.DWELL_GEOFENCE_CONVERSION
    return flags
}

/**
 * Init conversions of the request that carries [region]: fire ENTER (and DWELL after the loitering delay) if the
 * device is already inside when the region is registered, or nothing when `initialTriggerEntry` is off.
 */
internal fun hmsInitConversions(region: OsGeofence): Int =
    if (region.initialTriggerEntry) {
        GeofenceRequest.ENTER_INIT_CONVERSION or GeofenceRequest.DWELL_INIT_CONVERSION
    } else {
        0
    }

/** Builds the HMS geofence for [region]. */
internal fun buildHmsGeofence(region: OsGeofence): Geofence =
    Geofence.Builder()
        .setUniqueId(region.id)
        .setRoundArea(region.latitude, region.longitude, region.radius)
        .setConversions(hmsConversions(region))
        .setDwellDelayTime(region.loiteringDelayMs.coerceAtLeast(0))
        .setValidContinueTime(Geofence.GEOFENCE_NEVER_EXPIRE)
        .build()
