package com.brickssoft.locationtracking.model

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.permission.PermissionState
import com.brickssoft.locationtracking.permission.PermissionType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Shared JSON codecs (SCAFFOLD). Every JS-facing shape and the HTTP wire format are built here.

/** Null-safe helpers around org.json (whose `opt*` methods return "" / 0 / "null" for missing or null values). */
object JsonUtil {
    /** Parses JSON object text; null, blank or invalid text returns null. */
    fun parseObject(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return try {
            JSONObject(text)
        } catch (_: JSONException) {
            null
        }
    }

    /** String value, or null if the key is missing or JSON null. Non-string values are converted to text. */
    fun optString(json: JSONObject, key: String): String? = if (json.isNull(key)) null else json.opt(key)?.toString()

    /** Number value (or numeric string), or null if missing, JSON null or not numeric. */
    fun optDouble(json: JSONObject, key: String): Double? {
        if (json.isNull(key)) return null
        return when (val v = json.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    fun optFloat(json: JSONObject, key: String): Float? = optDouble(json, key)?.toFloat()

    fun optLong(json: JSONObject, key: String): Long? = optDouble(json, key)?.toLong()

    fun optInt(json: JSONObject, key: String): Int? = optDouble(json, key)?.toInt()

    /** Boolean value (or "true"/"false" string), or null if missing, JSON null or not boolean. */
    fun optBoolean(json: JSONObject, key: String): Boolean? {
        if (json.isNull(key)) return null
        return when (val v = json.opt(key)) {
            is Boolean -> v
            is String -> v.lowercase().toBooleanStrictOrNull()
            else -> null
        }
    }

    /** Converts a JSON object with scalar values to a string map (null values are dropped). */
    fun toStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            optString(json, key)?.let { out[key] = it }
        }
        return out
    }

    fun fromStringMap(map: Map<String, String>): JSONObject {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        return json
    }

    fun toStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> if (array.isNull(i)) null else array.opt(i)?.toString() }
    }

    /**
     * Converts a Float to the Double with the same shortest decimal representation (5.2f -> 5.2, not
     * 5.199999809265137), so JSON output is clean. Non-finite values become null.
     */
    fun floatToJson(value: Float): Double? = if (value.isNaN() || value.isInfinite()) null else value.toString().toDouble()

    /** [floatToJson] for nullable input, returning [JSONObject.NULL] for null. */
    fun floatOrNull(value: Float?): Any = value?.let { floatToJson(it) } ?: JSONObject.NULL

    /** [value] or [JSONObject.NULL]. */
    fun orNull(value: Any?): Any = value ?: JSONObject.NULL
}

/**
 * Record <-> wire JSON (architecture §2). The same shape is sent to JS (without `sent_at`) and stored in SQLite.
 */
object RecordJson {
    private const val TAG = "LT.RecordJson"

    /**
     * Builds the wire shape. `timestamp` and `coords` are null when [Record.location] is null; `sent_at` is
     * present only when [sentAt] is given; `extras`, `geofence`, `provider`, `reason` and `heartbeat` only when set.
     */
    fun toJson(record: Record, sentAt: Long? = null): JSONObject {
        val location = record.location
        val json = JSONObject()
        json.put("uuid", record.uuid)
        json.put("event", record.event.wire)
        json.put("timestamp", location?.let { Iso8601.format(it.time) } ?: JSONObject.NULL)
        json.put("recorded_at", Iso8601.format(record.recordedAt))
        if (sentAt != null) json.put("sent_at", Iso8601.format(sentAt))
        json.put("elapsed_realtime_ms", record.elapsedRealtimeMs)
        json.put("boot_count", record.bootCount)
        json.put("is_moving", record.isMoving)
        json.put("odometer", record.odometer)
        json.put("mock", location?.isMock ?: false)
        json.put("coords", location?.let { coordsToJson(it) } ?: JSONObject.NULL)
        json.put(
            "activity",
            JSONObject().put("type", record.activity.type.wire).put("confidence", record.activity.confidence),
        )
        json.put(
            "battery",
            JSONObject()
                .put("level", JsonUtil.floatOrNull(record.battery.level))
                .put("is_charging", record.battery.isCharging),
        )
        json.put("backend", record.backend?.wire ?: JSONObject.NULL)
        if (record.extras != null) {
            val extras = JsonUtil.parseObject(record.extras)
            if (extras != null) json.put("extras", extras) else Logger.w(TAG, "dropping invalid extras of ${record.uuid}")
        }
        record.geofence?.let { json.put("geofence", geofenceHitToJson(it)) }
        record.provider?.let { json.put("provider", ProviderStateJson.toJson(it)) }
        record.reason?.let { json.put("reason", it) }
        record.heartbeat?.let { json.put("heartbeat", heartbeatMetaToJson(it)) }
        return json
    }

