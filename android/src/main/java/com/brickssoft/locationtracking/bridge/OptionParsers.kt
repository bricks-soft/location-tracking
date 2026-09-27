package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.logging.LogQuery
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.GeofenceJson
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.permission.PermissionType
import com.brickssoft.locationtracking.position.CurrentPositionOptions
import com.brickssoft.locationtracking.position.WatchPositionOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** `uploadLog` options; [paramsJson] is JSON object text. */
internal data class UploadLogOptions(val url: String, val headers: Map<String, String>, val paramsJson: String?)

/**
 * Parses and validates JS call options into Kotlin option types.
 *
 * A missing key or JSON null means "use the default". A present value of the wrong type or out of range throws
 * [TrackingException] with [ErrorCode.INVALID_ARGUMENT]. Numbers must be JSON numbers (no numeric strings) and
 * booleans must be JSON booleans.
 */
internal object OptionParsers {
    /** `requestPermissions` default: every type, in the order of the JS contract (background last). */
    val DEFAULT_PERMISSION_ORDER: List<PermissionType> = listOf(
        PermissionType.LOCATION,
        PermissionType.NOTIFICATIONS,
        PermissionType.ACTIVITY_RECOGNITION,
        PermissionType.BACKGROUND_LOCATION,
    )

    private val LATITUDE = -90.0..90.0
    private val LONGITUDE = -180.0..180.0
    private val HEADING = 0.0..360.0

    // ---- typed options

    /** JS `CurrentPositionOptions`. */
    fun currentPositionOptions(json: JSONObject): CurrentPositionOptions {
        val defaults = CurrentPositionOptions()
        return CurrentPositionOptions(
            samples = optInt(json, "samples", min = 1) ?: defaults.samples,
            timeoutMs = optLong(json, "timeout", min = 1),
            maximumAgeMs = optLong(json, "maximumAge", min = 0) ?: defaults.maximumAgeMs,
            desiredAccuracy = optAccuracy(json) ?: defaults.desiredAccuracy,
            persist = optBoolean(json, "persist") ?: defaults.persist,
            extras = optObject(json, "extras")?.toString(),
        )
    }

    /** JS `WatchPositionOptions`. */
    fun watchPositionOptions(json: JSONObject): WatchPositionOptions {
        val defaults = WatchPositionOptions()
        return WatchPositionOptions(
            intervalMs = optLong(json, "interval", min = 0) ?: defaults.intervalMs,
            desiredAccuracy = optAccuracy(json) ?: defaults.desiredAccuracy,
            persist = optBoolean(json, "persist") ?: defaults.persist,
            extras = optObject(json, "extras")?.toString(),
        )
    }

    /**
     * JS `Geofence` -> [GeofenceSpec] via [GeofenceJson], plus value checks: coordinates in range, a circle's
     * radius > 0, a polygon's >= 3 vertices, loiteringDelay >= 0, and typed notify flags and extras.
     */
    fun geofence(json: JSONObject): GeofenceSpec {
        // GeofenceJson is lenient (numeric strings, any identifier coerced to text); enforce JSON types first.
        val id = requireString(json, "identifier", "geofence.identifier")
        for (key in listOf("latitude", "longitude", "radius")) optNumber(json, key, "geofence '$id' $key")
        val spec = GeofenceJson.fromJson(json)
        for (key in listOf("notifyOnEntry", "notifyOnExit", "notifyOnDwell")) {
            optBoolean(json, key, "geofence '$id' $key")
        }
        optObject(json, "extras", "geofence '$id' extras")
        optLong(json, "loiteringDelay", min = 0, path = "geofence '$id' loiteringDelay")
        val vertices = spec.vertices
        if (vertices == null) {
            checkRange(spec.latitude, LATITUDE, "geofence '$id' latitude")
            checkRange(spec.longitude, LONGITUDE, "geofence '$id' longitude")
            if (!(spec.radius > 0f) || spec.radius.isInfinite()) invalid("geofence '$id' radius must be > 0")
        } else {
            if (vertices.size < 3) invalid("geofence '$id' needs at least 3 vertices")
            vertices.forEachIndexed { i, v ->
                checkRange(v.latitude, LATITUDE, "geofence '$id' vertices[$i] latitude")
                checkRange(v.longitude, LONGITUDE, "geofence '$id' vertices[$i] longitude")
            }
        }
        return spec
    }

