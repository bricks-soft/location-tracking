package com.brickssoft.locationtracking.device

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.LogSink
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.permission.PermissionState
import com.brickssoft.locationtracking.permission.PermissionType
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakePermissionManager
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.FakeRecordSink
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Provider state, providerchange diffing (event + audit record) and receiver registration. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultDeviceMonitorProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val lastFix = Fixtures.location()
    private val configStore = FakeConfigStore()
    private val permissions = FakePermissionManager()
    private val providers = FakeProviderFactory(ProviderKind.GMS)
    private val events = RecordingEventBus()
    private val recordFactory = FakeRecordFactory(clock, configStore)
    private val recordSink = FakeRecordSink()
    private val locationManager = app.getSystemService(LocationManager::class.java)

    @Before
    fun setUp() {
        setLocation(enabled = true, gps = true, network = true)
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun TestScope.newMonitor(sink: RecordSink = recordSink) = DefaultDeviceMonitor(
        app,
        configStore,
        permissions,
        lazy { providers },
        events,
        clock,
        lazy { recordFactory },
        lazy { sink },
        backgroundScope,
    )

    private fun setLocation(enabled: Boolean, gps: Boolean, network: Boolean) {
        val shadow = shadowOf(locationManager)
        shadow.setLocationEnabled(enabled)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, gps)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, network)
    }

    /** Delivers the broadcasts to registered receivers (main looper) without running the debounced check. */
    private fun deliver(vararg actions: String) {
        actions.forEach { app.sendBroadcast(Intent(it)) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Lets a scheduled provider check run. */
    private fun TestScope.settle() {
        advanceTimeBy(DefaultDeviceMonitor.PROVIDER_CHECK_DEBOUNCE_MS)
        runCurrent()
    }

    private fun TestScope.broadcast(vararg actions: String) {
        deliver(*actions)
        settle()
    }

    private fun trackingWith(state: ProviderState?, enabled: Boolean = true) {
        configStore.runtimeFlow.value = RuntimeState(enabled = enabled, lastLocation = lastFix, providerState = state)
    }

    private fun providerChanges() = events.ofType<TrackingEvent.ProviderChange>().map { it.state }

    private fun providerRecords(): List<Record> = recordSink.ofEvent(RecordEvent.PROVIDERCHANGE)

    // ---- providerState()

    @Test
    fun `providerState reports everything on with background and fine permission`() = runTest {
        val state = newMonitor().providerState()

        assertEquals(Fixtures.providerState(), state)
        assertTrue(configStore.calls.isEmpty())
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `providerState reports when-in-use and approximate with coarse foreground only`() = runTest {
        permissions.set(PermissionType.BACKGROUND_LOCATION, PermissionState.DENIED)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        providers.kind = ProviderKind.HMS

        val state = newMonitor().providerState()

        assertEquals(PermissionLevel.WHEN_IN_USE, state.permission)
        assertEquals(AccuracyLevel.APPROXIMATE, state.accuracy)
        assertEquals(ProviderKind.HMS, state.backend)
    }

    @Test
    fun `providerState reports denied and no accuracy without foreground permission`() = runTest {
        permissions.denyAll()

        val state = newMonitor().providerState()

        assertEquals(PermissionLevel.DENIED, state.permission)
        assertEquals(AccuracyLevel.NONE, state.accuracy)
    }

    @Test
    fun `providerState reports location services off`() = runTest {
        setLocation(enabled = false, gps = false, network = false)

        val state = newMonitor().providerState()

        assertEquals(Fixtures.providerState(enabled = false, gps = false, network = false), state)
    }

    // ---- checkProviderState()

    @Test
    fun `first observation persists silently`() = runTest {
        trackingWith(state = null)

        newMonitor().checkProviderState("ready")

        assertEquals(Fixtures.providerState(), configStore.runtime.value.providerState)
        assertTrue(events.events.isEmpty())
        assertTrue(recordSink.records.isEmpty())
    }

    @Test
    fun `gps turned off while tracking emits exactly one event and one providerchange record`() = runTest {
        trackingWith(Fixtures.providerState())
        val monitor = newMonitor()
        monitor.start()

        setLocation(enabled = true, gps = false, network = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)

        val expected = Fixtures.providerState(gps = false)
        assertEquals(listOf(expected), providerChanges())
        val record = providerRecords().single()
        assertEquals(expected, record.provider)
        assertFalse(record.provider!!.gps)
        assertTrue(record.provider!!.enabled)
        assertEquals(lastFix, record.location)
        assertEquals(1, recordSink.records.size)
        assertEquals(expected, configStore.runtime.value.providerState)
    }

    @Test
    fun `location services turned off while tracking records enabled false`() = runTest {
        trackingWith(Fixtures.providerState())
        newMonitor().start()

        setLocation(enabled = false, gps = false, network = false)
        broadcast(LocationManager.MODE_CHANGED_ACTION)

        val expected = Fixtures.providerState(enabled = false, gps = false, network = false)
        assertEquals(listOf(expected), providerChanges())
        assertEquals(expected, providerRecords().single().provider)
    }

    @Test
    fun `a burst of provider broadcasts yields one check and one record`() = runTest {
        trackingWith(Fixtures.providerState())
        newMonitor().start()
        settle()

        setLocation(enabled = false, gps = false, network = false)
        broadcast(
            LocationManager.PROVIDERS_CHANGED_ACTION,
            LocationManager.PROVIDERS_CHANGED_ACTION,
            LocationManager.MODE_CHANGED_ACTION,
        )

        assertEquals(1, providerChanges().size)
        assertEquals(1, providerRecords().size)
        assertEquals(1, configStore.calls.count { it == "updateRuntime" })
    }

    @Test
    fun `a later change after a check produces a second record`() = runTest {
        trackingWith(Fixtures.providerState())
        newMonitor().start()

        setLocation(enabled = true, gps = false, network = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)
        setLocation(enabled = true, gps = true, network = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)

        assertEquals(listOf(Fixtures.providerState(gps = false), Fixtures.providerState()), providerChanges())
        assertEquals(listOf(false, true), providerRecords().map { it.provider!!.gps })
    }

    @Test
    fun `no change produces nothing`() = runTest {
        trackingWith(Fixtures.providerState())
        val monitor = newMonitor()
        monitor.start()

        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)
        monitor.checkProviderState("resume")

        assertTrue(events.events.isEmpty())
        assertTrue(recordSink.records.isEmpty())
        assertFalse(configStore.calls.contains("updateRuntime"))
    }

    @Test
    fun `tracking disabled emits the event but records nothing`() = runTest {
        trackingWith(Fixtures.providerState(), enabled = false)
        newMonitor().start()

        setLocation(enabled = true, gps = false, network = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)

        assertEquals(listOf(Fixtures.providerState(gps = false)), providerChanges())
        assertTrue(recordSink.records.isEmpty())
        assertEquals(Fixtures.providerState(gps = false), configStore.runtime.value.providerState)
    }

    @Test
    fun `permission downgrade is detected on a cold start`() = runTest {
        // Persisted by the previous process; revoking a permission killed it.
        trackingWith(Fixtures.providerState(permission = PermissionLevel.ALWAYS, accuracy = AccuracyLevel.PRECISE))
        permissions.set(PermissionType.BACKGROUND_LOCATION, PermissionState.DENIED)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        newMonitor().checkProviderState("restore")

        val expected = Fixtures.providerState(
            permission = PermissionLevel.WHEN_IN_USE,
            accuracy = AccuracyLevel.APPROXIMATE,
        )
        assertEquals(listOf(expected), providerChanges())
        val record = providerRecords().single()
        assertEquals(expected, record.provider)
        assertEquals(lastFix, record.location)
        assertEquals(expected, configStore.runtime.value.providerState)
    }

    @Test
    fun `permission fully revoked is reported as denied`() = runTest {
        trackingWith(Fixtures.providerState())
        permissions.denyAll()

        newMonitor().checkProviderState("restore")

        val state = providerRecords().single().provider!!
        assertEquals(PermissionLevel.DENIED, state.permission)
        assertEquals(AccuracyLevel.NONE, state.accuracy)
    }

    @Test
    fun `backend change is a provider change`() = runTest {
        trackingWith(Fixtures.providerState(backend = ProviderKind.GMS))
        providers.kind = ProviderKind.HMS

        newMonitor().checkProviderState("reselect")

        assertEquals(listOf(Fixtures.providerState(backend = ProviderKind.HMS)), providerChanges())
    }

    @Test
    fun `record carries a null location when none is known`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true, providerState = Fixtures.providerState())
        setLocation(enabled = true, gps = false, network = true)

        newMonitor().checkProviderState("resume")

        assertNull(providerRecords().single().location)
    }

    @Test
    fun `a failing sink leaves the state unpersisted so the next check retries the record`() = runTest {
        trackingWith(Fixtures.providerState())
        setLocation(enabled = true, gps = false, network = true)
        var failures = 1
        val flaky = object : RecordSink {
            override suspend fun submit(record: Record): Record {
                if (failures-- > 0) throw IOException("disk full")
                return recordSink.submit(record)
            }
        }
        val monitor = newMonitor(sink = flaky)

        monitor.checkProviderState("resume")

        assertEquals(listOf(Fixtures.providerState(gps = false)), providerChanges())
        assertEquals(Fixtures.providerState(), configStore.runtime.value.providerState)
        assertTrue(recordSink.records.isEmpty())

        monitor.checkProviderState("heartbeat")

        assertEquals(Fixtures.providerState(gps = false), providerRecords().single().provider)
        assertEquals(Fixtures.providerState(gps = false), configStore.runtime.value.providerState)
    }

    @Test
    fun `the new state is persisted only after the record reached the sink`() = runTest {
        trackingWith(Fixtures.providerState())
        setLocation(enabled = true, gps = false, network = true)
        var persistedDuringSubmit: ProviderState? = null
        val observing = object : RecordSink {
            override suspend fun submit(record: Record): Record {
                persistedDuringSubmit = configStore.runtime.value.providerState
                return recordSink.submit(record)
            }
        }

        newMonitor(sink = observing).checkProviderState("resume")

        assertEquals(Fixtures.providerState(), persistedDuringSubmit)
        assertEquals(Fixtures.providerState(gps = false), configStore.runtime.value.providerState)
    }

    // ---- scheduling

    @Test
    fun `intermediate states within the debounce window are not reported`() = runTest {
        trackingWith(Fixtures.providerState())
        newMonitor().start()
        settle()

        // One "location off" toggle: gps goes first, network follows a moment later.
        setLocation(enabled = true, gps = false, network = true)
        deliver(LocationManager.PROVIDERS_CHANGED_ACTION)
        advanceTimeBy(DefaultDeviceMonitor.PROVIDER_CHECK_DEBOUNCE_MS / 2)
        runCurrent()
        assertTrue(providerChanges().isEmpty())
        setLocation(enabled = false, gps = false, network = false)
        deliver(LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION)
        settle()

        val expected = Fixtures.providerState(enabled = false, gps = false, network = false)
        assertEquals(listOf(expected), providerChanges())
        assertEquals(expected, providerRecords().single().provider)
    }

    @Test
    fun `a broadcast while a check is running schedules another check`() = runTest {
        trackingWith(Fixtures.providerState())
        val gate = CompletableDeferred<Unit>()
        val gated = object : RecordSink {
            override suspend fun submit(record: Record): Record {
                if (!gate.isCompleted) gate.await()
                return recordSink.submit(record)
            }
        }
        newMonitor(sink = gated).start()

        setLocation(enabled = true, gps = false, network = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)
        // The first check is now suspended inside submit; network goes off meanwhile.
        assertEquals(1, providerChanges().size)
        setLocation(enabled = false, gps = false, network = false)
        deliver(LocationManager.PROVIDERS_CHANGED_ACTION)
        gate.complete(Unit)
        runCurrent()
        settle()

        assertEquals(
            listOf(
                Fixtures.providerState(gps = false),
                Fixtures.providerState(enabled = false, gps = false, network = false),
            ),
            providerRecords().map { it.provider },
        )
    }

    @Test
    fun `start checks the provider state once`() = runTest {
        // Changed while nothing was listening (e.g. background permission granted from system settings).
        trackingWith(Fixtures.providerState(permission = PermissionLevel.WHEN_IN_USE))
        val monitor = newMonitor()

        monitor.start()
        monitor.start()
        settle()

        assertEquals(listOf(Fixtures.providerState()), providerChanges())
        assertEquals(Fixtures.providerState(), providerRecords().single().provider)
    }

    // ---- lifecycle

    @Test
    fun `constructor and start do not touch the lazy dependencies`() = runTest {
        val monitor = DefaultDeviceMonitor(
            app,
            configStore,
            permissions,
            lazy<ProviderFactory> { error("providers touched") },
            events,
            clock,
            lazy<RecordFactory> { error("record factory touched") },
            lazy<RecordSink> { error("record sink touched") },
            backgroundScope,
        )

        monitor.start()
    }

    @Test
    fun `start registers the receiver and the network callback once`() = runTest {
        val monitor = newMonitor()

        monitor.start()
        monitor.start()

        val receivers = shadowOf(app).registeredReceivers.filter {
            it.intentFilter.hasAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        assertEquals(1, receivers.size)
        val filter = receivers.single().intentFilter
        assertTrue(filter.hasAction(LocationManager.MODE_CHANGED_ACTION))
        assertTrue(filter.hasAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        assertTrue(filter.hasAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
        assertEquals(1, shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks.size)
    }

    @Test
    fun `broadcasts before start are ignored`() = runTest {
        trackingWith(Fixtures.providerState())
        newMonitor()

        setLocation(enabled = true, gps = false, network = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)

        assertTrue(events.events.isEmpty())
    }

    @Test
    @Config(sdk = [29])
    fun `gps change is detected on API 29 with the androidx not-exported permission`() = runTest {
        // Real apps get this permission from androidx.core's manifest; the library test manifest lacks it.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val logs = captureLogs()
        trackingWith(Fixtures.providerState())
        newMonitor().start()

        setLocation(enabled = true, gps = false, network = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)

        assertEquals(Fixtures.providerState(gps = false), providerRecords().single().provider)
        assertTrue(logs.none { it.first == LogLevel.WARN || it.first == LogLevel.ERROR })
    }

    @Test
    @Config(sdk = [29])
    fun `gps change is detected on API 29 when the not-exported permission is missing`() = runTest {
        val logs = captureLogs()
        trackingWith(Fixtures.providerState())
        newMonitor().start()

        setLocation(enabled = true, gps = false, network = true)
        broadcast(LocationManager.PROVIDERS_CHANGED_ACTION)

        assertEquals(Fixtures.providerState(gps = false), providerRecords().single().provider)
        assertTrue(logs.any { it.first == LogLevel.WARN && "RECEIVER_NOT_EXPORTED" in it.second })
    }

    private fun captureLogs(): List<Pair<LogLevel, String>> {
        val lines = CopyOnWriteArrayList<Pair<LogLevel, String>>()
        Logger.sink = object : LogSink {
            override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
                lines += level to message
            }
        }
        return lines
    }
}
