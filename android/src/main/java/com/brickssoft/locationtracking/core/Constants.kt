package com.brickssoft.locationtracking.core

import android.app.PendingIntent
import android.os.Build

/** Shared names, ids and PendingIntent request codes. */
object Constants {
    /** Tag prefix for [Logger]. */
    const val TAG = "LT"

    const val PREFS_NAME = "location_tracking_prefs"
    const val DATABASE_NAME = "location_tracking.db"
    const val LOG_DIR = "location-tracking-logs"

    /** Authority suffix of `LogFileProvider`: `${applicationId}.locationtracking.logs`. */
    const val LOG_FILE_PROVIDER_SUFFIX = ".locationtracking.logs"

    /** Maximum number of registered geofences. */
    const val MAX_GEOFENCES = 100

    /**
     * Id of the engine's own OS geofence while STATIONARY (GPS off): a circle around the stationary anchor whose EXIT
     * wakes the engine. It is never stored, never counted against [MAX_GEOFENCES], never recorded and never emitted;
     * `DefaultGeofenceManager` routes its transitions to `StationaryRegionSink` (architecture round 2, §3).
     */
    const val STATIONARY_REGION_ID = "__lt_stationary__"

    /** Maximum number of notification action buttons. */
    const val MAX_NOTIFICATION_ACTIONS = 3

    const val NOTIFICATION_ID = 7301

    // PendingIntent request codes
    const val RC_HEARTBEAT = 7310
    const val RC_GMS_ACTIVITY = 7320
    const val RC_GMS_GEOFENCE = 7321
    const val RC_HMS_ACTIVITY = 7330
    const val RC_HMS_GEOFENCE = 7331
    const val RC_NOTIFICATION_CONTENT = 7349

    /** Notification action `i` uses `RC_NOTIFICATION_ACTION_BASE + i`. */
    const val RC_NOTIFICATION_ACTION_BASE = 7350

    /** Android proximity alerts; told apart by the data URI [geofenceUri]. */
    const val RC_ANDROID_PROXIMITY = 7400

    /** Scheme of the data URI that identifies an Android proximity-alert PendingIntent. */
    const val GEOFENCE_URI_SCHEME = "lt-geofence"

    // Intent actions / extras
    const val ACTION_HEARTBEAT = "com.brickssoft.locationtracking.HEARTBEAT"
    const val ACTION_NOTIFICATION_ACTION = "com.brickssoft.locationtracking.NOTIFICATION_ACTION"
    const val ACTION_GEOFENCE = "com.brickssoft.locationtracking.GEOFENCE"
    const val ACTION_ACTIVITY = "com.brickssoft.locationtracking.ACTIVITY"
    const val EXTRA_ACTION_ID = "com.brickssoft.locationtracking.EXTRA_ACTION_ID"

    /** `lt-geofence://<id>` */
    fun geofenceUri(id: String): String = "$GEOFENCE_URI_SCHEME://${android.net.Uri.encode(id)}"

    /**
     * Flags for PendingIntents the OS must fill in (GMS/HMS activity and geofence, proximity alerts):
     * `FLAG_UPDATE_CURRENT | FLAG_MUTABLE` on API 31+.
     */
    fun piMutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    /** Flags for alarm, notification and action PendingIntents: `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`. */
    fun piImmutable(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
