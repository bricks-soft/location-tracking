package com.brickssoft.locationtracking.device

import android.content.Intent
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.PermissionLevel

/** Pure mappings from Android system values to the plugin's device models. */
internal object DeviceStateMapping {
    /** No network at all. */
    val DISCONNECTED = Connectivity(connected = false, type = ConnectivityType.NONE)

    /**
     * Maps a network's capabilities to [Connectivity].
     *
     * The type comes from the first matching transport: Wi-Fi, cellular, ethernet, otherwise OTHER (VPN,
     * Bluetooth, ...). `connected` needs NET_CAPABILITY_INTERNET; VALIDATED is not required because validation
     * can lag or be blocked (for example where the platform's connectivity check host is unreachable).
     * A network behind a captive portal counts as not connected, so that logging in later reads as a change.
     */
    fun connectivityOf(caps: NetworkCapabilities?): Connectivity {
        if (caps == null) return DISCONNECTED
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectivityType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectivityType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectivityType.ETHERNET
            else -> ConnectivityType.OTHER
        }
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        return Connectivity(connected, type)
    }

    /**
     * Parses an `ACTION_BATTERY_CHANGED` intent. Level is `level / scale` in 0..1, or -1 when unknown.
     * Charging means plugged in (AC, USB, wireless, dock) or reporting CHARGING; FULL counts as charging only
     * when the plug state is missing from the intent.
     */
    fun batteryOf(intent: Intent?): BatterySnapshot {
        if (intent == null) return BatterySnapshot.UNKNOWN
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val fraction = if (level >= 0 && scale > 0) (level.toFloat() / scale).coerceIn(0f, 1f) else -1f
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val isCharging = plugged > 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            (plugged < 0 && status == BatteryManager.BATTERY_STATUS_FULL)
        return BatterySnapshot(fraction, isCharging)
    }

    /** Foreground access is required for any level; background access on top of it means ALWAYS. */
    fun permissionLevel(foreground: Boolean, background: Boolean): PermissionLevel = when {
        !foreground -> PermissionLevel.DENIED
        background -> PermissionLevel.ALWAYS
        else -> PermissionLevel.WHEN_IN_USE
    }

    /** PRECISE with fine location, APPROXIMATE with foreground access but no fine location, else NONE. */
    fun accuracyLevel(foreground: Boolean, fine: Boolean): AccuracyLevel = when {
        !foreground -> AccuracyLevel.NONE
        fine -> AccuracyLevel.PRECISE
        else -> AccuracyLevel.APPROXIMATE
    }
}
