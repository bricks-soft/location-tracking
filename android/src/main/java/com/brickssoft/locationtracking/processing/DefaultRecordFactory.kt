package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.JsonUtil
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.ProviderFactory
import org.json.JSONObject
import java.util.UUID

/**
 * Builds records: uuid v4, clock and boot metadata, runtime state (isMoving, odometer, activity), battery
 * from [device], backend from [providers], and `extras` = `persistence.extras` merged with the per-call
 * extras (per-call keys win; null when both are empty).
 */
class DefaultRecordFactory(
    private val configStore: ConfigStore,
    private val device: DeviceMonitor,
    private val providers: ProviderFactory,
    private val clock: Clock,
) : RecordFactory {
    override fun create(
        event: RecordEvent,
        location: TrackedLocation?,
        extras: String?,
        geofence: GeofenceHit?,
        provider: ProviderState?,
        reason: String?,
    ): Record {
        val runtime = configStore.runtime.value
        return Record(
            uuid = UUID.randomUUID().toString(),
            event = event,
            location = location,
            recordedAt = clock.now(),
            elapsedRealtimeMs = clock.elapsedRealtime(),
            bootCount = clock.bootCount(),
            isMoving = runtime.isMoving,
            odometer = runtime.odometer,
            activity = runtime.activity,
            battery = battery(),
            backend = backend(),
            extras = mergeExtras(configStore.config.value.persistence.extras, extras),
            geofence = geofence,
            provider = provider,
            reason = reason,
        )
    }

    /**
     * Parses a JS `InsertLocationInput`: `{coords:{latitude, longitude, accuracy?, altitude?, ...}, timestamp?,
     * event?, is_moving?, extras?}`. `accuracy` defaults to 0; negative optional accuracies, speed and heading
     * (the "unknown" sentinel of some platforms) become null.
     *
     * @throws TrackingException INVALID_ARGUMENT if coords, latitude or longitude are missing or invalid, if
     *   accuracy is negative, or if `timestamp`, `event`, `is_moving` or `extras` are given but invalid.
     */
    override fun fromExternal(input: JSONObject): Record {
        val coords = input.optJSONObject("coords") ?: invalid("location.coords is required")
        val latitude = JsonUtil.optDouble(coords, "latitude") ?: invalid("coords.latitude is required")
        val longitude = JsonUtil.optDouble(coords, "longitude") ?: invalid("coords.longitude is required")
        if (!Geo.isValid(latitude, longitude)) invalid("coords ($latitude, $longitude) are out of range")

        val time = if (input.isNull("timestamp")) {
            clock.now()
        } else {
            val text = JsonUtil.optString(input, "timestamp")
            Iso8601.parse(text) ?: invalid("timestamp '$text' is not an ISO-8601 date")
        }
        val event = if (input.isNull("event")) {
            RecordEvent.LOCATION
        } else {
            val wire = JsonUtil.optString(input, "event")
            RecordEvent.fromWire(wire) ?: invalid("unknown event '$wire'")
        }
        val isMoving = if (input.isNull("is_moving")) {
            null
        } else {
            JsonUtil.optBoolean(input, "is_moving") ?: invalid("is_moving must be a boolean")
        }
        val accuracy = JsonUtil.optFloat(coords, "accuracy") ?: 0f
        if (accuracy < 0) invalid("coords.accuracy must be >= 0")
        val extras = if (input.isNull("extras")) {
            null
        } else {
            input.optJSONObject("extras")?.toString() ?: invalid("extras must be an object")
        }
        val location = TrackedLocation(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = JsonUtil.optDouble(coords, "altitude"),
            altitudeAccuracy = nonNegative(coords, "altitude_accuracy"),
            speed = nonNegative(coords, "speed"),
            speedAccuracy = nonNegative(coords, "speed_accuracy"),
            heading = nonNegative(coords, "heading"),
            headingAccuracy = nonNegative(coords, "heading_accuracy"),
            time = time,
        )
        val record = create(event, location, extras)
        return if (isMoving == null) record else record.copy(isMoving = isMoving)
    }

    private fun nonNegative(json: JSONObject, key: String): Float? = JsonUtil.optFloat(json, key)?.takeIf { it >= 0 }

    private fun battery(): BatterySnapshot = try {
        device.battery()
    } catch (e: Exception) {
        Logger.w(TAG, "battery unavailable", e)
        BatterySnapshot.UNKNOWN
    }

    private fun backend(): ProviderKind? = try {
        providers.kind
    } catch (e: Exception) {
        Logger.w(TAG, "backend unavailable", e)
        null
    }

    private fun invalid(message: String): Nothing = throw TrackingException(ErrorCode.INVALID_ARGUMENT, message)

    internal companion object {
        private const val TAG = "LT.RecordFactory"

        /**
         * Shallow merge of two JSON object texts; keys of [perCall] win over [base]. Invalid text is logged
         * and ignored. Returns null when the result has no keys.
         */
        fun mergeExtras(base: String?, perCall: String?): String? {
            val merged = parseExtras(base, "persistence.extras") ?: JSONObject()
            parseExtras(perCall, "extras")?.let { overrides ->
                val keys = overrides.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    merged.put(key, overrides.opt(key))
                }
            }
            return if (merged.length() == 0) null else merged.toString()
        }

        private fun parseExtras(text: String?, name: String): JSONObject? {
            if (text.isNullOrBlank()) return null
            return JsonUtil.parseObject(text).also { if (it == null) Logger.w(TAG, "ignoring $name: not a JSON object") }
        }
    }
}
