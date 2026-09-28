package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.JsonUtil
import com.brickssoft.locationtracking.model.LocationProviderSetting
import org.json.JSONArray
import org.json.JSONObject

/**
 * Config <-> JS JSON, and `State` -> JS.
 *
 * [parse] deep-merges a partial TS `Config` onto a base config:
 * - a missing key keeps the base value;
 * - JSON `null` resets that key (or whole group) to its default;
 * - arrays (`notification.actions`) and map-valued objects (`headers`, `params`, `extras`, `refreshPayload`,
 *   `refreshHeaders`) replace the base value as a whole;
 * - unknown keys are ignored with a warning;
 * - a value of the wrong type, or an enum string that is not a wire value, throws
 *   [TrackingException] `INVALID_ARGUMENT` (nothing is applied).
 *
 * [parse] does not clamp; the store runs [ConfigValidator] on the result.
 */
object ConfigJson {
    private const val TAG = "LT.ConfigJson"

    /** Applies the partial TS `Config` [json] on top of [base]. See the class KDoc for the merge rules. */
    fun parse(json: JSONObject, base: Config = Config()): Config = parse(json, base, lenient = false)

    /**
     * Reads a config persisted with [toJson] onto the defaults. Unlike [parse], an invalid value (e.g. an enum
     * value unknown to this version) is logged and replaced by its default, so one bad field cannot discard the
     * whole stored config (URL, tokens, ...).
     */
    internal fun parseStored(json: JSONObject): Config = parse(json, Config(), lenient = true)

    private fun parse(json: JSONObject, base: Config, lenient: Boolean): Config {
        val r = JsonReader(json, "", lenient)
        val d = Config()
        val result = Config(
            geolocation = r.group("geolocation", base.geolocation, d.geolocation, ::parseGeolocation),
            activity = r.group("activity", base.activity, d.activity, ::parseActivity),
            heartbeat = r.group("heartbeat", base.heartbeat, d.heartbeat, ::parseHeartbeat),
            http = r.group("http", base.http, d.http, ::parseHttp),
            persistence = r.group("persistence", base.persistence, d.persistence, ::parsePersistence),
            app = r.group("app", base.app, d.app, ::parseApp),
            notification = r.group("notification", base.notification, d.notification, ::parseNotification),
            geofence = r.group("geofence", base.geofence, d.geofence, ::parseGeofence),
            logger = r.group("logger", base.logger, d.logger, ::parseLogger),
            backgroundPermissionRationale = r.group(
                "backgroundPermissionRationale",
                base.backgroundPermissionRationale,
                d.backgroundPermissionRationale,
                ::parseRationale,
            ),
            locationProvider = r.enumValue(
                "locationProvider",
                base.locationProvider,
                d.locationProvider,
                LocationProviderSetting.entries.map { it.wire },
                LocationProviderSetting::fromWire,
            ),
        )
        r.warnUnknownKeys()
        return result
    }

    /** The full TS `Config`: every key present, defaults filled, unset optional values as JSON null. */
    fun toJson(c: Config): JSONObject = JSONObject()
        .put("geolocation", geolocationToJson(c.geolocation))
        .put("activity", activityToJson(c.activity))
        .put("heartbeat", heartbeatToJson(c.heartbeat))
        .put("http", httpToJson(c.http))
        .put("persistence", persistenceToJson(c.persistence))
        .put("app", JSONObject().put("stopOnTerminate", c.app.stopOnTerminate).put("startOnBoot", c.app.startOnBoot))
        .put("notification", notificationToJson(c.notification))
        .put("geofence", JSONObject().put("initialTriggerEntry", c.geofence.initialTriggerEntry))
        .put("logger", JSONObject().put("logLevel", c.logger.logLevel.wire).put("logMaxDays", c.logger.logMaxDays))
        .put("backgroundPermissionRationale", rationaleToJson(c.backgroundPermissionRationale))
        .put("locationProvider", c.locationProvider.wire)

    /** JS `State`: runtime fields, the active backend, and the fully populated config. */
    fun stateToJson(s: State): JSONObject = JSONObject()
        .put("enabled", s.runtime.enabled)
        .put("trackingMode", s.runtime.trackingMode.wire)
        .put("isMoving", s.runtime.isMoving)
        .put("odometer", finiteOr(s.runtime.odometer, 0.0))
        .put("backend", s.backend.wire)
        .put("lastRecordAt", JsonUtil.orNull(Iso8601.formatOrNull(s.runtime.lastRecordAt)))
        .put("config", toJson(s.config))

