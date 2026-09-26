// STUB — owned by Unit 7 (Engine). Replace this implementation.
package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.State
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.geofence.GeofenceManager
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.processing.LocationProcessor
import com.brickssoft.locationtracking.processing.Odometer
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import com.brickssoft.locationtracking.service.ServiceController
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/** The tracking engine. Stub: config calls go straight to the store; tracking calls do nothing. */
@Suppress("unused")
class DefaultTrackingEngine(
    private val configStore: ConfigStore,
    private val providers: ProviderFactory,
    private val processor: LocationProcessor,
    private val odometer: Odometer,
    private val recordFactory: RecordFactory,
    private val recordSink: RecordSink,
    private val heartbeat: HeartbeatScheduler,
    private val geofences: GeofenceManager,
    private val serviceController: ServiceController,
    private val device: DeviceMonitor,
    private val syncer: HttpSyncer,
    private val permissions: PermissionManager,
    private val events: EventBus,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : TrackingEngine {
    override suspend fun ready(config: JSONObject?, reset: Boolean): State {
        configStore.ready(config, reset)
        configStore.updateRuntime { it.copy(didReady = true) }
        return state()
    }

    override suspend fun setConfig(config: JSONObject): State {
        configStore.merge(config)
        return state()
    }

    override suspend fun reset(config: JSONObject?): State {
        configStore.reset(config)
        return state()
    }

    override suspend fun start(): State = state()

    override suspend fun startGeofences(): State = state()

    override suspend fun stop(): State = state()

    override suspend fun changePace(isMoving: Boolean) = Unit

    override fun state(): State = State(configStore.config.value, configStore.runtime.value, providers.kind)

    override suspend fun restore(reason: String) = Unit

    override suspend fun onTerminate() = Unit

    override suspend fun onActivitySamples(samples: List<ActivitySample>) = Unit
}
