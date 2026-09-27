package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.JsonUtil
import com.brickssoft.locationtracking.model.ProviderStateJson
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.model.TrackedLocationJson
import org.json.JSONObject

/**
 * Lossless persistence JSON for [RuntimeState] (not a JS shape). Keys use the Kotlin property names; times are
 * epoch ms. [fromJson] is lenient: a missing or invalid field falls back to its default.
 */
internal object RuntimeStateJson {
    private const val TAG = "LT.RuntimeStateJson"

    fun toJson(r: RuntimeState): JSONObject = JSONObject()
        .put("enabled", r.enabled)
        .put("trackingMode", r.trackingMode.wire)
        .put("isMoving", r.isMoving)
        .put("odometer", if (r.odometer.isFinite()) r.odometer else 0.0)
        .put("activity", JSONObject().put("type", r.activity.type.wire).put("confidence", r.activity.confidence))
        .put("lastLocation", r.lastLocation?.let { lastLocationToJson(it) } ?: JSONObject.NULL)
        .put("lastRecordAt", JsonUtil.orNull(r.lastRecordAt))
        .put("lastRecordElapsed", JsonUtil.orNull(r.lastRecordElapsed))
        .put("lastRecordBootCount", JsonUtil.orNull(r.lastRecordBootCount))
        .put("lastHeartbeatAt", JsonUtil.orNull(r.lastHeartbeatAt))
        .put("trackingStartedAt", JsonUtil.orNull(r.trackingStartedAt))
        .put("providerState", r.providerState?.let { ProviderStateJson.toJson(it) } ?: JSONObject.NULL)
        .put("didReady", r.didReady)

    fun fromJson(json: JSONObject): RuntimeState {
        val d = RuntimeState()
        return RuntimeState(
            enabled = JsonUtil.optBoolean(json, "enabled") ?: d.enabled,
            trackingMode = TrackingMode.fromWire(JsonUtil.optString(json, "trackingMode")) ?: d.trackingMode,
            isMoving = JsonUtil.optBoolean(json, "isMoving") ?: d.isMoving,
            odometer = JsonUtil.optDouble(json, "odometer") ?: d.odometer,
            activity = json.optJSONObject("activity")?.let { a ->
                ActivitySample(
                    type = ActivityType.fromWire(JsonUtil.optString(a, "type")) ?: ActivityType.UNKNOWN,
                    confidence = JsonUtil.optInt(a, "confidence") ?: 0,
                )
            } ?: d.activity,
            lastLocation = json.optJSONObject("lastLocation")?.let { l ->
                try {
                    TrackedLocationJson.fromJson(l)
                } catch (e: Exception) {
                    Logger.w(TAG, "dropping invalid persisted lastLocation", e)
                    null
                }
            },
            lastRecordAt = JsonUtil.optLong(json, "lastRecordAt"),
            lastRecordElapsed = JsonUtil.optLong(json, "lastRecordElapsed"),
            lastRecordBootCount = JsonUtil.optInt(json, "lastRecordBootCount"),
            lastHeartbeatAt = JsonUtil.optLong(json, "lastHeartbeatAt"),
            trackingStartedAt = JsonUtil.optLong(json, "trackingStartedAt"),
            providerState = json.optJSONObject("providerState")?.let { ProviderStateJson.fromJson(it) },
            didReady = JsonUtil.optBoolean(json, "didReady") ?: d.didReady,
        )
    }

    /** org.json rejects NaN/Infinity, so a location with non-finite coordinates is not persisted. */
    private fun lastLocationToJson(l: TrackedLocation): Any =
        if (l.latitude.isFinite() && l.longitude.isFinite() && (l.altitude?.isFinite() != false)) {
            TrackedLocationJson.toJson(l)
        } else {
            Logger.w(TAG, "not persisting lastLocation with non-finite coordinates")
            JSONObject.NULL
        }
}