    /** The `heartbeat` object of a heartbeat record (snake_case keys, `next_at` ISO-8601 or null). */
    fun heartbeatMetaToJson(meta: HeartbeatMeta): JSONObject = JSONObject()
        .put("strategy", meta.strategy.wire)
        .put("min_interval", meta.minInterval)
        .put("max_interval", meta.maxInterval)
        .put("next_at", JsonUtil.orNull(Iso8601.formatOrNull(meta.nextAt)))
        .put("battery_exempt", meta.batteryExempt)
        .put("device_idle", meta.deviceIdle)

    /** Records as a JSON array, in the given order. */
    fun toJsonArray(records: List<Record>, sentAt: Long? = null): JSONArray {
        val array = JSONArray()
        for (r in records) array.put(toJson(r, sentAt))
        return array
    }

    /** The `coords` object of a location. */
    fun coordsToJson(l: TrackedLocation): JSONObject = JSONObject()
        .put("latitude", l.latitude)
        .put("longitude", l.longitude)
        .put("accuracy", JsonUtil.floatOrNull(l.accuracy))
        .put("altitude", JsonUtil.orNull(l.altitude))
        .put("altitude_accuracy", JsonUtil.floatOrNull(l.altitudeAccuracy))
        .put("speed", JsonUtil.floatOrNull(l.speed))
        .put("speed_accuracy", JsonUtil.floatOrNull(l.speedAccuracy))
        .put("heading", JsonUtil.floatOrNull(l.heading))
        .put("heading_accuracy", JsonUtil.floatOrNull(l.headingAccuracy))

    fun geofenceHitToJson(hit: GeofenceHit): JSONObject {
        val json = JSONObject().put("identifier", hit.identifier).put("action", hit.action.name)
        JsonUtil.parseObject(hit.extras)?.let { json.put("extras", it) }
        return json
    }

    /**
     * Parses the wire shape (e.g. a SQLite row). `sent_at` is ignored. The non-wire location fields
     * (`elapsedRealtimeNanos`, `provider`) come back as 0 / null; everything else round-trips.
     *
     * @throws JSONException if `uuid`, `event` or the coordinates are missing or invalid.
     */
    fun fromJson(json: JSONObject): Record {
        val uuid = JsonUtil.optString(json, "uuid") ?: throw JSONException("record.uuid is missing")
        val eventWire = JsonUtil.optString(json, "event")
        val event = RecordEvent.fromWire(eventWire) ?: throw JSONException("unknown record.event '$eventWire'")
        val recordedAt = Iso8601.parse(JsonUtil.optString(json, "recorded_at")) ?: 0L
        val coords = json.optJSONObject("coords")
        val location = coords?.let {
            TrackedLocation(
                latitude = JsonUtil.optDouble(it, "latitude") ?: throw JSONException("coords.latitude is missing"),
                longitude = JsonUtil.optDouble(it, "longitude") ?: throw JSONException("coords.longitude is missing"),
                accuracy = JsonUtil.optFloat(it, "accuracy") ?: 0f,
                altitude = JsonUtil.optDouble(it, "altitude"),
                altitudeAccuracy = JsonUtil.optFloat(it, "altitude_accuracy"),
                speed = JsonUtil.optFloat(it, "speed"),
                speedAccuracy = JsonUtil.optFloat(it, "speed_accuracy"),
                heading = JsonUtil.optFloat(it, "heading"),
                headingAccuracy = JsonUtil.optFloat(it, "heading_accuracy"),
                time = Iso8601.parse(JsonUtil.optString(json, "timestamp")) ?: recordedAt,
                isMock = JsonUtil.optBoolean(json, "mock") ?: false,
            )
        }
        val activity = json.optJSONObject("activity")?.let {
            ActivitySample(
                type = ActivityType.fromWire(JsonUtil.optString(it, "type")) ?: ActivityType.UNKNOWN,
                confidence = JsonUtil.optInt(it, "confidence") ?: 0,
            )
        } ?: ActivitySample.UNKNOWN
        val battery = json.optJSONObject("battery")?.let {
            BatterySnapshot(
                level = JsonUtil.optFloat(it, "level") ?: -1f,
                isCharging = JsonUtil.optBoolean(it, "is_charging") ?: false,
            )
        } ?: BatterySnapshot.UNKNOWN
        return Record(
            uuid = uuid,
            event = event,
            location = location,
            recordedAt = recordedAt,
            elapsedRealtimeMs = JsonUtil.optLong(json, "elapsed_realtime_ms") ?: 0L,
            bootCount = JsonUtil.optInt(json, "boot_count") ?: -1,
            isMoving = JsonUtil.optBoolean(json, "is_moving") ?: false,
            odometer = JsonUtil.optDouble(json, "odometer") ?: 0.0,
            activity = activity,
            battery = battery,
            backend = ProviderKind.fromWire(JsonUtil.optString(json, "backend")),
            extras = json.optJSONObject("extras")?.toString(),
            geofence = json.optJSONObject("geofence")?.let { geofenceHitFromJson(it) },
            provider = json.optJSONObject("provider")?.let { ProviderStateJson.fromJson(it) },
            reason = JsonUtil.optString(json, "reason"),
            heartbeat = json.optJSONObject("heartbeat")?.let { heartbeatMetaFromJson(it) },
        )
    }

