package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.JsonUtil
import com.brickssoft.locationtracking.model.LocationProviderSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [ConfigStore]. Tests usually set [configFlow] / [runtimeFlow] directly or call [update].
 *
 * The JSON methods apply only a minimal set of keys (see [applyJson]); they do not validate or reset nulls.
 * `ready(json, reset=false)` applies [json] only while `runtime.didReady` is false. [calls] records
 * "ready(reset=true|false)", "merge", "reset", "update" and "updateRuntime".
 */
class FakeConfigStore(
    initial: Config = Config(),
    runtime: RuntimeState = RuntimeState(),
) : ConfigStore {
    val configFlow = MutableStateFlow(initial)
    val runtimeFlow = MutableStateFlow(runtime)
    val calls = CopyOnWriteArrayList<String>()

    override val config: StateFlow<Config> get() = configFlow
    override val runtime: StateFlow<RuntimeState> get() = runtimeFlow

    override fun ready(json: JSONObject?, reset: Boolean): Config {
        calls += "ready(reset=$reset)"
        return when {
            reset -> configFlow.updateAndGet { applyJson(Config(), json) }
            !runtimeFlow.value.didReady -> configFlow.updateAndGet { applyJson(it, json) }
            else -> configFlow.value
        }
    }

    override fun merge(json: JSONObject): Config {
        calls += "merge"
        return configFlow.updateAndGet { applyJson(it, json) }
    }

    override fun reset(json: JSONObject?): Config {
        calls += "reset"
        return configFlow.updateAndGet { applyJson(Config(), json) }
    }

    override fun update(transform: (Config) -> Config): Config {
        calls += "update"
        return configFlow.updateAndGet(transform)
    }

    override fun updateRuntime(transform: (RuntimeState) -> RuntimeState): RuntimeState {
        calls += "updateRuntime"
        return runtimeFlow.updateAndGet(transform)
    }

    companion object {
        /**
         * Applies the keys that tests commonly need: most scalar fields of geolocation, activity, heartbeat,
         * http, persistence, app, geofence, logger, plus `locationProvider`. Unknown keys are ignored.
         */
        fun applyJson(base: Config, json: JSONObject?): Config {
            if (json == null) return base
            var c = base
            json.optJSONObject("geolocation")?.let { j ->
                val g = c.geolocation
                c = c.copy(
                    geolocation = g.copy(
                        desiredAccuracy = DesiredAccuracy.fromWire(JsonUtil.optString(j, "desiredAccuracy"))
                            ?: g.desiredAccuracy,
                        distanceFilter = JsonUtil.optDouble(j, "distanceFilter") ?: g.distanceFilter,
                        locationUpdateInterval = JsonUtil.optLong(j, "locationUpdateInterval") ?: g.locationUpdateInterval,
                        fastestLocationUpdateInterval = JsonUtil.optLong(j, "fastestLocationUpdateInterval")
                            ?: g.fastestLocationUpdateInterval,
                        disableElasticity = JsonUtil.optBoolean(j, "disableElasticity") ?: g.disableElasticity,
                        elasticityMultiplier = JsonUtil.optDouble(j, "elasticityMultiplier") ?: g.elasticityMultiplier,
                        stationaryRadius = JsonUtil.optDouble(j, "stationaryRadius") ?: g.stationaryRadius,
                        stopTimeout = JsonUtil.optInt(j, "stopTimeout") ?: g.stopTimeout,
                        stopAfterElapsedMinutes = JsonUtil.optInt(j, "stopAfterElapsedMinutes") ?: g.stopAfterElapsedMinutes,
                        stopOnStationary = JsonUtil.optBoolean(j, "stopOnStationary") ?: g.stopOnStationary,
                        locationTimeout = JsonUtil.optLong(j, "locationTimeout") ?: g.locationTimeout,
                    ),
                )
            }
            json.optJSONObject("activity")?.let { j ->
                val a = c.activity
                c = c.copy(
                    activity = a.copy(
                        disableMotionActivityUpdates = JsonUtil.optBoolean(j, "disableMotionActivityUpdates")
                            ?: a.disableMotionActivityUpdates,
                        activityRecognitionInterval = JsonUtil.optLong(j, "activityRecognitionInterval")
                            ?: a.activityRecognitionInterval,
                        minimumActivityRecognitionConfidence = JsonUtil.optInt(j, "minimumActivityRecognitionConfidence")
                            ?: a.minimumActivityRecognitionConfidence,
                        motionTriggerDelay = JsonUtil.optLong(j, "motionTriggerDelay") ?: a.motionTriggerDelay,
                        disableStopDetection = JsonUtil.optBoolean(j, "disableStopDetection") ?: a.disableStopDetection,
                    ),
                )
            }
            json.optJSONObject("heartbeat")?.let { j ->
                val h = c.heartbeat
                c = c.copy(
                    heartbeat = h.copy(
                        enabled = JsonUtil.optBoolean(j, "enabled") ?: h.enabled,
                        minInterval = JsonUtil.optInt(j, "minInterval") ?: h.minInterval,
                        maxInterval = JsonUtil.optInt(j, "maxInterval") ?: h.maxInterval,
                    ),
                )
            }
            json.optJSONObject("http")?.let { j ->
                val h = c.http
                c = c.copy(
                    http = h.copy(
                        url = if (j.has("url")) JsonUtil.optString(j, "url") else h.url,
                        autoSync = JsonUtil.optBoolean(j, "autoSync") ?: h.autoSync,
                        autoSyncThreshold = JsonUtil.optInt(j, "autoSyncThreshold") ?: h.autoSyncThreshold,
                        syncInterval = JsonUtil.optInt(j, "syncInterval") ?: h.syncInterval,
                        batchSync = JsonUtil.optBoolean(j, "batchSync") ?: h.batchSync,
                        maxBatchSize = JsonUtil.optInt(j, "maxBatchSize") ?: h.maxBatchSize,
                        disableAutoSyncOnCellular = JsonUtil.optBoolean(j, "disableAutoSyncOnCellular")
                            ?: h.disableAutoSyncOnCellular,
                        rootProperty = JsonUtil.optString(j, "rootProperty") ?: h.rootProperty,
                        timeout = JsonUtil.optLong(j, "timeout") ?: h.timeout,
                    ),
                )
            }
            json.optJSONObject("persistence")?.let { j ->
                val p = c.persistence
                c = c.copy(
                    persistence = p.copy(
                        maxDaysToPersist = JsonUtil.optInt(j, "maxDaysToPersist") ?: p.maxDaysToPersist,
                        maxRecordsToPersist = JsonUtil.optInt(j, "maxRecordsToPersist") ?: p.maxRecordsToPersist,
                        extras = j.optJSONObject("extras")?.toString() ?: p.extras,
                    ),
                )
            }
            json.optJSONObject("app")?.let { j ->
                c = c.copy(
                    app = c.app.copy(
                        stopOnTerminate = JsonUtil.optBoolean(j, "stopOnTerminate") ?: c.app.stopOnTerminate,
                        startOnBoot = JsonUtil.optBoolean(j, "startOnBoot") ?: c.app.startOnBoot,
                    ),
                )
            }
            json.optJSONObject("geofence")?.let { j ->
                c = c.copy(
                    geofence = c.geofence.copy(
                        initialTriggerEntry = JsonUtil.optBoolean(j, "initialTriggerEntry") ?: c.geofence.initialTriggerEntry,
                    ),
                )
            }
            json.optJSONObject("logger")?.let { j ->
                c = c.copy(
                    logger = c.logger.copy(
                        logLevel = LogLevel.fromWire(JsonUtil.optString(j, "logLevel")) ?: c.logger.logLevel,
                        logMaxDays = JsonUtil.optInt(j, "logMaxDays") ?: c.logger.logMaxDays,
                    ),
                )
            }
            LocationProviderSetting.fromWire(JsonUtil.optString(json, "locationProvider"))?.let {
                c = c.copy(locationProvider = it)
            }
            return c
        }
    }
}