    /** The required `geofences` array of `addGeofences`. */
    fun geofences(json: JSONObject): List<GeofenceSpec> {
        val array = requireArray(json, "geofences")
        return (0 until array.length()).map { i ->
            val item = array.opt(i) as? JSONObject ?: invalid("geofences[$i] must be an object")
            geofence(item)
        }
    }

    /**
     * The required `location` (JS `InsertLocationInput`) of `insertLocation`, validated and returned as is for
     * `RecordFactory.fromExternal`.
     */
    fun insertLocationInput(json: JSONObject): JSONObject {
        val location = requireObject(json, "location")
        val coords = requireObject(location, "coords", "location.coords")
        requireNumber(coords, "latitude", "location.coords.latitude").also {
            checkRange(it, LATITUDE, "location.coords.latitude")
        }
        requireNumber(coords, "longitude", "location.coords.longitude").also {
            checkRange(it, LONGITUDE, "location.coords.longitude")
        }
        optNumber(coords, "altitude", "location.coords.altitude")
        for (key in listOf("accuracy", "altitude_accuracy", "speed", "speed_accuracy", "heading_accuracy")) {
            val value = optNumber(coords, key, "location.coords.$key")
            if (value != null && value < 0) invalid("location.coords.$key must be >= 0")
        }
        optNumber(coords, "heading", "location.coords.heading")?.let {
            checkRange(it, HEADING, "location.coords.heading")
        }
        optString(location, "timestamp", "location.timestamp")?.let {
            if (Iso8601.parse(it) == null) invalid("location.timestamp must be an ISO-8601 date, got '$it'")
        }
        optString(location, "event", "location.event")?.let {
            if (RecordEvent.fromWire(it) == null) invalid("location.event '$it' is not a record event")
        }
        optBoolean(location, "is_moving", "location.is_moving")
        optObject(location, "extras", "location.extras")
        return location
    }

    /** JS `LogQuery`; `order` defaults to ascending. */
    fun logQuery(json: JSONObject): LogQuery {
        val start = optLong(json, "start", min = 0)
        val end = optLong(json, "end", min = 0)
        if (start != null && end != null && start > end) invalid("'start' must not be after 'end'")
        val level = optString(json, "level")?.let { LogLevel.fromWire(it) ?: invalid("unknown log level '$it'") }
        val ascending = when (val order = optString(json, "order")?.lowercase()) {
            null, "asc" -> true
            "desc" -> false
            else -> invalid("'order' must be 'asc' or 'desc', got '$order'")
        }
        val limit = optInt(json, "limit", min = 0)
        return LogQuery(start = start, end = end, level = level, limit = limit, ascending = ascending)
    }

    /** The required `level` of `log()`: any [LogLevel] except OFF. */
    fun logLevel(json: JSONObject): LogLevel {
        val wire = requireString(json, "level")
        val level = LogLevel.fromWire(wire)
        if (level == null || level == LogLevel.OFF) invalid("'level' must be error, warn, info, debug or verbose")
        return level
    }

    /**
     * `requestPermissions({permissions?})`: missing -> [DEFAULT_PERMISSION_ORDER]; otherwise the given types,
     * de-duplicated, in that canonical order.
     */
    fun permissionTypes(json: JSONObject): List<PermissionType> {
        val array = optArray(json, "permissions") ?: return DEFAULT_PERMISSION_ORDER
        val requested = (0 until array.length()).map { i ->
            val alias = array.opt(i)
            PermissionType.fromAlias(alias as? String) ?: invalid("unknown permission type '$alias'")
        }.toSet()
        return DEFAULT_PERMISSION_ORDER.filter { it in requested }
    }

    /** `uploadLog({url, headers?, params?})`: url must be http(s). */
    fun uploadLogOptions(json: JSONObject): UploadLogOptions {
        val url = requireString(json, "url")
        if (url.toHttpUrlOrNull() == null) invalid("'url' must be an http(s) URL, got '$url'")
        return UploadLogOptions(url, stringMap(json, "headers"), optObject(json, "params")?.toString())
    }

