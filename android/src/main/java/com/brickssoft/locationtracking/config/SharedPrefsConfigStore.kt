// STUB — owned by Unit 3 (Config + state). Replace this implementation.
package com.brickssoft.locationtracking.config

import android.content.Context
import com.brickssoft.locationtracking.core.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONObject

/** [ConfigStore] backed by SharedPreferences `location_tracking_prefs`. Stub: in-memory defaults only. */
class SharedPrefsConfigStore(
    @Suppress("unused") private val context: Context,
    @Suppress("unused") private val clock: Clock,
) : ConfigStore {
    private val configFlow = MutableStateFlow(Config())
    private val runtimeFlow = MutableStateFlow(RuntimeState())

    override val config: StateFlow<Config> = configFlow
    override val runtime: StateFlow<RuntimeState> = runtimeFlow

    override fun ready(json: JSONObject?, reset: Boolean): Config = configFlow.value

    override fun merge(json: JSONObject): Config = configFlow.value

    override fun reset(json: JSONObject?): Config = configFlow.value

    override fun update(transform: (Config) -> Config): Config = configFlow.updateAndGet(transform)

    override fun updateRuntime(transform: (RuntimeState) -> RuntimeState): RuntimeState =
        runtimeFlow.updateAndGet(transform)
}
