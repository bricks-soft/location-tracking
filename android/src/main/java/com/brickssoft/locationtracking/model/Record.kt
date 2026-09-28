package com.brickssoft.locationtracking.model

/**
 * Record type. [isPriority] records are uploaded immediately whenever `http.url` is set.
 */
enum class RecordEvent(val wire: String, val isPriority: Boolean) {
    LOCATION("location", false),
    MOTIONCHANGE("motionchange", false),
    CURRENT_POSITION("current_position", false),
    WATCH_POSITION("watch_position", false),
    HEARTBEAT("heartbeat", true),
    GEOFENCE("geofence", false),
    TRACKING_START("tracking_start", true),
    TRACKING_STOP("tracking_stop", true),
    PROVIDERCHANGE("providerchange", true),
    ;

    companion object {
        fun fromWire(value: String?): RecordEvent? = entries.firstOrNull { it.wire == value }
    }
}

enum class ActivityType(val wire: String) {
    STILL("still"),
    ON_FOOT("on_foot"),
    WALKING("walking"),
    RUNNING("running"),
    ON_BICYCLE("on_bicycle"),
    IN_VEHICLE("in_vehicle"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): ActivityType? = entries.firstOrNull { it.wire == value }
    }
}

/** A detected activity with confidence 0..100. */
data class ActivitySample(val type: ActivityType, val confidence: Int) {
    companion object {
        val UNKNOWN = ActivitySample(ActivityType.UNKNOWN, 0)
    }
}

/** Battery state; [level] is 0..1, or -1 if unknown. */
data class BatterySnapshot(val level: Float, val isCharging: Boolean) {
    companion object {
        val UNKNOWN = BatterySnapshot(-1f, false)
    }
}

/** A native location backend; [wire] is the JS `LocationBackend` value. */
enum class ProviderKind(val wire: String) {
    GMS("gms"),
    HMS("hms"),
    ANDROID("android"),
    ;

    companion object {
        fun fromWire(value: String?): ProviderKind? = entries.firstOrNull { it.wire == value }
    }
}

enum class GeofenceAction {
    ENTER,
    EXIT,
    DWELL,
    ;

    companion object {
        fun fromWire(value: String?): GeofenceAction? = entries.firstOrNull { it.name == value }
    }
}

/** The `geofence` key of a geofence record; [extras] is JSON object text. */
data class GeofenceHit(val identifier: String, val action: GeofenceAction, val extras: String?)

/**
 * A persisted record (location or audit). Serialized by [RecordJson] to the wire format.
 *
 * @property location the fix, or null if no location was ever known (audit records only).
 * @property recordedAt record creation time, epoch ms.
 * @property extras JSON object text, or null.
 * @property geofence event `geofence` only.
 * @property provider event `providerchange` only.
 * @property reason `tracking_start` / `tracking_stop` only.
 * @property heartbeat `heartbeat` only, and optional (round 2): the scheduling metadata of the heartbeat.
 */
data class Record(
    val uuid: String,
    val event: RecordEvent,
    val location: TrackedLocation?,
    val recordedAt: Long,
    val elapsedRealtimeMs: Long,
    val bootCount: Int,
    val isMoving: Boolean,
    val odometer: Double,
    val activity: ActivitySample,
    val battery: BatterySnapshot,
    val backend: ProviderKind?,
    val extras: String? = null,
    val geofence: GeofenceHit? = null,
    val provider: ProviderState? = null,
    val reason: String? = null,
    val heartbeat: HeartbeatMeta? = null,
)