    /** Lenient: an unknown strategy is dropped (null), missing numbers become 0 and missing flags false. */
    private fun heartbeatMetaFromJson(json: JSONObject): HeartbeatMeta? {
        val strategy = HeartbeatStrategy.fromWire(JsonUtil.optString(json, "strategy")) ?: return null
        return HeartbeatMeta(
            strategy = strategy,
            minInterval = JsonUtil.optInt(json, "min_interval") ?: 0,
            maxInterval = JsonUtil.optInt(json, "max_interval") ?: 0,
            nextAt = Iso8601.parse(JsonUtil.optString(json, "next_at")),
            batteryExempt = JsonUtil.optBoolean(json, "battery_exempt") ?: false,
            deviceIdle = JsonUtil.optBoolean(json, "device_idle") ?: false,
        )
    }

    private fun geofenceHitFromJson(json: JSONObject): GeofenceHit = GeofenceHit(
        identifier = JsonUtil.optString(json, "identifier") ?: "",
        action = GeofenceAction.fromWire(JsonUtil.optString(json, "action")) ?: GeofenceAction.ENTER,
        extras = json.optJSONObject("extras")?.toString(),
    )
}

/**
 * Lossless (non-wire) JSON for a [TrackedLocation], for persisting e.g. `RuntimeState.lastLocation`.
 * Keys use the Kotlin property names; `time` is epoch ms.
 */
object TrackedLocationJson {
    fun toJson(l: TrackedLocation): JSONObject = JSONObject()
        .put("latitude", l.latitude)
        .put("longitude", l.longitude)
        .put("accuracy", JsonUtil.floatOrNull(l.accuracy))
        .put("altitude", JsonUtil.orNull(l.altitude))
        .put("altitudeAccuracy", JsonUtil.floatOrNull(l.altitudeAccuracy))
        .put("speed", JsonUtil.floatOrNull(l.speed))
        .put("speedAccuracy", JsonUtil.floatOrNull(l.speedAccuracy))
        .put("heading", JsonUtil.floatOrNull(l.heading))
        .put("headingAccuracy", JsonUtil.floatOrNull(l.headingAccuracy))
        .put("time", l.time)
        .put("elapsedRealtimeNanos", l.elapsedRealtimeNanos)
        .put("provider", JsonUtil.orNull(l.provider))
        .put("isMock", l.isMock)

    /** @throws JSONException if latitude/longitude are missing. */
    fun fromJson(json: JSONObject): TrackedLocation = TrackedLocation(
        latitude = JsonUtil.optDouble(json, "latitude") ?: throw JSONException("latitude is missing"),
        longitude = JsonUtil.optDouble(json, "longitude") ?: throw JSONException("longitude is missing"),
        accuracy = JsonUtil.optFloat(json, "accuracy") ?: 0f,
        altitude = JsonUtil.optDouble(json, "altitude"),
        altitudeAccuracy = JsonUtil.optFloat(json, "altitudeAccuracy"),
        speed = JsonUtil.optFloat(json, "speed"),
        speedAccuracy = JsonUtil.optFloat(json, "speedAccuracy"),
        heading = JsonUtil.optFloat(json, "heading"),
        headingAccuracy = JsonUtil.optFloat(json, "headingAccuracy"),
        time = JsonUtil.optLong(json, "time") ?: 0L,
        elapsedRealtimeNanos = JsonUtil.optLong(json, "elapsedRealtimeNanos") ?: 0L,
        provider = JsonUtil.optString(json, "provider"),
        isMock = JsonUtil.optBoolean(json, "isMock") ?: false,
    )
}