    /**
     * An optional object of header-like values; strings, numbers and booleans are converted to text,
     * null values are dropped.
     */
    fun stringMap(json: JSONObject, key: String): Map<String, String> {
        val obj = optObject(json, key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (obj.isNull(name)) continue
            when (val value = obj.opt(name)) {
                is String, is Number, is Boolean -> out[name] = value.toString()
                else -> invalid("'$key.$name' must be a string")
            }
        }
        return out
    }

    // ---- primitives (path = the name used in error messages)

    fun optObject(json: JSONObject, key: String, path: String = key): JSONObject? = when (val v = value(json, key)) {
        null -> null
        is JSONObject -> v
        else -> invalid("'$path' must be an object")
    }

    fun requireObject(json: JSONObject, key: String, path: String = key): JSONObject =
        optObject(json, key, path) ?: invalid("'$path' is required")

    fun optArray(json: JSONObject, key: String, path: String = key): JSONArray? = when (val v = value(json, key)) {
        null -> null
        is JSONArray -> v
        else -> invalid("'$path' must be an array")
    }

    fun requireArray(json: JSONObject, key: String, path: String = key): JSONArray =
        optArray(json, key, path) ?: invalid("'$path' is required")

    fun optString(json: JSONObject, key: String, path: String = key): String? = when (val v = value(json, key)) {
        null -> null
        is String -> v
        else -> invalid("'$path' must be a string")
    }

    /** A required, non-blank string. */
    fun requireString(json: JSONObject, key: String, path: String = key): String {
        val v = optString(json, key, path) ?: invalid("'$path' is required")
        if (v.isBlank()) invalid("'$path' must not be empty")
        return v
    }

    fun optBoolean(json: JSONObject, key: String, path: String = key): Boolean? = when (val v = value(json, key)) {
        null -> null
        is Boolean -> v
        else -> invalid("'$path' must be a boolean")
    }

    fun requireBoolean(json: JSONObject, key: String, path: String = key): Boolean =
        optBoolean(json, key, path) ?: invalid("'$path' is required")

    /** A finite number. */
    fun optNumber(json: JSONObject, key: String, path: String = key): Double? = when (val v = value(json, key)) {
        null -> null
        is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
            ?: invalid("'$path' must be a finite number")
        else -> invalid("'$path' must be a number")
    }

    fun requireNumber(json: JSONObject, key: String, path: String = key): Double =
        optNumber(json, key, path) ?: invalid("'$path' is required")

    /** A number >= [min], truncated to whole milliseconds (or any other whole unit). */
    fun optLong(json: JSONObject, key: String, min: Long, path: String = key): Long? {
        val v = optNumber(json, key, path) ?: return null
        if (v < min) invalid("'$path' must be >= $min")
        if (v >= Long.MAX_VALUE.toDouble()) invalid("'$path' is too large")
        return v.toLong()
    }

    /** A whole number in [min, [Int.MAX_VALUE]]. */
    fun optInt(json: JSONObject, key: String, min: Int, path: String = key): Int? {
        val v = optNumber(json, key, path) ?: return null
        if (v != floor(v)) invalid("'$path' must be a whole number")
        if (v < min || v > Int.MAX_VALUE) invalid("'$path' must be between $min and ${Int.MAX_VALUE}")
        return v.toInt()
    }

    private fun optAccuracy(json: JSONObject): DesiredAccuracy? = optString(json, "desiredAccuracy")?.let {
        DesiredAccuracy.fromWire(it) ?: invalid("'desiredAccuracy' must be high, balanced, low or passive, got '$it'")
    }

    private fun checkRange(value: Double, range: ClosedFloatingPointRange<Double>, path: String) {
        if (value !in range) invalid("'$path' must be within ${range.start}..${range.endInclusive}, got $value")
    }

    private fun value(json: JSONObject, key: String): Any? = if (json.isNull(key)) null else json.opt(key)

    fun invalid(message: String): Nothing = throw TrackingException(ErrorCode.INVALID_ARGUMENT, message)
}
