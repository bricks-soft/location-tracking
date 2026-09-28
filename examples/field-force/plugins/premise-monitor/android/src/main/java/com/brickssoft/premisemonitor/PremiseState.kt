package com.brickssoft.premisemonitor

import android.content.Context
import android.content.SharedPreferences
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** A circular premise: the JS `Premise` `{id, name?, latitude, longitude, radius}` (radius in meters). */
internal data class Premise(
    val id: String,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
) {
    /** Identifier of the tracking plugin geofence that watches this premise. */
    val geofenceId: String get() = GEOFENCE_PREFIX + id

    fun toJson(): JSONObject {
        val json = JSONObject().put("id", id)
        if (name != null) json.put("name", name)
        return json.put("latitude", latitude).put("longitude", longitude).put("radius", radius)
    }

    /** The JS `Geofence` passed to `LocationTrackingNative.addGeofence`: a circle with ENTER and EXIT. */
    fun toGeofenceJson(): JSONObject = JSONObject()
        .put("identifier", geofenceId)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("radius", radius)
        .put("notifyOnEntry", true)
        .put("notifyOnExit", true)
        .put("notifyOnDwell", false)
        .put("extras", JSONObject().put("premise_id", id))

    /** Same identifier and circle (the display name may differ): the same geofence. */
    fun sameArea(other: Premise): Boolean =
        id == other.id && latitude == other.latitude && longitude == other.longitude && radius == other.radius

    /** Distance of [fix] from the centre, in meters. */
    fun distanceTo(fix: Fix): Double = Geo.distanceMeters(latitude, longitude, fix.latitude, fix.longitude)

    /**
     * True when the whole accuracy circle of [fix] lies outside the premise: `distance − accuracy > radius`
     * ([distance] = [distanceTo], passed in when the caller already has it).
     */
    fun isCertainlyOutside(fix: Fix, distance: Double = distanceTo(fix)): Boolean = distance - fix.accuracy > radius

    companion object {
        const val GEOFENCE_PREFIX = "premise:"

        /** The tracking plugin accepts geofence identifiers of up to 100 characters, prefix included. */
        const val MAX_ID_LENGTH = 100 - GEOFENCE_PREFIX.length

        /** Parses and validates a JS `Premise`. @throws TrackingException INVALID_ARGUMENT */
        fun fromJson(json: JSONObject): Premise {
            val id = (json.opt("id") as? String)?.trim()
            if (id.isNullOrEmpty()) invalid("premise.id must be a non-empty string")
            if (id.length > MAX_ID_LENGTH) invalid("premise.id is longer than $MAX_ID_LENGTH characters")
            val latitude = number(json, "latitude")
            val longitude = number(json, "longitude")
            val radius = number(json, "radius")
            if (latitude !in -90.0..90.0) invalid("premise.latitude must be between -90 and 90")
            if (longitude !in -180.0..180.0) invalid("premise.longitude must be between -180 and 180")
            if (radius <= 0.0) invalid("premise.radius must be > 0")
            val name = (json.opt("name") as? String)?.takeIf { it.isNotBlank() }
            return Premise(id, name, latitude, longitude, radius)
        }

        /** The stored form (written by [toJson]); null if missing or unreadable. */
        fun fromStored(text: String?): Premise? {
            if (text.isNullOrBlank()) return null
            return try {
                fromJson(JSONObject(text))
            } catch (e: Exception) {
                PmLog.w(TAG, "stored premise unreadable; ignored", e)
                null
            }
        }

        private fun number(json: JSONObject, key: String): Double {
            val value = (json.opt(key) as? Number)?.toDouble()
            if (value == null || !value.isFinite()) invalid("premise.$key must be a number")
            return value
        }

        private const val TAG = "PM.State"
    }
}

/**
 * `auditUrl` of `startMonitoring`: null or blank = no uploads; otherwise an absolute http(s) URL.
 *
 * @throws TrackingException INVALID_ARGUMENT
 */