/** GeofenceSpec <-> JS `Geofence` (also used for SQLite rows). */
object GeofenceJson {
    /**
     * JS shape. latitude/longitude/radius are always present (for polygons: the enclosing circle);
     * `vertices` (`[[lat,lng],...]`) and `extras` only when set.
     */
    fun toJson(g: GeofenceSpec): JSONObject {
        val json = JSONObject()
        json.put("identifier", g.identifier)
        json.put("latitude", g.latitude)
        json.put("longitude", g.longitude)
        json.put("radius", JsonUtil.floatOrNull(g.radius))
        g.vertices?.let { vertices ->
            val array = JSONArray()
            for (v in vertices) array.put(JSONArray().put(v.latitude).put(v.longitude))
            json.put("vertices", array)
        }
        json.put("notifyOnEntry", g.notifyOnEntry)
        json.put("notifyOnExit", g.notifyOnExit)
        json.put("notifyOnDwell", g.notifyOnDwell)
        json.put("loiteringDelay", g.loiteringDelay)
        JsonUtil.parseObject(g.extras)?.let { json.put("extras", it) }
        return json
    }

    fun toJsonArray(list: List<GeofenceSpec>): JSONArray {
        val array = JSONArray()
        for (g in list) array.put(toJson(g))
        return array
    }

    /**
     * Structural parsing only; semantic validation (vertex count, radius > 0, max 100) belongs to the
     * GeofenceManager. A polygon's missing latitude/longitude/radius default to 0.
     *
     * @throws TrackingException INVALID_ARGUMENT if the identifier is missing, a circle lacks
     *   latitude/longitude/radius, or `vertices` is malformed.
     */
    fun fromJson(json: JSONObject): GeofenceSpec {
        val identifier = JsonUtil.optString(json, "identifier")
        if (identifier.isNullOrBlank()) invalid("geofence.identifier is required")
        val vertices = if (json.isNull("vertices")) null else parseVertices(identifier, json.opt("vertices"))
        val latitude = JsonUtil.optDouble(json, "latitude")
        val longitude = JsonUtil.optDouble(json, "longitude")
        val radius = JsonUtil.optDouble(json, "radius")
        if (vertices == null && (latitude == null || longitude == null || radius == null)) {
            invalid("geofence '$identifier' needs latitude, longitude and radius, or vertices")
        }
        return GeofenceSpec(
            identifier = identifier,
            latitude = latitude ?: 0.0,
            longitude = longitude ?: 0.0,
            radius = radius?.toFloat() ?: 0f,
            vertices = vertices,
            notifyOnEntry = JsonUtil.optBoolean(json, "notifyOnEntry") ?: true,
            notifyOnExit = JsonUtil.optBoolean(json, "notifyOnExit") ?: true,
            notifyOnDwell = JsonUtil.optBoolean(json, "notifyOnDwell") ?: false,
            loiteringDelay = JsonUtil.optLong(json, "loiteringDelay") ?: 30_000L,
            extras = json.optJSONObject("extras")?.toString(),
        )
    }

    /** @throws TrackingException INVALID_ARGUMENT if any element is not a geofence object. */
    fun fromJsonArray(array: JSONArray): List<GeofenceSpec> = (0 until array.length()).map { i ->
        val item = array.optJSONObject(i) ?: invalid("geofences[$i] is not an object")
        fromJson(item)
    }

    private fun parseVertices(identifier: String, value: Any?): List<LatLng> {
        val array = value as? JSONArray ?: invalid("geofence '$identifier': vertices must be [[lat,lng],...]")
        return (0 until array.length()).map { i ->
            val pair = array.optJSONArray(i)
            val lat = pair?.opt(0) as? Number
            val lng = pair?.opt(1) as? Number
            if (pair == null || pair.length() != 2 || lat == null || lng == null) {
                invalid("geofence '$identifier': vertices[$i] must be [lat,lng]")
            }
            LatLng(lat.toDouble(), lng.toDouble())
        }
    }