    // ---- parse

    private fun parseGeolocation(r: JsonReader, c: GeolocationConfig): GeolocationConfig {
        val d = GeolocationConfig()
        return GeolocationConfig(
            desiredAccuracy = r.enumValue(
                "desiredAccuracy",
                c.desiredAccuracy,
                d.desiredAccuracy,
                DesiredAccuracy.entries.map { it.wire },
                DesiredAccuracy::fromWire,
            ),
            distanceFilter = r.double("distanceFilter", c.distanceFilter, d.distanceFilter),
            locationUpdateInterval = r.long(
                "locationUpdateInterval",
                c.locationUpdateInterval,
                d.locationUpdateInterval,
            ),
            fastestLocationUpdateInterval = r.long(
                "fastestLocationUpdateInterval",
                c.fastestLocationUpdateInterval,
                d.fastestLocationUpdateInterval,
            ),
            disableElasticity = r.boolean("disableElasticity", c.disableElasticity, d.disableElasticity),
            elasticityMultiplier = r.double("elasticityMultiplier", c.elasticityMultiplier, d.elasticityMultiplier),
            stationaryRadius = r.double("stationaryRadius", c.stationaryRadius, d.stationaryRadius),
            stopTimeout = r.int("stopTimeout", c.stopTimeout, d.stopTimeout),
            stopAfterElapsedMinutes = r.int(
                "stopAfterElapsedMinutes",
                c.stopAfterElapsedMinutes,
                d.stopAfterElapsedMinutes,
            ),
            stopOnStationary = r.boolean("stopOnStationary", c.stopOnStationary, d.stopOnStationary),
            locationTimeout = r.long("locationTimeout", c.locationTimeout, d.locationTimeout),
            filter = r.group("filter", c.filter, d.filter, ::parseFilter),
        )
    }

    private fun parseFilter(r: JsonReader, c: LocationFilterConfig): LocationFilterConfig {
        val d = LocationFilterConfig()
        return LocationFilterConfig(
            useKalman = r.boolean("useKalman", c.useKalman, d.useKalman),
            trackingAccuracyThreshold = r.double(
                "trackingAccuracyThreshold",
                c.trackingAccuracyThreshold,
                d.trackingAccuracyThreshold,
            ),
            maxImpliedSpeed = r.double("maxImpliedSpeed", c.maxImpliedSpeed, d.maxImpliedSpeed),
            odometerAccuracyThreshold = r.double(
                "odometerAccuracyThreshold",
                c.odometerAccuracyThreshold,
                d.odometerAccuracyThreshold,
            ),
            allowIdenticalLocations = r.boolean(
                "allowIdenticalLocations",
                c.allowIdenticalLocations,
                d.allowIdenticalLocations,
            ),
            rejectMockLocations = r.boolean("rejectMockLocations", c.rejectMockLocations, d.rejectMockLocations),
        )
    }

    private fun parseActivity(r: JsonReader, c: ActivityConfig): ActivityConfig {
        val d = ActivityConfig()
        return ActivityConfig(
            disableMotionActivityUpdates = r.boolean(
                "disableMotionActivityUpdates",
                c.disableMotionActivityUpdates,
                d.disableMotionActivityUpdates,
            ),
            activityRecognitionInterval = r.long(
                "activityRecognitionInterval",
                c.activityRecognitionInterval,
                d.activityRecognitionInterval,
            ),
            minimumActivityRecognitionConfidence = r.int(
                "minimumActivityRecognitionConfidence",
                c.minimumActivityRecognitionConfidence,
                d.minimumActivityRecognitionConfidence,
            ),
            motionTriggerDelay = r.long("motionTriggerDelay", c.motionTriggerDelay, d.motionTriggerDelay),
            disableStopDetection = r.boolean("disableStopDetection", c.disableStopDetection, d.disableStopDetection),
        )
    }

    private fun parseHeartbeat(r: JsonReader, c: HeartbeatConfig): HeartbeatConfig {
        val d = HeartbeatConfig()
        return HeartbeatConfig(
            enabled = r.boolean("enabled", c.enabled, d.enabled),
            minInterval = r.int("minInterval", c.minInterval, d.minInterval),
            maxInterval = r.int("maxInterval", c.maxInterval, d.maxInterval),
        )
    }

