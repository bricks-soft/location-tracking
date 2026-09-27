package com.brickssoft.locationtracking.device

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakePermissionManager
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.FakeRecordSink
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** Connectivity, power-save and idle, battery and exact-alarm state and their events. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultDeviceMonitorSystemTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val configStore = FakeConfigStore()
    private val events = RecordingEventBus()
    private val connectivityManager = app.getSystemService(ConnectivityManager::class.java)
    private val powerManager = app.getSystemService(PowerManager::class.java)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun TestScope.newMonitor() = DefaultDeviceMonitor(
        app,
        configStore,
        FakePermissionManager(),
        lazy { FakeProviderFactory(ProviderKind.GMS) },
        events,
        clock,
        lazy { FakeRecordFactory(clock, configStore) },
        lazy { FakeRecordSink() },
        backgroundScope,
    )

    private fun caps(
        vararg transports: Int,
        internet: Boolean = true,
        validated: Boolean = true,
        captivePortal: Boolean = false,
    ): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        val shadow = shadowOf(caps)
        transports.forEach { shadow.addTransportType(it) }
        if (internet) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (captivePortal) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        return caps
    }

    private fun setActive(caps: NetworkCapabilities) {
        val shadow = shadowOf(connectivityManager)
        shadow.setDefaultNetworkActive(true)
        shadow.setNetworkCapabilities(connectivityManager.activeNetwork, caps)
    }

    private fun connectivityEvents() = events.ofType<TrackingEvent.ConnectivityChange>().map { it.connectivity }

    private fun powerSaveEvents() = events.ofType<TrackingEvent.PowerSaveChange>().map { it.isPowerSaveMode }

    private fun sendBroadcast(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- connectivity

    @Test
    fun `connectivity maps the active network`() = runTest {
        val monitor = newMonitor()

        setActive(caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(Connectivity(true, ConnectivityType.WIFI), monitor.connectivity())

        setActive(caps(NetworkCapabilities.TRANSPORT_CELLULAR, validated = false))
        assertEquals(Connectivity(true, ConnectivityType.CELLULAR), monitor.connectivity())

        setActive(caps(NetworkCapabilities.TRANSPORT_ETHERNET, internet = false, validated = false))
        assertEquals(Connectivity(false, ConnectivityType.ETHERNET), monitor.connectivity())
    }

    @Test
    fun `connectivity without an active network is none`() = runTest {
        shadowOf(connectivityManager).setDefaultNetworkActive(false)

        assertEquals(Connectivity(false, ConnectivityType.NONE), newMonitor().connectivity())
    }

    @Test
    fun `network callback emits deduplicated connectivity changes`() = runTest {
        setActive(caps(NetworkCapabilities.TRANSPORT_WIFI))
        val monitor = newMonitor()
        monitor.start()
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(101)
        val cell = ShadowNetwork.newInstance(102)

        // The initial callback matches the state seeded at start: no event.
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(connectivityEvents().isEmpty())

        // Default network switches to cellular (validation still pending), then validates: one event.
        callback.onAvailable(cell)
        callback.onCapabilitiesChanged(cell, caps(NetworkCapabilities.TRANSPORT_CELLULAR, validated = false))
        callback.onCapabilitiesChanged(cell, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        // The old network's late loss is not the default network's loss.
        callback.onLost(wifi)
        assertEquals(listOf(Connectivity(true, ConnectivityType.CELLULAR)), connectivityEvents())

        callback.onLost(cell)
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))

        assertEquals(
            listOf(
                Connectivity(true, ConnectivityType.CELLULAR),
                Connectivity(false, ConnectivityType.NONE),
                Connectivity(true, ConnectivityType.WIFI),
            ),
            connectivityEvents(),
        )
    }

    @Test
    fun `captive portal reads as disconnected until it is passed`() = runTest {
        setActive(caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        val monitor = newMonitor()
        monitor.start()
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(103)

        callback.onCapabilitiesChanged(
            wifi,
            caps(NetworkCapabilities.TRANSPORT_WIFI, validated = false, captivePortal = true),
        )
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))

        assertEquals(
            listOf(Connectivity(false, ConnectivityType.WIFI), Connectivity(true, ConnectivityType.WIFI)),
            connectivityEvents(),
        )
    }

    @Test
    fun `blocked network access reads as disconnected until it is unblocked`() = runTest {
        setActive(caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        newMonitor().start()
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val cell = ShadowNetwork.newInstance(104)

        callback.onAvailable(cell)
        callback.onCapabilitiesChanged(cell, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        // Doze / Data Saver blocks this app's traffic.
        callback.onBlockedStatusChanged(cell, true)
        // Another network's status and capability updates while blocked change nothing.
        callback.onBlockedStatusChanged(ShadowNetwork.newInstance(105), false)
        callback.onCapabilitiesChanged(cell, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        // Maintenance window: access returns, which must read as a change so queued records are retried.
        callback.onBlockedStatusChanged(cell, false)

        assertEquals(
            listOf(Connectivity(false, ConnectivityType.CELLULAR), Connectivity(true, ConnectivityType.CELLULAR)),
            connectivityEvents(),
        )
    }

    // ---- power save / idle

    @Test
    fun `power save broadcasts emit deduplicated events`() = runTest {
        val shadow = shadowOf(powerManager)
        shadow.setIsPowerSaveMode(false)
        newMonitor().start()

        shadow.setIsPowerSaveMode(true)
        sendBroadcast(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        sendBroadcast(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        assertEquals(listOf(true), powerSaveEvents())

        shadow.setIsPowerSaveMode(false)
        sendBroadcast(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        assertEquals(listOf(true, false), powerSaveEvents())
    }

    @Test
    fun `device idle broadcast emits no event`() = runTest {
        newMonitor().start()

        shadowOf(powerManager).setIsDeviceIdleMode(true)
        sendBroadcast(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)

        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `power and idle flags follow the power manager`() = runTest {
        val monitor = newMonitor()
        val shadow = shadowOf(powerManager)

        shadow.setIsPowerSaveMode(true)
        shadow.setIsDeviceIdleMode(true)
        shadow.setIgnoringBatteryOptimizations(app.packageName, true)
        assertTrue(monitor.isPowerSaveMode())
        assertTrue(monitor.isDeviceIdleMode())
        assertTrue(monitor.isIgnoringBatteryOptimizations())

        shadow.setIsPowerSaveMode(false)
        shadow.setIsDeviceIdleMode(false)
        shadow.setIgnoringBatteryOptimizations(app.packageName, false)
        shadow.setIgnoringBatteryOptimizations("com.other.app", true)
        assertFalse(monitor.isPowerSaveMode())
        assertFalse(monitor.isDeviceIdleMode())
        assertFalse(monitor.isIgnoringBatteryOptimizations())
    }

    // ---- exact alarms

    @Test
    fun `exact alarms follow the alarm manager on API 31+`() = runTest {
        val monitor = newMonitor()

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(monitor.canScheduleExactAlarms())

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(monitor.canScheduleExactAlarms())
    }

    @Test
    @Config(sdk = [30])
    fun `exact alarms are always allowed below API 31`() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertTrue(newMonitor().canScheduleExactAlarms())
    }

    // ---- battery

    @Test
    fun `battery is unknown without a sticky broadcast`() = runTest {
        assertEquals(BatterySnapshot.UNKNOWN, newMonitor().battery())
    }

    @Test
    fun `battery reads the sticky broadcast`() = runTest {
        val monitor = newMonitor()

        sendStickyBattery(
            level = 45,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_USB,
        )
        assertEquals(BatterySnapshot(0.45f, true), monitor.battery())

        sendStickyBattery(level = 160, scale = 200, status = BatteryManager.BATTERY_STATUS_DISCHARGING, plugged = 0)
        assertEquals(BatterySnapshot(0.8f, false), monitor.battery())
    }

    @Suppress("DEPRECATION")
    private fun sendStickyBattery(level: Int, scale: Int, status: Int, plugged: Int) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale)
            .putExtra(BatteryManager.EXTRA_STATUS, status)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
        app.sendStickyBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
