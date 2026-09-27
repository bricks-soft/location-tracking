package com.brickssoft.locationtracking.provider.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.LocationManager
import android.net.Uri
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.OsGeofence

/** Seam over the platform proximity-alert API (`LocationManager.addProximityAlert` / `removeProximityAlert`). */
interface ProximityAlerts {
    /** Registers a circular alert that never expires. May throw [SecurityException] without fine location. */
    fun add(latitude: Double, longitude: Double, radius: Float, intent: PendingIntent)

    fun remove(intent: PendingIntent)
}

/** [ProximityAlerts] backed by the platform [LocationManager]. */
internal class LocationManagerProximityAlerts(context: Context) : ProximityAlerts {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @SuppressLint("MissingPermission")
    override fun add(latitude: Double, longitude: Double, radius: Float, intent: PendingIntent) {
        val manager = lm ?: throw TrackingException(ErrorCode.UNAVAILABLE, "LocationManager is unavailable")
        manager.addProximityAlert(latitude, longitude, radius, NO_EXPIRATION, intent)
    }

    override fun remove(intent: PendingIntent) {
        lm?.removeProximityAlert(intent)
    }

    private companion object {
        const val NO_EXPIRATION = -1L
    }
}

/**
 * [GeofenceBackend] over `LocationManager` proximity alerts.
 *
 * - One mutable broadcast PendingIntent per region: explicit [AndroidGeofenceReceiver] component, action
 *   [Constants.ACTION_GEOFENCE], data URI [Constants.geofenceUri] and request code [Constants.RC_ANDROID_PROXIMITY].
 *   The region's `onEntry`/`onExit` flags travel as extras; the receiver drops unwanted transitions.
 * - No dwell ([supportsDwell] is false). The platform always reports ENTER when the device is already inside, so
 *   `initialTriggerEntry` is left to the GeofenceManager.
 * - Registered ids are kept in memory and mirrored to SharedPreferences, so [removeAll] also clears alerts that were
 *   registered before a process restart. [remove] re-creates each PendingIntent from its id.
 * - [add] validates every region before touching the OS ([ErrorCode.INVALID_ARGUMENT]). If the platform then fails,
 *   the regions that call introduced are removed again; a region that was already registered keeps its new circle if
 *   it was re-added, or is dropped if re-adding it failed (its previous circle is already gone).
 * - Calls run on the caller's thread: a few binder calls per region, no disk I/O after the first preferences load
 *   (started in the background at construction).
 */
class AndroidGeofenceBackend(
    context: Context,
    private val alerts: ProximityAlerts = LocationManagerProximityAlerts(context),
) : GeofenceBackend {
    private val appContext: Context = context.applicationContext ?: context
    override val kind: ProviderKind = ProviderKind.ANDROID
    override val supportsDwell: Boolean = false

    private val lock = Any()
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var ids: MutableSet<String>? = null

    /** Ids currently registered with the OS (as far as this backend knows). */
    internal val registeredIds: Set<String> get() = synchronized(lock) { registered().toSet() }

    override suspend fun add(regions: List<OsGeofence>) {
        for (region in regions) {
            invalidReason(region)?.let {
                throw TrackingException(ErrorCode.INVALID_ARGUMENT, "Invalid geofence '${region.id}': $it")
            }
        }
        synchronized(lock) {
            val before = registered().toSet()
            val added = ArrayList<String>(regions.size)
            for (region in regions) {
                val intent = pendingIntentFor(region)
                try {
                    // The platform keys alerts by (PendingIntent, circle): drop the previous circle of this id first.
                    alerts.remove(intent)
                    alerts.add(region.latitude, region.longitude, region.radius, intent)
                } catch (e: Exception) {
                    (added.filter { it !in before } + region.id).forEach(::removeLocked)
                    persist()
                    throw toTrackingException(e)
                }
                added += region.id
                registered() += region.id
            }
            persist()
            if (regions.isNotEmpty()) Logger.d(TAG, "added ${regions.size} proximity alert(s)")
        }
    }

    override suspend fun remove(ids: List<String>) {
        synchronized(lock) {
            ids.forEach(::removeLocked)
            persist()
        }
    }

    override suspend fun removeAll() {
        synchronized(lock) {
            registered().toList().forEach(::removeLocked)
            persist()
        }
    }

    private fun removeLocked(id: String) {
        existingPendingIntent(id)?.let { intent ->
            try {
                alerts.remove(intent)
            } catch (e: Exception) {
                Logger.w(TAG, "removeProximityAlert failed for $id", e)
            }
            intent.cancel()
        }
        registered() -= id
    }

    private fun registered(): MutableSet<String> =
        ids ?: HashSet(prefs.getStringSet(KEY_IDS, null).orEmpty()).also { ids = it }

    private fun persist() {
        prefs.edit().putStringSet(KEY_IDS, HashSet(registered())).apply()
    }

    private fun pendingIntentFor(region: OsGeofence): PendingIntent {
        val intent = baseIntent(region.id)
            .putExtra(EXTRA_NOTIFY_ENTRY, region.onEntry)
            .putExtra(EXTRA_NOTIFY_EXIT, region.onExit)
        return PendingIntent.getBroadcast(appContext, Constants.RC_ANDROID_PROXIMITY, intent, Constants.piMutable())
    }

    /** The PendingIntent registered for [id], if any; same identity (intent, request code, mutability) as created. */
    private fun existingPendingIntent(id: String): PendingIntent? {
        val flags = PendingIntent.FLAG_NO_CREATE or (Constants.piMutable() and PendingIntent.FLAG_UPDATE_CURRENT.inv())
        return PendingIntent.getBroadcast(appContext, Constants.RC_ANDROID_PROXIMITY, baseIntent(id), flags)
    }

    private fun baseIntent(id: String): Intent =
        Intent(appContext, AndroidGeofenceReceiver::class.java)
            .setAction(Constants.ACTION_GEOFENCE)
            .setData(Uri.parse(Constants.geofenceUri(id)))

    private fun invalidReason(region: OsGeofence): String? = when {
        region.id.isEmpty() -> "empty identifier"
        region.latitude !in -90.0..90.0 -> "latitude ${region.latitude} out of range"
        region.longitude !in -180.0..180.0 -> "longitude ${region.longitude} out of range"
        !region.radius.isFinite() || region.radius <= 0f -> "radius ${region.radius} must be > 0"
        else -> null
    }

    private fun toTrackingException(e: Exception): TrackingException = when (e) {
        is TrackingException -> e
        is SecurityException ->
            TrackingException(ErrorCode.PERMISSION_DENIED, "Proximity alerts need fine location permission", e)
        is IllegalArgumentException ->
            TrackingException(ErrorCode.INVALID_ARGUMENT, "Invalid geofence: ${e.message}", e)
        else -> TrackingException(ErrorCode.UNAVAILABLE, "Cannot add proximity alert: ${e.message}", e)
    }

    internal companion object {
        private const val TAG = "LT.AndroidGeofence"
        private const val PREFS_NAME = "location_tracking_android_geofences"
        private const val KEY_IDS = "registered_ids"

        /** Boolean extras of the proximity PendingIntent: whether ENTER / EXIT should be delivered (default true). */
        const val EXTRA_NOTIFY_ENTRY = "com.brickssoft.locationtracking.provider.android.NOTIFY_ENTRY"
        const val EXTRA_NOTIFY_EXIT = "com.brickssoft.locationtracking.provider.android.NOTIFY_EXIT"
    }
}