    private fun parseHttp(r: JsonReader, c: HttpConfig): HttpConfig {
        val d = HttpConfig()
        return HttpConfig(
            url = r.nullableString("url", c.url, d.url),
            method = r.enumValue(
                "method",
                c.method,
                d.method,
                HttpMethod.entries.map { it.wire },
                HttpMethod::fromWire,
            ),
            headers = r.stringMap("headers", c.headers, d.headers),
            params = r.objectText("params", c.params, d.params),
            autoSync = r.boolean("autoSync", c.autoSync, d.autoSync),
            autoSyncThreshold = r.int("autoSyncThreshold", c.autoSyncThreshold, d.autoSyncThreshold),
            syncInterval = r.int("syncInterval", c.syncInterval, d.syncInterval),
            batchSync = r.boolean("batchSync", c.batchSync, d.batchSync),
            maxBatchSize = r.int("maxBatchSize", c.maxBatchSize, d.maxBatchSize),
            disableAutoSyncOnCellular = r.boolean(
                "disableAutoSyncOnCellular",
                c.disableAutoSyncOnCellular,
                d.disableAutoSyncOnCellular,
            ),
            rootProperty = r.string("rootProperty", c.rootProperty, d.rootProperty),
            locationTemplate = r.nullableString("locationTemplate", c.locationTemplate, d.locationTemplate),
            geofenceTemplate = r.nullableString("geofenceTemplate", c.geofenceTemplate, d.geofenceTemplate),
            timeout = r.long("timeout", c.timeout, d.timeout),
            authorization = r.group<AuthorizationConfig?>("authorization", c.authorization, d.authorization) { ar, a ->
                parseAuthorization(ar, a ?: AuthorizationConfig())
            },
        )
    }

    private fun parseAuthorization(r: JsonReader, c: AuthorizationConfig): AuthorizationConfig {
        val d = AuthorizationConfig()
        return AuthorizationConfig(
            strategy = r.enumValue("strategy", c.strategy, d.strategy, listOf(d.strategy)) { value ->
                d.strategy.takeIf { it.equals(value, ignoreCase = true) }
            },
            accessToken = r.nullableString("accessToken", c.accessToken, d.accessToken),
            refreshToken = r.nullableString("refreshToken", c.refreshToken, d.refreshToken),
            refreshUrl = r.nullableString("refreshUrl", c.refreshUrl, d.refreshUrl),
            refreshPayload = r.objectText("refreshPayload", c.refreshPayload, d.refreshPayload),
            refreshHeaders = r.stringMap("refreshHeaders", c.refreshHeaders, d.refreshHeaders),
            refreshPayloadEncoding = r.enumValue(
                "refreshPayloadEncoding",
                c.refreshPayloadEncoding,
                d.refreshPayloadEncoding,
                RefreshPayloadEncoding.entries.map { it.wire },
                RefreshPayloadEncoding::fromWire,
            ),
            expires = r.long("expires", c.expires, d.expires),
        )
    }

    private fun parsePersistence(r: JsonReader, c: PersistenceConfig): PersistenceConfig {
        val d = PersistenceConfig()
        return PersistenceConfig(
            maxDaysToPersist = r.int("maxDaysToPersist", c.maxDaysToPersist, d.maxDaysToPersist),
            maxRecordsToPersist = r.int("maxRecordsToPersist", c.maxRecordsToPersist, d.maxRecordsToPersist),
            extras = r.objectText("extras", c.extras, d.extras),
        )
    }

    private fun parseApp(r: JsonReader, c: AppConfig): AppConfig {
        val d = AppConfig()
        return AppConfig(
            stopOnTerminate = r.boolean("stopOnTerminate", c.stopOnTerminate, d.stopOnTerminate),
            startOnBoot = r.boolean("startOnBoot", c.startOnBoot, d.startOnBoot),
        )
    }

    private fun parseNotification(r: JsonReader, c: NotificationConfig): NotificationConfig {
        val d = NotificationConfig()
        return NotificationConfig(
            title = r.nullableString("title", c.title, d.title),
            text = r.string("text", c.text, d.text),
            smallIcon = r.string("smallIcon", c.smallIcon, d.smallIcon),
            largeIcon = r.nullableString("largeIcon", c.largeIcon, d.largeIcon),
            color = r.nullableString("color", c.color, d.color),
            priority = r.enumValue(
                "priority",
                c.priority,
                d.priority,
                NotificationPriority.entries.map { it.wire },
                NotificationPriority::fromWire,
            ),
            channelId = r.string("channelId", c.channelId, d.channelId),
            channelName = r.string("channelName", c.channelName, d.channelName),
            actions = r.array("actions", c.actions, d.actions, ::parseAction),
        )
    }

