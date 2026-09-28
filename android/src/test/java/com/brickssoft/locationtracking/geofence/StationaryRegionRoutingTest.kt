package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.OsGeofence
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.provider.StationaryRegionSink
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeGeofenceBackend
import com.brickssoft.locationtracking.testing.FakeGeofenceStore
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.FakeRecordSink
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Round 2, §3: [DefaultGeofenceManager] forwards transitions of the engine's stationary region
 * ([Constants.STATIONARY_REGION_ID]) to the [StationaryRegionSink] and never stores, records, emits or counts them;
 * `removeAll()` leaves the region registered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationaryRegionRoutingTest {
    private class RecordingSink : StationaryRegionSink {
        val received = CopyOnWriteArrayList<OsGeofenceTransition>()

        /** Called when a transition arrives; returns what the test wants to observe at that moment. */
        @Volatile
        var onReceive: () -> Unit = {}

        @Volatile
        var failWith: Exception? = null

        override suspend fun onStationaryRegionTransition(transition: OsGeofenceTransition) {
            received += transition
            onReceive()
            failWith?.let { throw it }
        }
    }

    private class Harness(scope: TestScope, enabled: Boolean = true) {
        val clock = FakeClock(scheduler = scope.testScheduler)
        val backend = FakeGeofenceBackend(supportsDwell = true)
        val providers = FakeProviderFactory(geofenceBackend = backend)
        val configStore = FakeConfigStore(runtime = RuntimeState(enabled = enabled))
        val store = FakeGeofenceStore()
        val sink = FakeRecordSink()
        val events = RecordingEventBus()
        val stationary = RecordingSink()
        val manager = DefaultGeofenceManager(
            store, providers, configStore, FakeRecordFactory(clock, configStore), sink, events, clock,
            scope.backgroundScope, lazyOf(stationary),
        )

        fun setEnabled(enabled: Boolean) {
            configStore.runtimeFlow.value = configStore.runtimeFlow.value.copy(enabled = enabled)
        }
    }

    private val logs = FakeLogStore()

    private val region = OsGeofence(
        Constants.STATIONARY_REGION_ID, Fixtures.LAT, Fixtures.LNG, 150f,
        onEntry = false, onExit = true, onDwell = false, loiteringDelayMs = 0, initialTriggerEntry = false,
    )

    private fun fix(north: Double): TrackedLocation = Fixtures.moved(Fixtures.location(accuracy = 5f), north)

    private fun stationaryExit(location: TrackedLocation? = fix(300.0)) =
        OsGeofenceTransition(Constants.STATIONARY_REGION_ID, GeofenceAction.EXIT, location)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `stationary region transitions go to the sink and are never stored, recorded or emitted`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("home")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        h.events.clear()
        val exit = stationaryExit()
        val enterHome = OsGeofenceTransition("home", GeofenceAction.ENTER, fix(5.0))
        var recordsWhenForwarded = -1
        h.stationary.onReceive = { recordsWhenForwarded = h.sink.records.size }

        h.manager.onGeofenceTransitions(listOf(exit, enterHome))

        assertEquals(listOf(exit), h.stationary.received)
        // The user geofence of the batch was recorded before the engine got the stationary region's EXIT.
        assertEquals(1, recordsWhenForwarded)
        // The user geofence of the same batch is handled as before.
        assertEquals(listOf("home"), h.sink.records.map { it.geofence!!.identifier })
        assertEquals(listOf("home"), h.events.ofType<TrackingEvent.Geofence>().map { it.identifier })
        assertNull(h.store.get(Constants.STATIONARY_REGION_ID))
        assertTrue(h.store.runtimes().keys.none { it == Constants.STATIONARY_REGION_ID })
        assertEquals(listOf("home"), h.manager.list().map { it.identifier })
    }

    @Test
    fun `stationary region transitions are forwarded before any other check`() = runTest {
        // Tracking disabled, stopped, and geofences never loaded: the sink (the engine) decides what to do.
        val h = Harness(this, enabled = false)
        h.manager.onTrackingStopped()

        h.manager.onGeofenceTransitions(listOf(stationaryExit()))
        h.setEnabled(true)
        h.manager.onGeofenceTransitions(listOf(stationaryExit(location = null)))

        assertEquals(2, h.stationary.received.size)
        assertTrue(h.sink.records.isEmpty())
        assertTrue(h.events.events.isEmpty())
    }

    @Test
    fun `the stationary region does not count against the geofence limit`() = runTest {
        val h = Harness(this)
        h.backend.add(listOf(region)) // registered by the engine

        h.manager.add((1..Constants.MAX_GEOFENCES).map { Fixtures.circle("g$it") })

        assertEquals(Constants.MAX_GEOFENCES, h.store.count())
        assertEquals(Constants.MAX_GEOFENCES + 1, h.backend.registered.size)
    }

    @Test
    fun `a failing sink does not drop the other transitions of the batch`() = runTest {
        Logger.sink = logs
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("home")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        h.stationary.failWith = IllegalStateException("engine failed")

        h.manager.onGeofenceTransitions(
            listOf(stationaryExit(), OsGeofenceTransition("home", GeofenceAction.ENTER, fix(5.0))),
        )

        assertEquals(1, h.stationary.received.size)
        assertEquals(listOf("home"), h.sink.records.map { it.geofence!!.identifier })
        assertTrue(logs.lines.any { it.level == LogLevel.ERROR && "stationary region" in it.message })
    }

    @Test
    fun `removeAll removes the stored ids from the OS and keeps the stationary region`() = runTest {
        val h = Harness(this)
        h.manager.add(
            listOf(
                Fixtures.circle("a"),
                Fixtures.circle("b"),
                Fixtures.circle("mute", notifyOnEntry = false, notifyOnExit = false),
            ),
        )
        h.backend.add(listOf(region))
        h.events.clear()

        h.manager.removeAll()

        assertEquals(0, h.store.count())
        assertEquals(0, h.backend.removeAllCalls)
        assertEquals(listOf(listOf("a", "b")), h.backend.removeCalls) // "mute" was never registered
        assertEquals(setOf(Constants.STATIONARY_REGION_ID), h.backend.registered.keys)
        assertEquals(
            listOf(TrackingEvent.GeofencesChange(on = emptyList(), off = listOf("a", "b", "mute"))),
            h.events.ofType<TrackingEvent.GeofencesChange>(),
        )
    }

    @Test
    fun `tracking stop keeps the stationary region (the engine removes it itself)`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("home")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        h.backend.add(listOf(region))

        h.manager.onTrackingStopped()

        assertEquals(setOf(Constants.STATIONARY_REGION_ID), h.backend.registered.keys)
    }
}