    private fun invalid(message: String): Nothing = throw TrackingException(ErrorCode.INVALID_ARGUMENT, message)
}

/** JS `ProviderState`. */
object ProviderStateJson {
    fun toJson(s: ProviderState): JSONObject = JSONObject()
        .put("enabled", s.enabled)
        .put("gps", s.gps)
        .put("network", s.network)
        .put("permission", s.permission.wire)
        .put("accuracy", s.accuracy.wire)
        .put("backend", s.backend.wire)

    /** Lenient: unknown enum values fall back to DENIED / NONE / ANDROID. */
    fun fromJson(json: JSONObject): ProviderState = ProviderState(
        enabled = JsonUtil.optBoolean(json, "enabled") ?: false,
        gps = JsonUtil.optBoolean(json, "gps") ?: false,
        network = JsonUtil.optBoolean(json, "network") ?: false,
        permission = PermissionLevel.fromWire(JsonUtil.optString(json, "permission")) ?: PermissionLevel.DENIED,
        accuracy = AccuracyLevel.fromWire(JsonUtil.optString(json, "accuracy")) ?: AccuracyLevel.NONE,
        backend = ProviderKind.fromWire(JsonUtil.optString(json, "backend")) ?: ProviderKind.ANDROID,
    )
}

/** JS `HeartbeatStatus` (times as ISO strings or null). */
object HeartbeatStatusJson {
    fun toJson(s: HeartbeatStatus): JSONObject = JSONObject()
        .put("enabled", s.enabled)
        .put("minInterval", s.minInterval)
        .put("maxInterval", s.maxInterval)
        .put("lastRecordAt", JsonUtil.orNull(Iso8601.formatOrNull(s.lastRecordAt)))
        .put("lastHeartbeatAt", JsonUtil.orNull(Iso8601.formatOrNull(s.lastHeartbeatAt)))
        .put("nextHeartbeatAt", JsonUtil.orNull(Iso8601.formatOrNull(s.nextHeartbeatAt)))
        .put("strategy", s.strategy.wire)
        .put("canScheduleExactAlarms", s.canScheduleExactAlarms)
        .put("isIgnoringBatteryOptimizations", s.isIgnoringBatteryOptimizations)
        .put("isDeviceIdleMode", s.isDeviceIdleMode)
        .put("isPowerSaveMode", s.isPowerSaveMode)
        .put("pendingHeartbeats", s.pendingHeartbeats)
}

/** JS `DeviceInfo`. */
object DeviceInfoJson {
    fun toJson(d: DeviceInfo): JSONObject {
        val providers = JSONArray()
        for (p in d.packagedProviders) providers.put(p)
        return JSONObject()
            .put("platform", d.platform)
            .put("manufacturer", d.manufacturer)
            .put("model", d.model)
            .put("brand", d.brand)
            .put("osVersion", d.osVersion)
            .put("sdkInt", d.sdkInt)
            .put("pluginVersion", d.pluginVersion)
            .put("gmsAvailable", d.gmsAvailable)
            .put("hmsAvailable", d.hmsAvailable)
            .put("backend", d.backend.wire)
            .put("packagedProviders", providers)
    }
}

/** JS `Sensors`. */
object SensorsJson {
    fun toJson(s: Sensors): JSONObject = JSONObject()
        .put("accelerometer", s.accelerometer)
        .put("gyroscope", s.gyroscope)
        .put("magnetometer", s.magnetometer)
        .put("significantMotion", s.significantMotion)
        .put("stepCounter", s.stepCounter)
        .put("stepDetector", s.stepDetector)
        .put("barometer", s.barometer)
}

/** JS `PowerManagerInfo`. */
object PowerManagerInfoJson {
    fun toJson(p: PowerManagerInfo): JSONObject =
        JSONObject().put("manufacturer", p.manufacturer).put("available", p.available)
}

/** JS `BatteryOptimizationStatus`. */
object BatteryOptimizationStatusJson {
    fun toJson(b: BatteryOptimizationStatus): JSONObject = JSONObject()
        .put("isIgnoringBatteryOptimizations", b.isIgnoringBatteryOptimizations)
        .put("canScheduleExactAlarms", b.canScheduleExactAlarms)
        .put("isDeviceIdleMode", b.isDeviceIdleMode)
}

/** JS `PermissionStatus`: every [PermissionType] alias is present (missing entries are "prompt"). */
object PermissionStatusJson {
    fun toJson(status: Map<PermissionType, PermissionState>): JSONObject {
        val json = JSONObject()
        for (type in PermissionType.entries) json.put(type.alias, (status[type] ?: PermissionState.PROMPT).js)
        return json
    }
}