    private fun parseAction(item: Any, path: String): NotificationActionButton {
        val obj = item as? JSONObject ?: JsonReader.invalid("$path must be an object {id, label}")
        val r = JsonReader(obj, path)
        val id = r.nullableString("id", null, null)
        val label = r.nullableString("label", null, null)
        if (id.isNullOrBlank()) JsonReader.invalid("$path.id is required")
        if (label == null) JsonReader.invalid("$path.label is required")
        r.warnUnknownKeys()
        return NotificationActionButton(id = id, label = label)
    }

    private fun parseGeofence(r: JsonReader, c: GeofenceConfig): GeofenceConfig {
        val d = GeofenceConfig()
        return GeofenceConfig(
            initialTriggerEntry = r.boolean("initialTriggerEntry", c.initialTriggerEntry, d.initialTriggerEntry),
        )
    }

    private fun parseLogger(r: JsonReader, c: LoggerConfig): LoggerConfig {
        val d = LoggerConfig()
        return LoggerConfig(
            logLevel = r.enumValue(
                "logLevel",
                c.logLevel,
                d.logLevel,
                LogLevel.entries.map { it.wire },
                LogLevel::fromWire,
            ),
            logMaxDays = r.int("logMaxDays", c.logMaxDays, d.logMaxDays),
        )
    }

    private fun parseRationale(r: JsonReader, c: BackgroundPermissionRationale): BackgroundPermissionRationale {
        val d = BackgroundPermissionRationale()
        return BackgroundPermissionRationale(
            title = r.nullableString("title", c.title, d.title),
            message = r.nullableString("message", c.message, d.message),
            positiveAction = r.nullableString("positiveAction", c.positiveAction, d.positiveAction),
            negativeAction = r.nullableString("negativeAction", c.negativeAction, d.negativeAction),
        )
    }

    // ---- toJson

    private fun geolocationToJson(g: GeolocationConfig): JSONObject = JSONObject()
        .put("desiredAccuracy", g.desiredAccuracy.wire)
        .put("distanceFilter", finiteOr(g.distanceFilter, GeolocationConfig().distanceFilter))
        .put("locationUpdateInterval", g.locationUpdateInterval)
        .put("fastestLocationUpdateInterval", g.fastestLocationUpdateInterval)
        .put("disableElasticity", g.disableElasticity)
        .put("elasticityMultiplier", finiteOr(g.elasticityMultiplier, GeolocationConfig().elasticityMultiplier))
        .put("stationaryRadius", finiteOr(g.stationaryRadius, GeolocationConfig().stationaryRadius))
        .put("stopTimeout", g.stopTimeout)
        .put("stopAfterElapsedMinutes", g.stopAfterElapsedMinutes)
        .put("stopOnStationary", g.stopOnStationary)
        .put("locationTimeout", g.locationTimeout)
        .put("filter", filterToJson(g.filter))

    private fun filterToJson(f: LocationFilterConfig): JSONObject {
        val d = LocationFilterConfig()
        return JSONObject()
            .put("useKalman", f.useKalman)
            .put("trackingAccuracyThreshold", finiteOr(f.trackingAccuracyThreshold, d.trackingAccuracyThreshold))
            .put("maxImpliedSpeed", finiteOr(f.maxImpliedSpeed, d.maxImpliedSpeed))
            .put("odometerAccuracyThreshold", finiteOr(f.odometerAccuracyThreshold, d.odometerAccuracyThreshold))
            .put("allowIdenticalLocations", f.allowIdenticalLocations)
            .put("rejectMockLocations", f.rejectMockLocations)
    }

    private fun activityToJson(a: ActivityConfig): JSONObject = JSONObject()
        .put("disableMotionActivityUpdates", a.disableMotionActivityUpdates)
        .put("activityRecognitionInterval", a.activityRecognitionInterval)
        .put("minimumActivityRecognitionConfidence", a.minimumActivityRecognitionConfidence)
        .put("motionTriggerDelay", a.motionTriggerDelay)
        .put("disableStopDetection", a.disableStopDetection)

