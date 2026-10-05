package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.LocationProviderSetting

// Mirrors the TS `Config` 1:1 (same field names, same defaults). JSON-valued fields are stored as JSON text.
// Units: distances in meters, intervals in ms unless the field name/KDoc says otherwise.

enum class HttpMethod {
    POST,
    PUT,
    PATCH,
    ;

    val wire: String get() = name

    companion object {
        fun fromWire(value: String?): HttpMethod? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

enum class NotificationPriority(val wire: String) {
    MIN("min"),
    LOW("low"),
    DEFAULT("default"),
    HIGH("high"),
    MAX("max"),
    ;

    companion object {
        fun fromWire(value: String?): NotificationPriority? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

enum class RefreshPayloadEncoding(val wire: String) {
    JSON("json"),
    FORM("form"),
    ;

    companion object {
        fun fromWire(value: String?): RefreshPayloadEncoding? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

data class LocationFilterConfig(
    val useKalman: Boolean = false,
    /** Reject fixes with accuracy worse than this (m). */
    val trackingAccuracyThreshold: Double = 100.0,
    /** Reject fixes implying speed above this (m/s); 0 = off. */
    val maxImpliedSpeed: Double = 80.0,
    /** Odometer ignores fixes worse than this (m). */
    val odometerAccuracyThreshold: Double = 20.0,
    val allowIdenticalLocations: Boolean = false,
    /** Drop mock fixes entirely (otherwise they are kept with mock:true). */
    val rejectMockLocations: Boolean = false,
)

data class GeolocationConfig(
    val desiredAccuracy: DesiredAccuracy = DesiredAccuracy.HIGH,
    /** m */
    val distanceFilter: Double = 10.0,
    /** ms */
    val locationUpdateInterval: Long = 1000,
    /** ms */
    val fastestLocationUpdateInterval: Long = 500,
    val disableElasticity: Boolean = false,
    val elasticityMultiplier: Double = 1.0,
    /** m */
    val stationaryRadius: Double = 25.0,
    /** minutes */
    val stopTimeout: Int = 5,
    /** minutes, 0 = off */
    val stopAfterElapsedMinutes: Int = 0,
    val stopOnStationary: Boolean = false,
    /** Default getCurrentPosition timeout, ms. */
    val locationTimeout: Long = 30_000,
    val filter: LocationFilterConfig = LocationFilterConfig(),
)

data class ActivityConfig(
    val disableMotionActivityUpdates: Boolean = false,
    /** ms */
    val activityRecognitionInterval: Long = 10_000,
    /** 0-100 */
    val minimumActivityRecognitionConfidence: Int = 75,
    /** ms */
    val motionTriggerDelay: Long = 0,
    val disableStopDetection: Boolean = false,
)

data class HeartbeatConfig(
    val enabled: Boolean = true,
    /** seconds, min 60 */
    val minInterval: Int = 180,
    /** seconds, >= minInterval */
    val maxInterval: Int = 300,
)

data class AuthorizationConfig(
    val strategy: String = "JWT",
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val refreshUrl: String? = null,
    /** JSON object text; string values may contain `{refreshToken}`. */
    val refreshPayload: String = "{}",
    val refreshHeaders: Map<String, String> = emptyMap(),
    val refreshPayloadEncoding: RefreshPayloadEncoding = RefreshPayloadEncoding.JSON,
    /** Access-token expiry, epoch ms; -1 = unknown. */
    val expires: Long = -1,
)

data class HttpConfig(
    /** No url => nothing is uploaded (records stay queued). */
    val url: String? = null,
    val method: HttpMethod = HttpMethod.POST,
    val headers: Map<String, String> = emptyMap(),
    /** JSON object text, merged into the ROOT of every request body. */
    val params: String = "{}",
    val autoSync: Boolean = true,
    /** Upload when queue >= threshold; 0 = every record. */
    val autoSyncThreshold: Int = 0,
    /**
     * Seconds, 0 = off (round 2). Normal records are uploaded once the oldest pending normal record is at least this
     * old (live location at most this stale); priority records are unaffected. See docs/e2e/architecture.md §4.
     */
    val syncInterval: Int = 0,
    val batchSync: Boolean = false,
    val maxBatchSize: Int = 100,
    val disableAutoSyncOnCellular: Boolean = false,
    /** "." = no wrapping. */
    val rootProperty: String = "location",
    /** JSON text with `<%= name %>` placeholders. */
    val locationTemplate: String? = null,
    /** Used for event 'geofence'; falls back to [locationTemplate]. */
    val geofenceTemplate: String? = null,
    /** ms */
    val timeout: Long = 60_000,
    val authorization: AuthorizationConfig? = null,
)

data class PersistenceConfig(
    val maxDaysToPersist: Int = 7,
    /** -1 = unlimited */
    val maxRecordsToPersist: Int = -1,
    /** JSON object text, merged into `extras` of every record at creation time. */
    val extras: String = "{}",
)

data class AppConfig(
    val stopOnTerminate: Boolean = true,
    val startOnBoot: Boolean = false,
)

data class NotificationActionButton(val id: String, val label: String)

data class NotificationConfig(
    /** null = app label. */
    val title: String? = null,
    val text: String = DEFAULT_TEXT,
    /** 'drawable/name' | 'mipmap/name' */
    val smallIcon: String = DEFAULT_SMALL_ICON,
    val largeIcon: String? = null,
    /** '#RRGGBB' */
    val color: String? = null,
    val priority: NotificationPriority = NotificationPriority.DEFAULT,
    val channelId: String = DEFAULT_CHANNEL_ID,
    val channelName: String = DEFAULT_CHANNEL_NAME,
    /** Max 3; tap => 'notificationaction' event. */
    val actions: List<NotificationActionButton> = emptyList(),
    val resume: ResumeNotificationConfig = ResumeNotificationConfig(),
) {
    companion object {
        const val DEFAULT_TEXT = "Location tracking is active"
        const val DEFAULT_SMALL_ICON = "drawable/lt_ic_notification"
        const val DEFAULT_CHANNEL_ID = "location_tracking"
        const val DEFAULT_CHANNEL_NAME = "Location tracking"
    }
}

/**
 * The notification posted when Android refuses to restore tracking from the background (after a reboot, an app update
 * or a process restart); tapping it resumes the session. Uses the tracking notification's small icon and color.
 */
data class ResumeNotificationConfig(
    val enabled: Boolean = false,
    /** null = app label. */
    val title: String? = null,
    val text: String = DEFAULT_TEXT,
    val channelName: String = DEFAULT_CHANNEL_NAME,
) {
    companion object {
        const val DEFAULT_TEXT = "Location tracking is paused. Tap to resume."
        const val DEFAULT_CHANNEL_NAME = "Paused location tracking"
    }
}

data class GeofenceConfig(
    val initialTriggerEntry: Boolean = true,
)

data class LoggerConfig(
    val logLevel: LogLevel = LogLevel.INFO,
    val logMaxDays: Int = 3,
)

/** null fields fall back to the plugin string resources `lt_bg_rationale_*`. */
data class BackgroundPermissionRationale(
    val title: String? = null,
    val message: String? = null,
    val positiveAction: String? = null,
    val negativeAction: String? = null,
)

/** The full plugin configuration with every default applied. */
data class Config(
    val geolocation: GeolocationConfig = GeolocationConfig(),
    val activity: ActivityConfig = ActivityConfig(),
    val heartbeat: HeartbeatConfig = HeartbeatConfig(),
    val http: HttpConfig = HttpConfig(),
    val persistence: PersistenceConfig = PersistenceConfig(),
    val app: AppConfig = AppConfig(),
    val notification: NotificationConfig = NotificationConfig(),
    val geofence: GeofenceConfig = GeofenceConfig(),
    val logger: LoggerConfig = LoggerConfig(),
    val backgroundPermissionRationale: BackgroundPermissionRationale = BackgroundPermissionRationale(),
    /** Android only. */
    val locationProvider: LocationProviderSetting = LocationProviderSetting.AUTO,
)
