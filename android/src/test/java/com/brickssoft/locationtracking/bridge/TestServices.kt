package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.device.DeviceInfoProvider
import com.brickssoft.locationtracking.model.DeviceInfo
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.Sensors
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.position.PositionService
import com.brickssoft.locationtracking.settings.DeviceSettings
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeDeviceSettings
import com.brickssoft.locationtracking.testing.FakeGeofenceManager
import com.brickssoft.locationtracking.testing.FakeHeartbeatScheduler
import com.brickssoft.locationtracking.testing.FakeHttpSyncer
import com.brickssoft.locationtracking.testing.FakeLocationStore
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.FakeOdometer
import com.brickssoft.locationtracking.testing.FakePermissionManager
import com.brickssoft.locationtracking.testing.FakePositionService
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.FakeTrackingEngine
import com.brickssoft.locationtracking.testing.RecordingEventBus
import com.brickssoft.locationtracking.testing.testDispatchers
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** [BridgeServices] over the scaffold fakes. */
internal class TestServices(
    val clock: FakeClock = FakeClock(),
    override val configStore: FakeConfigStore = FakeConfigStore(),
    override val engine: FakeTrackingEngine = FakeTrackingEngine(configStore),
    override val positions: PositionService = FakePositionService(),
    override val locationStore: FakeLocationStore = FakeLocationStore(),
    override val recordFactory: FakeRecordFactory = FakeRecordFactory(clock),
    override val syncer: FakeHttpSyncer = FakeHttpSyncer(),
    override val geofences: FakeGeofenceManager = FakeGeofenceManager(),
    override val heartbeat: FakeHeartbeatScheduler = FakeHeartbeatScheduler(),
    override val device: FakeDeviceMonitor = FakeDeviceMonitor(),
    override val deviceInfo: FakeDeviceInfoProvider = FakeDeviceInfoProvider(),
    override val deviceSettings: DeviceSettings = FakeDeviceSettings(),
    override val permissions: PermissionManager = FakePermissionManager(),
    override val logStore: FakeLogStore = FakeLogStore(),
    override val odometer: FakeOdometer = FakeOdometer(),
    override val events: RecordingEventBus = RecordingEventBus(),
    override val dispatchers: AppDispatchers = testDispatchers(),
) : BridgeServices {
    val fakePositions: FakePositionService get() = positions as FakePositionService
    val fakeSettings: FakeDeviceSettings get() = deviceSettings as FakeDeviceSettings
    val fakePermissions: FakePermissionManager get() = permissions as FakePermissionManager
}

/** Fixed [DeviceInfoProvider]. */
internal class FakeDeviceInfoProvider : DeviceInfoProvider {
    var info = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 9",
        brand = "google",
        osVersion = "15",
        sdkInt = 35,
        pluginVersion = "0.1.0",
        gmsAvailable = true,
        hmsAvailable = false,
        backend = ProviderKind.GMS,
        packagedProviders = listOf("gms", "hms"),
    )

    var sensorsValue = Sensors(
        accelerometer = true,
        gyroscope = true,
        magnetometer = false,
        significantMotion = true,
        stepCounter = false,
        stepDetector = false,
        barometer = true,
    )

    override fun deviceInfo(): DeviceInfo = info

    override fun sensors(): Sensors = sensorsValue
}

/** [WatchTarget] that records deliveries, failures and releases. */
internal class RecordingWatchTarget : WatchTarget {
    val delivered = CopyOnWriteArrayList<JSONObject>()
    val failures = CopyOnWriteArrayList<Pair<ErrorCode, String>>()

    @Volatile
    var releases = 0

    override fun deliver(location: JSONObject) {
        delivered += location
    }

    override fun fail(code: ErrorCode, message: String) {
        failures += code to message
    }

    override fun release() {
        releases++
    }
}
