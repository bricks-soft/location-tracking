package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.JsonUtil
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Renders `http.locationTemplate` / `http.geofenceTemplate` (architecture §2).
 *
 * Each `<%= name %>` placeholder (surrounding whitespace allowed) is replaced by a raw JSON literal: numbers and
 * booleans bare, null as `null`, strings JSON-escaped but without quotes (templates write `"<%= uuid %>"`), and
 * `extras` as JSON object text (`{}` when the record has none), and `record` as the default record object (with
 * `sent_at`), so `"raw": <%= record %>` keeps every field. A null value in a placeholder that is exactly wrapped
 * in quotes (`"<%= timestamp %>"`) replaces the quotes too, so it becomes JSON `null` rather than the string "null".
 * An unknown placeholder becomes "" and logs a warning.
 */
internal object TemplateRenderer {
    private const val TAG = "LT.Template"
    private const val NULL = "null"

    /** A placeholder, with the quote directly before and after it when present. */
    private val PLACEHOLDER = Regex("""("?)<%=\s*(.*?)\s*%>("?)""", RegexOption.DOT_MATCHES_ALL)

    /** JSON literal text per placeholder; a resolver returns null for a null value. */
    private val RESOLVERS: Map<String, (Record, Long) -> String?> = linkedMapOf(
        "uuid" to { r, _ -> string(r.uuid) },
        "event" to { r, _ -> string(r.event.wire) },
        "timestamp" to { r, _ -> r.location?.let { string(Iso8601.format(it.time)) } },
        "recorded_at" to { r, _ -> string(Iso8601.format(r.recordedAt)) },
        "sent_at" to { _, sentAt -> string(Iso8601.format(sentAt)) },
        "latitude" to { r, _ -> number(r.location?.latitude) },
        "longitude" to { r, _ -> number(r.location?.longitude) },
        "accuracy" to { r, _ -> number(r.location?.accuracy) },
        "altitude" to { r, _ -> number(r.location?.altitude) },
        "altitude_accuracy" to { r, _ -> number(r.location?.altitudeAccuracy) },
        "speed" to { r, _ -> number(r.location?.speed) },
        "speed_accuracy" to { r, _ -> number(r.location?.speedAccuracy) },
        "heading" to { r, _ -> number(r.location?.heading) },
        "heading_accuracy" to { r, _ -> number(r.location?.headingAccuracy) },
        "is_moving" to { r, _ -> r.isMoving.toString() },
        "odometer" to { r, _ -> number(r.odometer) },
        "mock" to { r, _ -> (r.location?.isMock ?: false).toString() },
        "activity.type" to { r, _ -> string(r.activity.type.wire) },
        "activity.confidence" to { r, _ -> r.activity.confidence.toString() },
        "battery.level" to { r, _ -> number(r.battery.level) },
        "battery.is_charging" to { r, _ -> r.battery.isCharging.toString() },
        "elapsed_realtime_ms" to { r, _ -> r.elapsedRealtimeMs.toString() },
        "boot_count" to { r, _ -> r.bootCount.toString() },
        "backend" to { r, _ -> r.backend?.let { string(it.wire) } },
        "reason" to { r, _ -> r.reason?.let { string(it) } },
        "geofence.identifier" to { r, _ -> r.geofence?.let { string(it.identifier) } },
        "geofence.action" to { r, _ -> r.geofence?.let { string(it.action.name) } },
        "provider.enabled" to { r, _ -> r.provider?.enabled?.toString() },
        "provider.gps" to { r, _ -> r.provider?.gps?.toString() },
        "provider.network" to { r, _ -> r.provider?.network?.toString() },
        "provider.permission" to { r, _ -> r.provider?.let { string(it.permission.wire) } },
        "extras" to { r, _ -> JsonUtil.parseObject(r.extras)?.toString() ?: "{}" },
        "record" to { r, sentAt -> RecordJson.toJson(r, sentAt).toString() },
    )

    /** Every supported placeholder name. */
    val PLACEHOLDERS: Set<String> get() = RESOLVERS.keys

    /**
     * The template for [record]: `geofenceTemplate` for geofence records (falling back to `locationTemplate`),
     * otherwise `locationTemplate`. Blank templates count as absent; null means the default shape.
     */
    fun templateFor(record: Record, http: HttpConfig): String? {
        val location = http.locationTemplate?.takeIf { it.isNotBlank() }
        if (record.event == RecordEvent.GEOFENCE) return http.geofenceTemplate?.takeIf { it.isNotBlank() } ?: location
        return location
    }

    /**
     * Renders [template] for [record] and parses the result. Returns a [JSONObject] or [JSONArray], or null (and
     * logs an error) if the rendered text is not a valid JSON object or array; callers then use the default shape.
     */
    fun render(template: String, record: Record, sentAt: Long): Any? {
        val text = renderText(template, record, sentAt)
        val value = if (StrictJson.isValid(text)) parse(text) else null
        if (value == null) {
            Logger.e(
                TAG,
                "template for ${record.event.wire} record ${record.uuid} did not render to a JSON object or array; " +
                    "using the default shape. Template: ${template.take(200)}",
            )
        }
        return value
    }

    /** Substitutes every placeholder of [template] (no JSON validation). */
    fun renderText(template: String, record: Record, sentAt: Long): String {
        val unknown = LinkedHashSet<String>()
        val text = PLACEHOLDER.replace(template) { match ->
            val (open, name, close) = match.destructured
            val resolver = RESOLVERS[name]
            if (resolver == null) {
                unknown += name
                return@replace open + close
            }
            val literal = resolver(record, sentAt)
            when {
                literal != null -> open + literal + close
                open.isNotEmpty() && close.isNotEmpty() -> NULL
                else -> open + NULL + close
            }
        }
        if (unknown.isNotEmpty()) {
            Logger.w(TAG, "unknown template placeholder(s) ${unknown.joinToString { "'$it'" }} replaced by \"\"")
        }
        return text
    }

    /** The JSON literal for placeholder [name] (`null` for a null value), or null if the name is unknown. */
    fun literal(name: String, record: Record, sentAt: Long): String? {
        val resolver = RESOLVERS[name] ?: return null
        return resolver(record, sentAt) ?: NULL
    }

    private fun parse(text: String): Any? = try {
        JSONTokener(text).nextValue().takeIf { it is JSONObject || it is JSONArray }
    } catch (e: Exception) {
        // e.g. a number out of range (org.json rejects infinities)
        null
    }

    /** JSON string-content escaping, without the surrounding quotes. */
    private fun string(value: String): String = JSONObject.quote(value).let { it.substring(1, it.length - 1) }

    private fun number(value: Double?): String? = value?.takeIf { !it.isNaN() && !it.isInfinite() }?.toString()

    private fun number(value: Float?): String? = number(value?.let { JsonUtil.floatToJson(it) })
}
