package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger

/**
 * Clamps config values to their allowed ranges and logs a warning for each value it changes. Never throws.
 *
 * - `heartbeat.minInterval` >= 60 s and `heartbeat.maxInterval` >= `minInterval`;
 * - `activity.minimumActivityRecognitionConfidence` in 0..100;
 * - `geolocation.stationaryRadius` >= 1; distances, intervals, delays and filter thresholds >= 0;
 * - `geolocation.locationTimeout` and `http.timeout` > 0 (otherwise their defaults);
 * - `http.maxBatchSize` >= 1, `http.autoSyncThreshold` >= 0;
 * - `persistence.maxDaysToPersist` >= 1, `persistence.maxRecordsToPersist` >= -1 (-1 = unlimited);
 * - `logger.logMaxDays` >= 1;
 * - at most [Constants.MAX_NOTIFICATION_ACTIONS] notification actions (the first ones are kept);
 * - non-finite numbers become their defaults.
 */
object ConfigValidator {
    private const val TAG = "LT.ConfigValidator"

    /** Smallest allowed `heartbeat.minInterval`, seconds. */
    const val MIN_HEARTBEAT_INTERVAL_S = 60

    const val MIN_STATIONARY_RADIUS_M = 1.0

    /** Returns [config] with every out-of-range value clamped (equal to [config] when all values are valid). */
    fun validate(config: Config): Config {
        val validated = config.copy(
            geolocation = geolocation(config.geolocation),
            activity = activity(config.activity),
            heartbeat = heartbeat(config.heartbeat),
            http = http(config.http),
            persistence = persistence(config.persistence),
            notification = notification(config.notification),
            logger = config.logger.copy(logMaxDays = atLeast("logger.logMaxDays", config.logger.logMaxDays, 1)),
        )
        return if (validated == config) config else validated
    }

    private fun geolocation(g: GeolocationConfig): GeolocationConfig {
        val d = GeolocationConfig()
        return g.copy(
            distanceFilter = atLeast("geolocation.distanceFilter", g.distanceFilter, 0.0, d.distanceFilter),
            locationUpdateInterval = atLeast("geolocation.locationUpdateInterval", g.locationUpdateInterval, 0L),
            fastestLocationUpdateInterval = atLeast(
                "geolocation.fastestLocationUpdateInterval",
                g.fastestLocationUpdateInterval,
                0L,
            ),
            elasticityMultiplier = atLeast(
                "geolocation.elasticityMultiplier",
                g.elasticityMultiplier,
                0.0,
                d.elasticityMultiplier,
            ),
            stationaryRadius = atLeast(
                "geolocation.stationaryRadius",
                g.stationaryRadius,
                MIN_STATIONARY_RADIUS_M,
                d.stationaryRadius,
            ),
            stopTimeout = atLeast("geolocation.stopTimeout", g.stopTimeout, 0),
            stopAfterElapsedMinutes = atLeast("geolocation.stopAfterElapsedMinutes", g.stopAfterElapsedMinutes, 0),
            locationTimeout = positive("geolocation.locationTimeout", g.locationTimeout, d.locationTimeout),
            filter = filter(g.filter),
        )
    }

    private fun filter(f: LocationFilterConfig): LocationFilterConfig {
        val d = LocationFilterConfig()
        return f.copy(
            trackingAccuracyThreshold = atLeast(
                "geolocation.filter.trackingAccuracyThreshold",
                f.trackingAccuracyThreshold,
                0.0,
                d.trackingAccuracyThreshold,
            ),
            maxImpliedSpeed = atLeast("geolocation.filter.maxImpliedSpeed", f.maxImpliedSpeed, 0.0, d.maxImpliedSpeed),
            odometerAccuracyThreshold = atLeast(
                "geolocation.filter.odometerAccuracyThreshold",
                f.odometerAccuracyThreshold,
                0.0,
                d.odometerAccuracyThreshold,
            ),
        )
    }

    private fun activity(a: ActivityConfig): ActivityConfig = a.copy(
        activityRecognitionInterval = atLeast(
            "activity.activityRecognitionInterval",
            a.activityRecognitionInterval,
            0L,
        ),
        minimumActivityRecognitionConfidence = inRange(
            "activity.minimumActivityRecognitionConfidence",
            a.minimumActivityRecognitionConfidence,
            0,
            100,
        ),
        motionTriggerDelay = atLeast("activity.motionTriggerDelay", a.motionTriggerDelay, 0L),
    )

    private fun heartbeat(h: HeartbeatConfig): HeartbeatConfig {
        val min = atLeast("heartbeat.minInterval", h.minInterval, MIN_HEARTBEAT_INTERVAL_S)
        val max = atLeast("heartbeat.maxInterval", h.maxInterval, min)
        return h.copy(minInterval = min, maxInterval = max)
    }

    private fun http(h: HttpConfig): HttpConfig = h.copy(
        autoSyncThreshold = atLeast("http.autoSyncThreshold", h.autoSyncThreshold, 0),
        maxBatchSize = atLeast("http.maxBatchSize", h.maxBatchSize, 1),
        timeout = positive("http.timeout", h.timeout, HttpConfig().timeout),
    )

    private fun persistence(p: PersistenceConfig): PersistenceConfig = p.copy(
        maxDaysToPersist = atLeast("persistence.maxDaysToPersist", p.maxDaysToPersist, 1),
        maxRecordsToPersist = atLeast("persistence.maxRecordsToPersist", p.maxRecordsToPersist, -1),
    )

    private fun notification(n: NotificationConfig): NotificationConfig {
        val max = Constants.MAX_NOTIFICATION_ACTIONS
        if (n.actions.size <= max) return n
        Logger.w(TAG, "notification.actions has ${n.actions.size} entries; keeping the first $max")
        return n.copy(actions = n.actions.take(max))
    }

    // ---- clamps (each logs when it changes a value)

    private fun atLeast(path: String, value: Int, min: Int): Int {
        if (value >= min) return value
        clamped(path, value, min)
        return min
    }

    private fun atLeast(path: String, value: Long, min: Long): Long {
        if (value >= min) return value
        clamped(path, value, min)
        return min
    }

    /** Non-finite [value]s become [fallback]. */
    private fun atLeast(path: String, value: Double, min: Double, fallback: Double): Double {
        if (!value.isFinite()) {
            clamped(path, value, fallback)
            return fallback
        }
        if (value >= min) return value
        clamped(path, value, min)
        return min
    }

    private fun inRange(path: String, value: Int, min: Int, max: Int): Int {
        val result = value.coerceIn(min, max)
        if (result != value) clamped(path, value, result)
        return result
    }

    /** Values <= 0 become [fallback]. */
    private fun positive(path: String, value: Long, fallback: Long): Long {
        if (value > 0) return value
        clamped(path, value, fallback)
        return fallback
    }

    private fun clamped(path: String, from: Any, to: Any) {
        Logger.w(TAG, "config $path=$from is out of range; using $to")
    }
}
