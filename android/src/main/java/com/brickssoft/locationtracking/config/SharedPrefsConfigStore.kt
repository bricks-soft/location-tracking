package com.brickssoft.locationtracking.config

import android.content.Context
import android.content.SharedPreferences
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [ConfigStore] backed by the SharedPreferences file [Constants.PREFS_NAME].
 *
 * Config and runtime state are loaded synchronously in the constructor. An unreadable stored value falls back to
 * its default; unreadable JSON text falls back to all defaults.
 *
 * Every write runs under one lock: it computes the new value, persists it with `apply()` and publishes it on the
 * matching [StateFlow] before returning. Config writes go through [ConfigJson.parse] and [ConfigValidator].
 * Only the `cfg_` keys of the shared prefs file are touched.
 */
class SharedPrefsConfigStore(
    context: Context,
    @Suppress("unused") private val clock: Clock,
) : ConfigStore {
    private val prefs: SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val configFlow = MutableStateFlow(loadConfig())
    private val runtimeFlow = MutableStateFlow(loadRuntime())

    override val config: StateFlow<Config> = configFlow.asStateFlow()
    override val runtime: StateFlow<RuntimeState> = runtimeFlow.asStateFlow()

    override fun ready(json: JSONObject?, reset: Boolean): Config = synchronized(lock) {
        val current = configFlow.value
        val next = when {
            reset -> build(json, Config())
            !runtimeFlow.value.didReady -> build(json, current)
            else -> {
                if (json != null && json.length() > 0) {
                    Logger.i(TAG, "ready(reset=false): keeping the persisted config; the given config is ignored")
                }
                current
            }
        }
        val runtime = runtimeFlow.value.copy(didReady = true)
        persist(next.takeIf { it != current }, runtime.takeIf { it != runtimeFlow.value })
        configFlow.value = next
        runtimeFlow.value = runtime
        next
    }

    override fun merge(json: JSONObject): Config = setConfig { build(json, it) }

    override fun reset(json: JSONObject?): Config = setConfig { build(json, Config()) }

    override fun update(transform: (Config) -> Config): Config = setConfig { ConfigValidator.validate(transform(it)) }

    override fun updateRuntime(transform: (RuntimeState) -> RuntimeState): RuntimeState = synchronized(lock) {
        val current = runtimeFlow.value
        val next = transform(current)
        if (next != current) {
            persist(null, next)
            runtimeFlow.value = next
        }
        next
    }

    private inline fun setConfig(compute: (Config) -> Config): Config = synchronized(lock) {
        val current = configFlow.value
        val next = compute(current)
        if (next != current) {
            persist(next, null)
            configFlow.value = next
        }
        next
    }

    /** Defaults-or-[base] plus [json], clamped. Throws INVALID_ARGUMENT (before anything changes) on bad input. */
    private fun build(json: JSONObject?, base: Config): Config =
        ConfigValidator.validate(if (json == null) base else ConfigJson.parse(json, base))

    /** Writes the non-null values in one `apply()`. A value that cannot be serialized is logged and skipped. */
    private fun persist(config: Config?, runtime: RuntimeState?) {
        if (config == null && runtime == null) return
        val editor = prefs.edit()
        if (config != null) {
            serialize("config") { ConfigJson.toJson(config) }?.let { editor.putString(KEY_CONFIG, it) }
        }
        if (runtime != null) {
            serialize("runtime state") { RuntimeStateJson.toJson(runtime) }?.let { editor.putString(KEY_RUNTIME, it) }
        }
        editor.apply()
    }

    private inline fun serialize(what: String, toJson: () -> JSONObject): String? = try {
        toJson().toString()
    } catch (e: Exception) {
        Logger.e(TAG, "cannot persist $what", e)
        null
    }

    private fun loadConfig(): Config {
        val text = prefs.getString(KEY_CONFIG, null) ?: return Config()
        return try {
            ConfigValidator.validate(ConfigJson.parseStored(JSONObject(text)))
        } catch (e: Exception) {
            Logger.e(TAG, "persisted config is unreadable; using defaults", e)
            Config()
        }
    }

    private fun loadRuntime(): RuntimeState {
        val text = prefs.getString(KEY_RUNTIME, null) ?: return RuntimeState()
        return try {
            RuntimeStateJson.fromJson(JSONObject(text))
        } catch (e: Exception) {
            Logger.e(TAG, "persisted runtime state is unreadable; using defaults", e)
            RuntimeState()
        }
    }

    internal companion object {
        private const val TAG = "LT.ConfigStore"

        /** Full TS-shaped config JSON (`ConfigJson.toJson`). */
        const val KEY_CONFIG = "cfg_config"

        /** Runtime state JSON (`RuntimeStateJson`). */
        const val KEY_RUNTIME = "cfg_runtime"
    }
}