internal fun normalizeAuditUrl(url: String?): String? {
    val value = url?.trim()
    if (value.isNullOrEmpty()) return null
    val uri = try {
        URI(value)
    } catch (e: Exception) {
        invalid("auditUrl is not a valid URL: $value")
    }
    val scheme = uri.scheme?.lowercase()
    if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) {
        invalid("auditUrl must be an absolute http(s) URL: $value")
    }
    return value
}

private fun invalid(message: String): Nothing = throw TrackingException(ErrorCode.INVALID_ARGUMENT, message)

/**
 * Monitoring state in SharedPreferences `pm_state`, so a new process (restore, boot) continues where the previous one
 * stopped. Written with `commit()` from the `PM-native` thread only, through one instance per process (the core's).
 *
 * - `inside`: true / false, or absent (unknown: no transition seen since monitoring started or tracking stopped).
 * - `entered_at`: epoch ms of the ENTER that made `inside` true; fixes older than it are not presence evidence.
 * - `device_id`: random id sent with every upload, created on first use and kept for the app's lifetime.
 */
internal class PremiseState(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The parsed [premise]; this instance is the only writer in its process, so it is parsed once. */
    @Volatile
    private var cachedPremise: Premise? = null

    @Volatile
    private var premiseLoaded = false

    val monitoring: Boolean get() = prefs.getBoolean(KEY_MONITORING, false)

    val premise: Premise?
        get() {
            if (!premiseLoaded) {
                cachedPremise = Premise.fromStored(prefs.getString(KEY_PREMISE, null))
                premiseLoaded = true
            }
            return cachedPremise
        }

    val auditUrl: String? get() = prefs.getString(KEY_AUDIT_URL, null)

    val inside: Boolean? get() = if (prefs.contains(KEY_INSIDE)) prefs.getBoolean(KEY_INSIDE, false) else null

    val enteredAt: Long get() = prefs.getLong(KEY_ENTERED_AT, 0L)

    /** Creates the id on first use. */
    val deviceId: String
        @Synchronized get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).commit()
            return id
        }

    /** Monitoring [premise] from now on; `inside` becomes unknown. */
    fun startMonitoring(premise: Premise, auditUrl: String?) {
        prefs.edit()
            .putBoolean(KEY_MONITORING, true)
            .putString(KEY_PREMISE, premise.toJson().toString())
            .putString(KEY_AUDIT_URL, auditUrl)
            .remove(KEY_INSIDE)
            .remove(KEY_ENTERED_AT)
            .commit()
        remember(premise)
    }

    /** Replaces the stored premise (same area, new name) without touching `inside`. */
    fun updatePremise(premise: Premise) {
        prefs.edit().putString(KEY_PREMISE, premise.toJson().toString()).commit()
        remember(premise)
    }

    /** Not monitoring; the premise and `inside` are cleared. The audit URL stays, so pending entries still upload. */
    fun stopMonitoring() {
        prefs.edit()
            .putBoolean(KEY_MONITORING, false)
            .remove(KEY_PREMISE)
            .remove(KEY_INSIDE)
            .remove(KEY_ENTERED_AT)
            .commit()
        remember(null)
    }

    private fun remember(premise: Premise?) {
        cachedPremise = premise
        premiseLoaded = true
    }

    fun setAuditUrl(url: String?) {
        prefs.edit().putString(KEY_AUDIT_URL, url).commit()
    }

    /** [inside] null = unknown. [enteredAt] is kept only while inside. */
    fun setInside(inside: Boolean?, enteredAt: Long = 0L) {
        val editor = prefs.edit()
        if (inside == null) editor.remove(KEY_INSIDE) else editor.putBoolean(KEY_INSIDE, inside)
        if (inside == true) editor.putLong(KEY_ENTERED_AT, enteredAt) else editor.remove(KEY_ENTERED_AT)
        editor.commit()
    }

    internal companion object {
        const val PREFS_NAME = "pm_state"
        private const val KEY_MONITORING = "monitoring"
        private const val KEY_PREMISE = "premise"
        private const val KEY_AUDIT_URL = "audit_url"
        private const val KEY_INSIDE = "inside"
        private const val KEY_ENTERED_AT = "entered_at"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
