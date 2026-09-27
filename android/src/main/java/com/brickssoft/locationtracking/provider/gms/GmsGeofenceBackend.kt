package com.brickssoft.locationtracking.provider.gms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.OsGeofence
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * OS geofencing through `GeofencingClient`. Transitions arrive at [GmsGeofenceReceiver] through a mutable,
 * explicit broadcast PendingIntent. Adding a region with an existing id replaces it.
 */
internal class GmsGeofenceBackend(
    private val context: Context,
    private val client: GeofencingClient,
) : GeofenceBackend {
    override val kind: ProviderKind = ProviderKind.GMS
    override val supportsDwell: Boolean = true

    /**
     * Registers [regions], one GMS request per distinct `initialTriggerEntry` value. Regions that watch no
     * transition are skipped (GMS rejects them). If a later request fails, the regions registered by the earlier
     * requests of this call are removed again (best effort) before the error is thrown.
     *
     * @throws TrackingException see [GmsErrors.map].
     */
    override suspend fun add(regions: List<OsGeofence>) = call("addGeofences") {
        val requests = buildGeofencingRequests(regions)
        val registered = mutableListOf<String>()
        try {
            for (request in requests) {
                client.addGeofences(request, pendingIntent(context)).await()
                registered += request.geofences.map { it.requestId }
            }
        } catch (e: Exception) {
            if (registered.isNotEmpty()) rollBack(registered)
            throw e
        }
        if (registered.isNotEmpty()) Logger.d(TAG, "registered ${registered.size} geofences")
    }

    /** @throws TrackingException see [GmsErrors.map]. */
    override suspend fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        call("removeGeofences") { client.removeGeofences(ids).await() }
    }

    /** Removes every geofence registered with this backend's PendingIntent; a no-op if it does not exist. */
    override suspend fun removeAll() = call("removeAllGeofences") {
        val pending = existingPendingIntent(context) ?: return@call
        client.removeGeofences(pending).await()
    }

    /** Best effort; never replaces the original error (unless the caller itself is cancelled). */
    private suspend fun rollBack(ids: List<String>) {
        try {
            client.removeGeofences(ids).await()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Logger.w(TAG, "rolling back geofences $ids failed", e)
        }
    }

    private suspend fun call(operation: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            // GMS cancelled the Task itself; the caller is still active and expects a TrackingException.
            throw TrackingException(ErrorCode.UNAVAILABLE, "$operation was cancelled by Google Play services", e)
        } catch (e: Exception) {
            val mapped = GmsErrors.map(e, operation)
            Logger.w(TAG, "${mapped.code}: ${mapped.message}", e)
            throw mapped
        }
    }

    companion object {
        private const val TAG = "LT.GmsGeofence"

        /** Initial trigger for `initialTriggerEntry`: report ENTER, and DWELL after the delay, if already inside. */
        private const val INITIAL_TRIGGER_INSIDE =
            GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL

        /** Explicit broadcast to [GmsGeofenceReceiver]; mutable because GMS fills in the transition. */
        fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, Constants.RC_GMS_GEOFENCE, intent(context), Constants.piMutable())

        /** The PendingIntent of [pendingIntent] if it exists, without creating it. */
        fun existingPendingIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
            context,
            Constants.RC_GMS_GEOFENCE,
            intent(context),
            Constants.piMutable() or PendingIntent.FLAG_NO_CREATE,
        )

        private fun intent(context: Context): Intent =
            Intent(context, GmsGeofenceReceiver::class.java).setAction(Constants.ACTION_GEOFENCE)

        /**
         * Groups [regions] by `initialTriggerEntry`. `true` sets the initial trigger `ENTER | DWELL` (a device that is
         * already inside reports ENTER, and DWELL after the loitering delay, for the transitions it watches); `false`
         * sets no initial trigger.
         *
         * @throws IllegalArgumentException if GMS rejects a region (id longer than 100, bad coordinates or radius).
         */
        fun buildGeofencingRequests(regions: List<OsGeofence>): List<GeofencingRequest> = regions
            .mapNotNull { region -> toGeofence(region)?.let { region.initialTriggerEntry to it } }
            .groupBy({ it.first }, { it.second })
            .map { (initialTriggerEntry, geofences) ->
                GeofencingRequest.Builder()
                    .setInitialTrigger(if (initialTriggerEntry) INITIAL_TRIGGER_INSIDE else 0)
                    .addGeofences(geofences)
                    .build()
            }

        /** The GMS geofence for [region], or null if it watches no transition. */
        fun toGeofence(region: OsGeofence): Geofence? {
            val transitions = transitionTypes(region)
            if (transitions == 0) {
                Logger.w(TAG, "geofence '${region.id}' watches no transition; not registered")
                return null
            }
            return Geofence.Builder()
                .setRequestId(region.id)
                .setCircularRegion(region.latitude, region.longitude, region.radius)
                .setTransitionTypes(transitions)
                .setLoiteringDelay(region.loiteringDelayMs.coerceAtLeast(0))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }

        fun transitionTypes(region: OsGeofence): Int {
            var types = 0
            if (region.onEntry) types = types or Geofence.GEOFENCE_TRANSITION_ENTER
            if (region.onExit) types = types or Geofence.GEOFENCE_TRANSITION_EXIT
            if (region.onDwell) types = types or Geofence.GEOFENCE_TRANSITION_DWELL
            return types
        }
    }
}
