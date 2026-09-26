// STUB — owned by Unit 3 (Config + state). Replace this implementation.
package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.core.Iso8601
import org.json.JSONObject

/** Config <-> JS JSON (deep merge onto [parse]'s `base`), and `State` -> JS. */
object ConfigJson {
    /** Applies [json] on top of [base]. Stub: returns [base] unchanged. */
    fun parse(json: JSONObject, base: Config = Config()): Config = base

    /** Full JS `Config` with every default. Stub: empty object. */
    fun toJson(c: Config): JSONObject = JSONObject()

    /** JS `State`. Stub: runtime fields only, with an empty config. */
    fun stateToJson(s: State): JSONObject = JSONObject()
        .put("enabled", s.runtime.enabled)
        .put("trackingMode", s.runtime.trackingMode.wire)
        .put("isMoving", s.runtime.isMoving)
        .put("odometer", s.runtime.odometer)
        .put("backend", s.backend.wire)
        .put("lastRecordAt", Iso8601.formatOrNull(s.runtime.lastRecordAt) ?: JSONObject.NULL)
        .put("config", toJson(s.config))
}
