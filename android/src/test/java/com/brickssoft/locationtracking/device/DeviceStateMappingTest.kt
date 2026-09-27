package com.brickssoft.locationtracking.device

import android.content.Intent
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.PermissionLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class DeviceStateMappingTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun caps(transports: List<Int>, capabilities: List<Int>): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(caps).addTransportType(it) }
        capabilities.forEach { shadowOf(caps).addCapability(it) }
        return caps
    }

    private fun battery(level: Int? = null, scale: Int? = null, status: Int? = null, plugged: Int? = null) =
        DeviceStateMapping.batteryOf(
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                level?.let { putExtra(BatteryManager.EXTRA_LEVEL, it) }
                scale?.let { putExtra(BatteryManager.EXTRA_SCALE, it) }
                status?.let { putExtra(BatteryManager.EXTRA_STATUS, it) }
                plugged?.let { putExtra(BatteryManager.EXTRA_PLUGGED, it) }
            },
        )

    @Test
    fun `connectivity type follows the transport`() {
        val internet = listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val cases = mapOf(
            listOf(NetworkCapabilities.TRANSPORT_WIFI) to ConnectivityType.WIFI,
            listOf(NetworkCapabilities.TRANSPORT_CELLULAR) to ConnectivityType.CELLULAR,
            listOf(NetworkCapabilities.TRANSPORT_ETHERNET) to ConnectivityType.ETHERNET,
            listOf(NetworkCapabilities.TRANSPORT_VPN) to ConnectivityType.OTHER,
            listOf(NetworkCapabilities.TRANSPORT_BLUETOOTH) to ConnectivityType.OTHER,
            listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI) to ConnectivityType.WIFI,
        )
        cases.forEach { (transports, type) ->
            val actual = DeviceStateMapping.connectivityOf(caps(transports, internet))
            assertEquals("$transports", Connectivity(true, type), actual)
        }
    }

    @Test
    fun `connected needs internet but not validation and no captive portal`() {
        val wifi = listOf(NetworkCapabilities.TRANSPORT_WIFI)

        assertEquals(
            Connectivity(true, ConnectivityType.WIFI),
            DeviceStateMapping.connectivityOf(caps(wifi, listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET))),
        )
        assertEquals(
            Connectivity(false, ConnectivityType.WIFI),
            DeviceStateMapping.connectivityOf(caps(wifi, listOf(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))),
        )
        val captive = listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
        )
        assertEquals(Connectivity(false, ConnectivityType.WIFI), DeviceStateMapping.connectivityOf(caps(wifi, captive)))
        assertEquals(Connectivity(false, ConnectivityType.NONE), DeviceStateMapping.connectivityOf(null))
    }

    @Test
    fun `battery level is level over scale`() {
        assertEquals(0.45f, battery(level = 45, scale = 100).level, 1e-6f)
        assertEquals(0.5f, battery(level = 1, scale = 2).level, 1e-6f)
        assertEquals(1f, battery(level = 120, scale = 100).level, 1e-6f)
        assertEquals(-1f, battery(level = 45).level, 1e-6f)
        assertEquals(-1f, battery(level = 45, scale = 0).level, 1e-6f)
        assertEquals(-1f, battery(scale = 100).level, 1e-6f)
        assertEquals(BatterySnapshot.UNKNOWN, DeviceStateMapping.batteryOf(null))
    }

    @Test
    fun `charging follows plug state first then status`() {
        val charging = BatteryManager.BATTERY_STATUS_CHARGING
        val discharging = BatteryManager.BATTERY_STATUS_DISCHARGING
        val full = BatteryManager.BATTERY_STATUS_FULL
        val notCharging = BatteryManager.BATTERY_STATUS_NOT_CHARGING

        assertEquals(true, battery(status = charging, plugged = BatteryManager.BATTERY_PLUGGED_AC).isCharging)
        assertEquals(true, battery(status = full, plugged = BatteryManager.BATTERY_PLUGGED_USB).isCharging)
        assertEquals(true, battery(status = notCharging, plugged = BatteryManager.BATTERY_PLUGGED_WIRELESS).isCharging)
        assertEquals(true, battery(status = charging, plugged = 0).isCharging)
        assertEquals(true, battery(status = charging).isCharging)
        assertEquals(true, battery(status = full).isCharging)
        assertEquals(false, battery(status = full, plugged = 0).isCharging)
        assertEquals(false, battery(status = discharging, plugged = 0).isCharging)
        assertEquals(false, battery().isCharging)
    }

    @Test
    fun `permission and accuracy levels`() {
        val permission = DeviceStateMapping::permissionLevel
        assertEquals(PermissionLevel.ALWAYS, permission(true, true))
        assertEquals(PermissionLevel.WHEN_IN_USE, permission(true, false))
        assertEquals(PermissionLevel.DENIED, permission(false, true))
        assertEquals(PermissionLevel.DENIED, permission(false, false))

        assertEquals(AccuracyLevel.PRECISE, DeviceStateMapping.accuracyLevel(foreground = true, fine = true))
        assertEquals(AccuracyLevel.APPROXIMATE, DeviceStateMapping.accuracyLevel(foreground = true, fine = false))
        assertEquals(AccuracyLevel.NONE, DeviceStateMapping.accuracyLevel(foreground = false, fine = true))
    }
}
