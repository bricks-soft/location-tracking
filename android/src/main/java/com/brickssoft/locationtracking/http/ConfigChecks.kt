package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.BatteryDetails
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.DeviceSnapshot
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.HeartbeatMeta
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import okhttp3.Headers

/**
 * Checks of a config the app gives (`ready()`, `setConfig()`, `reset()`) that [com.brickssoft.locationtracking.config.
 * ConfigValidator] cannot fix by clamping (recommendation 14). Mistakes that would otherwise show only at upload time
 * reject the call with `INVALID_ARGUMENT`, and nothing is applied:
 * - `http.url` (unless unset or blank): an http or https URL with a host, without user info (`user:password@`) and
 *   without a fragment (`#…`);
 * - `http.headers` and `http.authorization.refreshHeaders`: names and values that OkHttp accepts (ASCII, no line
 *   breaks). A rejected header is named; its value is never repeated;
 * - `http.locationTemplate` and `http.geofenceTemplate`: every `<%= name %>` placeholder is known, and the template
 *   renders to a valid JSON object or array ([StrictJson]) for a sample record with every field set and for one with
 *   none set.
 *
 * [warnings] lists what is accepted but probably wrong: an `http://` upload URL (Android 9+ blocks cleartext traffic
 * unless the app's network security config allows that host).
 */
internal object ConfigChecks {
    /** Throws `INVALID_ARGUMENT` for the first mistake found; returns [warnings] otherwise. */
    fun check(config: Config): List<String> {
        val http = config.http
        checkUrl(http)
        checkHeaders("http.headers", http.headers)
        http.authorization?.let { checkHeaders("http.authorization.refreshHeaders", it.refreshHeaders) }
        checkTemplate("http.locationTemplate", http.locationTemplate)
        checkTemplate("http.geofenceTemplate", http.geofenceTemplate)
        return warnings(config)
    }

    fun warnings(config: Config): List<String> {
        val url = HttpSupport.parseUrl(config.http.url) ?: return emptyList()
        if (url.scheme != "http") return emptyList()
        return listOf(
            "http.url uses http://: Android 9+ blocks cleartext traffic unless the app's network security config " +
                "allows ${url.host}; uploads would fail until then",
        )
    }

    private fun checkUrl(http: HttpConfig) {
        val text = http.url?.takeIf { it.isNotBlank() } ?: return
        val url = HttpSupport.parseUrl(text) ?: invalid("http.url must be an http(s) URL with a host")
        if (url.host.isEmpty()) invalid("http.url must have a host")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            invalid("http.url must not contain user info (user:password@); use http.headers")
        }
        if (url.fragment != null) invalid("http.url must not contain a fragment (#...)")
    }

    private fun checkHeaders(path: String, headers: Map<String, String>) {
        for ((name, value) in headers) {
            try {
                Headers.Builder().add(name, value)
            } catch (e: IllegalArgumentException) {
                // Never repeat the value: it may be a credential.
                invalid("$path: header '${name.take(MAX_ECHO)}' has an invalid name or value (ASCII only, no line breaks)")
            }
        }
    }

    private fun checkTemplate(path: String, template: String?) {
        if (template.isNullOrBlank()) return
        val unknown = TemplateRenderer.placeholderNames(template) - TemplateRenderer.PLACEHOLDERS
        if (unknown.isNotEmpty()) {
            invalid("$path has unknown placeholder(s) ${unknown.joinToString { "'${it.take(MAX_ECHO)}'" }}")
        }
        for (sample in SAMPLES) {
            val text = TemplateRenderer.renderText(template, sample, sample.recordedAt)
            if (!StrictJson.isValid(text) || !(text.trimStart().startsWith("{") || text.trimStart().startsWith("["))) {
                invalid("$path does not render to a JSON object or array (check the quotes around string placeholders)")
            }
        }
    }

    private fun invalid(message: String): Nothing = throw TrackingException(ErrorCode.INVALID_ARGUMENT, "config.$message")

    private const val MAX_ECHO = 40
    private const val SAMPLE_TIME = 1_790_000_000_000L

    /** A record with every field set, and one with none (no location, no geofence, no provider). */
    private val SAMPLES: List<Record> by lazy {
        val full = Record(
            uuid = "00000000-0000-4000-8000-000000000000",
            event = RecordEvent.GEOFENCE,
            location = TrackedLocation(
                latitude = 52.5, longitude = 13.4, accuracy = 5f, altitude = 34.0, altitudeAccuracy = 3f, speed = 1.5f,
                speedAccuracy = 0.5f, heading = 90f, headingAccuracy = 10f, time = SAMPLE_TIME, isMock = false,
            ),
            recordedAt = SAMPLE_TIME,
            elapsedRealtimeMs = 1_000L,
            bootCount = 1,
            isMoving = true,
            odometer = 10.0,
            activity = ActivitySample(ActivityType.WALKING, 80),
            battery = BatterySnapshot(0.5f, false),
            backend = ProviderKind.GMS,
            extras = """{"k":"v"}""",
            geofence = GeofenceHit("id", GeofenceAction.ENTER, """{"k":"v"}"""),
            provider = ProviderState(true, true, true, PermissionLevel.ALWAYS, AccuracyLevel.PRECISE, ProviderKind.GMS),
            reason = "start",
            heartbeat = HeartbeatMeta(HeartbeatStrategy.EXACT, 180, 300, SAMPLE_TIME, true, false),
            stoppedAt = SAMPLE_TIME,
            network = ConnectivityType.WIFI,
            device = DeviceSnapshot(
                backgroundRestricted = false, standbyBucket = "active", diskFreeBytes = 1L, thermalStatus = "none",
                manufacturer = "m", model = "m", osVersion = "14", sdkInt = 34, deviceId = "a", lowRamDevice = false,
                ramTotalBytes = 1L,
            ),
            batteryDetails = BatteryDetails(30f, "good", "ac"),
        )
        val empty = Record(
            uuid = full.uuid, event = RecordEvent.HEARTBEAT, location = null, recordedAt = SAMPLE_TIME,
            elapsedRealtimeMs = 1_000L, bootCount = -1, isMoving = false, odometer = 0.0,
            activity = ActivitySample.UNKNOWN, battery = BatterySnapshot.UNKNOWN, backend = null,
        )
        listOf(full, empty)
    }
}
