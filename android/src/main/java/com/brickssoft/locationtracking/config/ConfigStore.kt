package com.brickssoft.locationtracking.config

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Persistent config and runtime state (implemented by `SharedPrefsConfigStore`, prefs file
 * `location_tracking_prefs`, loaded synchronously in the constructor). All methods are thread-safe and
 * persist before returning.
 */
interface ConfigStore {
    val config: StateFlow<Config>
    val runtime: StateFlow<RuntimeState>

    /**
     * `ready()`: with [reset] true, config = defaults + [json]; with false, [json] is applied only on the very
     * first ready (runtime.didReady false), otherwise the persisted config wins.
     */
    fun ready(json: JSONObject?, reset: Boolean): Config

    /** `setConfig()`: deep merge; arrays replaced; null resets the key to its default. */
    fun merge(json: JSONObject): Config

    /** `reset()`: defaults + [json]. */
    fun reset(json: JSONObject?): Config

    /** Internal writers (e.g. JWT refresh). */
    fun update(transform: (Config) -> Config): Config

    fun updateRuntime(transform: (RuntimeState) -> RuntimeState): RuntimeState
}
