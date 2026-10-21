package com.brickssoft.locationtracking.model

// ---- location settings shared by config, providers and positions

/** JS `DesiredAccuracy`. */
enum class DesiredAccuracy(val wire: String) {
    HIGH("high"),
    BALANCED("balanced"),
    LOW("low"),
    PASSIVE("passive"),
    ;

    companion object {
        fun fromWire(value: String?): DesiredAccuracy? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** JS `LocationProviderSetting` (config `locationProvider`). */
enum class LocationProviderSetting(val wire: String) {
    AUTO("auto"),
    GMS("gms"),
    HMS("hms"),
    ANDROID("android"),
    ;

    companion object {
        fun fromWire(value: String?): LocationProviderSetting? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

// ---- provider state

enum class PermissionLevel(val wire: String) {
    ALWAYS("always"),
    WHEN_IN_USE("when_in_use"),
    DENIED("denied"),
    ;

    companion object {
        fun fromWire(value: String?): PermissionLevel? = entries.firstOrNull { it.wire == value }
    }
}

enum class AccuracyLevel(val wire: String) {
    PRECISE("precise"),
    APPROXIMATE("approximate"),
    NONE("none"),
    ;

    companion object {
        fun fromWire(value: String?): AccuracyLevel? = entries.firstOrNull { it.wire == value }
    }
}

/** JS `ProviderState`: location services, permission and backend. */
data class ProviderState(
    val enabled: Boolean,
    val gps: Boolean,
    val network: Boolean,
    val permission: PermissionLevel,
    val accuracy: AccuracyLevel,
    val backend: ProviderKind,
)

// ---- connectivity / http

enum class ConnectivityType {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    NONE,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): ConnectivityType? = entries.firstOrNull { it.wire == value }
    }
}

data class Connectivity(val connected: Boolean, val type: ConnectivityType)

/** Result of one HTTP request (JS `HttpEvent`). */
data class HttpResult(val success: Boolean, val status: Int, val responseText: String, val uuids: List<String>)

// ---- heartbeat

enum class HeartbeatStrategy {
    EXACT,
    LISTENER_WITH_BACKUP,
    IDLE_PACED,
    DISABLED,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): HeartbeatStrategy? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Round 2: the optional `heartbeat` object of a heartbeat record (wire keys `strategy`, `min_interval`,
 * `max_interval`, `next_at`, `battery_exempt`, `device_idle`). It tells the server which cadence to expect.
 *
 * @property strategy how the next heartbeat is scheduled (never [HeartbeatStrategy.DISABLED] in a record).
 * @property minInterval `heartbeat.minInterval` at creation, seconds.
 * @property maxInterval `heartbeat.maxInterval` at creation, seconds.
 * @property nextAt when the next heartbeat is expected (the alarm armed after this one), epoch ms; null if unknown.
 * @property batteryExempt the app is exempt from battery optimization.
 * @property deviceIdle the device was in (deep) Doze when the heartbeat was created.
 */
data class HeartbeatMeta(
    val strategy: HeartbeatStrategy,
    val minInterval: Int,
    val maxInterval: Int,
    val nextAt: Long?,
    val batteryExempt: Boolean,
    val deviceIdle: Boolean,
)

/** JS `HeartbeatStatus`; times are epoch ms (ISO strings in JS); intervals are seconds. */
data class HeartbeatStatus(
    val enabled: Boolean,
    val minInterval: Int,
    val maxInterval: Int,
    val lastRecordAt: Long?,
    val lastHeartbeatAt: Long?,
    val nextHeartbeatAt: Long?,
    val strategy: HeartbeatStrategy,
    val canScheduleExactAlarms: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val isDeviceIdleMode: Boolean,
    val isPowerSaveMode: Boolean,
    val pendingHeartbeats: Int,
)

// ---- device

/**
 * JS `DeviceInfo`.
 *
 * @property backendFallbackReason why a better location backend is not used (recommendation 15), or null.
 * @property gmsStatus `GoogleApiAvailability` result code (0 = SUCCESS), null if GMS is not packaged or not checked.
 * @property hmsStatus `HuaweiApiAvailability` result code (0 = SUCCESS), null if HMS is not packaged or not checked.
 * @property hmsCoreOutdated true when HMS Core is older than 6.3.0.301; null if HMS is not packaged.
 * @property locationAvailableHint the last `LocationAvailability` of GMS or HMS; a hint, null if none was reported.
 * @property cpuCores `Runtime.availableProcessors()`.
 * @property supportedAbis `Build.SUPPORTED_ABIS`.
 */
data class DeviceInfo(
    val platform: String = "android",
    val manufacturer: String,
    val model: String,
    val brand: String,
    val osVersion: String,
    val sdkInt: Int,
    val pluginVersion: String,
    val gmsAvailable: Boolean,
    val hmsAvailable: Boolean,
    val backend: ProviderKind,
    val packagedProviders: List<String>,
    val backendFallbackReason: String? = null,
    val gmsStatus: Int? = null,
    val hmsStatus: Int? = null,
    val hmsCoreOutdated: Boolean? = null,
    val locationAvailableHint: Boolean? = null,
    val cpuCores: Int = 0,
    val supportedAbis: List<String> = emptyList(),
)

/**
 * The `device` object of records (see `DeviceDetails`); a null field is not written. Every record: [deviceId].
 * Heartbeat: [backgroundRestricted], [standbyBucket], [diskFreeBytes], [thermalStatus]. `tracking_start`:
 * [manufacturer], [model], [osVersion], [sdkInt], [lowRamDevice], [ramTotalBytes].
 *
 * @property deviceId the device id: `Settings.Secure.ANDROID_ID` on Android; null in records queued by an older version.
 */
data class DeviceSnapshot(
    val backgroundRestricted: Boolean? = null,
    val standbyBucket: String? = null,
    val diskFreeBytes: Long? = null,
    val thermalStatus: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val sdkInt: Int? = null,
    val deviceId: String? = null,
    val lowRamDevice: Boolean? = null,
    val ramTotalBytes: Long? = null,
) {
    val isEmpty: Boolean get() = this == DeviceSnapshot()
}

/** Battery readings added to the `battery` object of heartbeat records: °C, health and power source. */
data class BatteryDetails(val temperature: Float?, val health: String?, val plugged: String?)

/** JS `Sensors`. */
data class Sensors(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
    val magnetometer: Boolean,
    val significantMotion: Boolean,
    val stepCounter: Boolean,
    val stepDetector: Boolean,
    val barometer: Boolean,
)

/** JS `PowerManagerInfo`: whether an OEM power-manager screen is available. */
data class PowerManagerInfo(val manufacturer: String, val available: Boolean)

/** JS `BatteryOptimizationStatus`. */
data class BatteryOptimizationStatus(
    val isIgnoringBatteryOptimizations: Boolean,
    val canScheduleExactAlarms: Boolean,
    val isDeviceIdleMode: Boolean,
)