    private fun heartbeatToJson(h: HeartbeatConfig): JSONObject = JSONObject()
        .put("enabled", h.enabled)
        .put("minInterval", h.minInterval)
        .put("maxInterval", h.maxInterval)

    private fun httpToJson(h: HttpConfig): JSONObject = JSONObject()
        .put("url", JsonUtil.orNull(h.url))
        .put("method", h.method.wire)
        .put("headers", JsonUtil.fromStringMap(h.headers))
        .put("params", objectOrEmpty(h.params, "http.params"))
        .put("autoSync", h.autoSync)
        .put("autoSyncThreshold", h.autoSyncThreshold)
        .put("syncInterval", h.syncInterval)
        .put("batchSync", h.batchSync)
        .put("maxBatchSize", h.maxBatchSize)
        .put("disableAutoSyncOnCellular", h.disableAutoSyncOnCellular)
        .put("rootProperty", h.rootProperty)
        .put("locationTemplate", JsonUtil.orNull(h.locationTemplate))
        .put("geofenceTemplate", JsonUtil.orNull(h.geofenceTemplate))
        .put("timeout", h.timeout)
        .put("authorization", h.authorization?.let { authorizationToJson(it) } ?: JSONObject.NULL)

    private fun authorizationToJson(a: AuthorizationConfig): JSONObject = JSONObject()
        .put("strategy", a.strategy)
        .put("accessToken", JsonUtil.orNull(a.accessToken))
        .put("refreshToken", JsonUtil.orNull(a.refreshToken))
        .put("refreshUrl", JsonUtil.orNull(a.refreshUrl))
        .put("refreshPayload", objectOrEmpty(a.refreshPayload, "http.authorization.refreshPayload"))
        .put("refreshHeaders", JsonUtil.fromStringMap(a.refreshHeaders))
        .put("refreshPayloadEncoding", a.refreshPayloadEncoding.wire)
        .put("expires", a.expires)

    private fun persistenceToJson(p: PersistenceConfig): JSONObject = JSONObject()
        .put("maxDaysToPersist", p.maxDaysToPersist)
        .put("maxRecordsToPersist", p.maxRecordsToPersist)
        .put("extras", objectOrEmpty(p.extras, "persistence.extras"))

    private fun notificationToJson(n: NotificationConfig): JSONObject {
        val actions = JSONArray()
        for (a in n.actions) actions.put(JSONObject().put("id", a.id).put("label", a.label))
        return JSONObject()
            .put("title", JsonUtil.orNull(n.title))
            .put("text", n.text)
            .put("smallIcon", n.smallIcon)
            .put("largeIcon", JsonUtil.orNull(n.largeIcon))
            .put("color", JsonUtil.orNull(n.color))
            .put("priority", n.priority.wire)
            .put("channelId", n.channelId)
            .put("channelName", n.channelName)
            .put("actions", actions)
    }

    private fun rationaleToJson(b: BackgroundPermissionRationale): JSONObject = JSONObject()
        .put("title", JsonUtil.orNull(b.title))
        .put("message", JsonUtil.orNull(b.message))
        .put("positiveAction", JsonUtil.orNull(b.positiveAction))
        .put("negativeAction", JsonUtil.orNull(b.negativeAction))

    /** Parses stored JSON object text; invalid text (only possible through `update{}`) becomes `{}`. */
    private fun objectOrEmpty(text: String, path: String): JSONObject {
        JsonUtil.parseObject(text)?.let { return it }
        if (text.isNotBlank()) Logger.w(TAG, "$path is not a JSON object; emitting {}")
        return JSONObject()
    }

    /** org.json rejects NaN/Infinity; such values (only possible through `update{}`) fall back to [fallback]. */
    private fun finiteOr(value: Double, fallback: Double): Double = if (value.isFinite()) value else fallback
}

/**
 * Typed reader over one JSON object of a partial config. Each accessor returns the current value when the key is
 * missing, the default when it is JSON null, and the parsed value otherwise; wrong types throw INVALID_ARGUMENT,
 * or, when [lenient], are logged and keep the current value. Every key an accessor asks for is remembered, so
 * [warnUnknownKeys] can report the rest.
 */
