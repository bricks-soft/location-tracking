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

/** JS `DeviceInfo`. */
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
)

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
