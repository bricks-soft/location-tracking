package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.device.DeviceInfoProvider
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.engine.TrackingEngine
import com.brickssoft.locationtracking.geofence.GeofenceManager
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.logging.LogStore
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.position.PositionService
import com.brickssoft.locationtracking.processing.Odometer
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.settings.DeviceSettings

/** The components [PluginHandlers] delegates to. Tests implement it with the scaffold fakes. */
internal interface BridgeServices {
    val engine: TrackingEngine
    val positions: PositionService
    val locationStore: LocationStore
    val recordFactory: RecordFactory
    val syncer: HttpSyncer
    val geofences: GeofenceManager
    val heartbeat: HeartbeatScheduler
    val device: DeviceMonitor
    val deviceInfo: DeviceInfoProvider
    val deviceSettings: DeviceSettings
    val permissions: PermissionManager
    val logStore: LogStore
    val odometer: Odometer
    val configStore: ConfigStore
    val events: EventBus
    val dispatchers: AppDispatchers
}

/** [BridgeServices] backed by [Components]; each component is resolved (and created) on first use only. */
internal class ComponentServices(private val components: Components) : BridgeServices {
    override val engine: TrackingEngine get() = components.engine
    override val positions: PositionService get() = components.positions
    override val locationStore: LocationStore get() = components.locationStore
    override val recordFactory: RecordFactory get() = components.recordFactory
    override val syncer: HttpSyncer get() = components.syncer
    override val geofences: GeofenceManager get() = components.geofences
    override val heartbeat: HeartbeatScheduler get() = components.heartbeat
    override val device: DeviceMonitor get() = components.device
    override val deviceInfo: DeviceInfoProvider get() = components.deviceInfo
    override val deviceSettings: DeviceSettings get() = components.deviceSettings
    override val permissions: PermissionManager get() = components.permissions
    override val logStore: LogStore get() = components.logStore
    override val odometer: Odometer get() = components.odometer
    override val configStore: ConfigStore get() = components.configStore
    override val events: EventBus get() = components.events
    override val dispatchers: AppDispatchers get() = components.dispatchers
}