/** JS `HttpEvent` shape. */
object HttpResultJson {
    fun toJson(r: HttpResult): JSONObject {
        val uuids = JSONArray()
        for (u in r.uuids) uuids.put(u)
        return JSONObject()
            .put("success", r.success)
            .put("status", r.status)
            .put("responseText", r.responseText)
            .put("uuids", uuids)
    }
}

/** JS `ConnectivityChangeEvent` shape. */
object ConnectivityJson {
    fun toJson(c: Connectivity): JSONObject = JSONObject().put("connected", c.connected).put("type", c.type.wire)
}

/** JS event name and payload of every [TrackingEvent] (see `LocationTrackingEventMap`). */
object EventJson {
    const val LOCATION = "location"
    const val MOTIONCHANGE = "motionchange"
    const val ACTIVITYCHANGE = "activitychange"
    const val PROVIDERCHANGE = "providerchange"
    const val HEARTBEAT = "heartbeat"
    const val GEOFENCE = "geofence"
    const val GEOFENCESCHANGE = "geofenceschange"
    const val HTTP = "http"
    const val CONNECTIVITYCHANGE = "connectivitychange"
    const val POWERSAVECHANGE = "powersavechange"
    const val ENABLEDCHANGE = "enabledchange"
    const val NOTIFICATIONACTION = "notificationaction"
    const val AUTHORIZATION = "authorization"

    fun name(event: TrackingEvent): String = when (event) {
        is TrackingEvent.Location -> LOCATION
        is TrackingEvent.MotionChange -> MOTIONCHANGE
        is TrackingEvent.ActivityChange -> ACTIVITYCHANGE
        is TrackingEvent.ProviderChange -> PROVIDERCHANGE
        is TrackingEvent.Heartbeat -> HEARTBEAT
        is TrackingEvent.Geofence -> GEOFENCE
        is TrackingEvent.GeofencesChange -> GEOFENCESCHANGE
        is TrackingEvent.Http -> HTTP
        is TrackingEvent.ConnectivityChange -> CONNECTIVITYCHANGE
        is TrackingEvent.PowerSaveChange -> POWERSAVECHANGE
        is TrackingEvent.EnabledChange -> ENABLEDCHANGE
        is TrackingEvent.NotificationAction -> NOTIFICATIONACTION
        is TrackingEvent.Authorization -> AUTHORIZATION
    }

    fun payload(event: TrackingEvent): JSONObject = when (event) {
        is TrackingEvent.Location -> RecordJson.toJson(event.record)
        is TrackingEvent.MotionChange ->
            JSONObject().put("isMoving", event.isMoving).put("location", RecordJson.toJson(event.record))
        is TrackingEvent.ActivityChange ->
            JSONObject().put("activity", event.activity.type.wire).put("confidence", event.activity.confidence)
        is TrackingEvent.ProviderChange -> ProviderStateJson.toJson(event.state)
        is TrackingEvent.Heartbeat -> JSONObject().put("location", RecordJson.toJson(event.record))
        is TrackingEvent.Geofence -> {
            val json = JSONObject()
                .put("identifier", event.identifier)
                .put("action", event.action.name)
                .put("location", RecordJson.toJson(event.record))
            JsonUtil.parseObject(event.extras)?.let { json.put("extras", it) }
            json
        }
        is TrackingEvent.GeofencesChange -> {
            val off = JSONArray()
            for (id in event.off) off.put(id)
            JSONObject().put("on", GeofenceJson.toJsonArray(event.on)).put("off", off)
        }
        is TrackingEvent.Http -> HttpResultJson.toJson(event.result)
        is TrackingEvent.ConnectivityChange -> ConnectivityJson.toJson(event.connectivity)
        is TrackingEvent.PowerSaveChange -> JSONObject().put("isPowerSaveMode", event.isPowerSaveMode)
        is TrackingEvent.EnabledChange -> JSONObject().put("enabled", event.enabled)
        is TrackingEvent.NotificationAction -> JSONObject().put("id", event.id)
        is TrackingEvent.Authorization -> {
            val json = JSONObject().put("success", event.success).put("status", event.status)
            event.error?.let { json.put("error", it) }
            JsonUtil.parseObject(event.responseJson)?.let { json.put("response", it) }
            json
        }
    }
}