private class JsonReader(
    private val json: JSONObject,
    private val path: String,
    private val lenient: Boolean = false,
) {
    private val known = HashSet<String>()

    fun boolean(key: String, current: Boolean, default: Boolean): Boolean =
        read(key, current, default, "a boolean") { v ->
            when (v) {
                is Boolean -> v
                is String -> v.trim().lowercase().toBooleanStrictOrNull()
                else -> null
            }
        }

    fun double(key: String, current: Double, default: Double): Double =
        read(key, current, default, "a number") { toDouble(it) }

    fun long(key: String, current: Long, default: Long): Long =
        read(key, current, default, "a number") { v ->
            when (v) {
                is Int, is Long, is Short, is Byte -> (v as Number).toLong()
                else -> toDouble(v)?.toLong()
            }
        }

    fun int(key: String, current: Int, default: Int): Int =
        read(key, current, default, "a number") { v ->
            when (v) {
                is Int, is Short, is Byte -> (v as Number).toInt()
                else -> toDouble(v)?.toInt()
            }
        }

    fun string(key: String, current: String, default: String): String =
        read(key, current, default, "a string") { it as? String }

    fun nullableString(key: String, current: String?, default: String?): String? =
        read(key, current, default, "a string") { it as? String }

    fun <E> enumValue(key: String, current: E, default: E, allowed: List<String>, fromWire: (String) -> E?): E =
        read(key, current, default, "one of ${allowed.joinToString("|") { "'$it'" }}") { v ->
            (v as? String)?.let(fromWire)
        }

    /** An object of scalar values, replaced as a whole (null values are dropped). */
    fun stringMap(key: String, current: Map<String, String>, default: Map<String, String>): Map<String, String> =
        read(key, current, default, "an object") { v -> (v as? JSONObject)?.let { JsonUtil.toStringMap(it) } }

    /** An arbitrary object, replaced as a whole and kept as JSON text. */
    fun objectText(key: String, current: String, default: String): String =
        read(key, current, default, "an object") { v -> (v as? JSONObject)?.toString() }

    /** An array, replaced as a whole; [item] parses one non-null element (given its path) or throws. */
    fun <T> array(key: String, current: List<T>, default: List<T>, item: (Any, String) -> T): List<T> =
        read(key, current, default, "an array") { v ->
            (v as? JSONArray)?.let { array ->
                (0 until array.length()).map { i ->
                    val element = array.opt(i)
                    val itemPath = "${pathOf(key)}[$i]"
                    if (element == null || element == JSONObject.NULL) invalid("$itemPath must not be null")
                    item(element, itemPath)
                }
            }
        }

    /** A nested group: merged key by key onto [current] by [parse]; null resets it to [default]. */
    fun <T> group(key: String, current: T, default: T, parse: (JsonReader, T) -> T): T =
        read(key, current, default, "an object") { v ->
            (v as? JSONObject)?.let { obj ->
                val nested = JsonReader(obj, pathOf(key), lenient)
                val result = parse(nested, current)
                nested.warnUnknownKeys()
                result
            }
        }

    fun warnUnknownKeys() {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in known) Logger.w(TAG, "ignoring unknown config key '${pathOf(key)}'")
        }
    }

    private inline fun <T> read(key: String, current: T, default: T, expected: String, convert: (Any) -> T?): T {
        known += key
        if (!json.has(key)) return current
        val raw = json.opt(key)
        if (raw == null || raw == JSONObject.NULL) return default
        if (!lenient) return convert(raw) ?: invalid("${pathOf(key)} must be $expected (got ${describe(raw)})")
        return try {
            convert(raw) ?: invalid("${pathOf(key)} must be $expected (got ${describe(raw)})")
        } catch (e: TrackingException) {
            Logger.w(TAG, "ignoring invalid stored value: ${e.message}")
            current
        }
    }

    private fun pathOf(key: String): String = if (path.isEmpty()) key else "$path.$key"

    private fun toDouble(v: Any): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    private fun describe(raw: Any): String = when (raw) {
        is String -> "'${raw.take(MAX_ECHO)}'"
        is JSONObject -> "an object"
        is JSONArray -> "an array"
        else -> raw.toString().take(MAX_ECHO)
    }

    companion object {
        private const val TAG = "LT.ConfigJson"
        private const val MAX_ECHO = 40

        fun invalid(message: String): Nothing = throw TrackingException(ErrorCode.INVALID_ARGUMENT, "config.$message")
    }
}
