package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.GeofenceConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.data.GeofenceRuntime
import com.brickssoft.locationtracking.data.GeofenceStore
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.LatLng
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.OsGeofence
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeGeofenceBackend
import com.brickssoft.locationtracking.testing.FakeGeofenceStore
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.FakeRecordSink
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultGeofenceManagerTest {
    private val lat = Fixtures.LAT
    private val lng = Fixtures.LNG

    /** Wires a manager with fakes on the test scheduler. */
    private class Harness(
        scope: TestScope,
        supportsDwell: Boolean = true,
        enabled: Boolean = true,
        initialTriggerEntry: Boolean = true,
        val store: FakeGeofenceStore = FakeGeofenceStore(),
    ) {
        val clock = FakeClock(scheduler = scope.testScheduler)
        val backend = FakeGeofenceBackend(supportsDwell)
        val providers = FakeProviderFactory(geofenceBackend = backend)
        val configStore = FakeConfigStore(
            Config(geofence = GeofenceConfig(initialTriggerEntry = initialTriggerEntry)),
            RuntimeState(enabled = enabled),
        )
        val recordFactory = FakeRecordFactory(clock, configStore)
        val sink = FakeRecordSink()
        val events = RecordingEventBus()
        val manager = DefaultGeofenceManager(
            store, providers, configStore, recordFactory, sink, events, clock, scope.backgroundScope,
        )
        private var fixTime = Fixtures.FIX_TIME

        val geofenceEvents get() = events.ofType<TrackingEvent.Geofence>()
        val changes get() = events.ofType<TrackingEvent.GeofencesChange>()
        val hits get() = sink.ofEvent(RecordEvent.GEOFENCE).map { it.geofence!!.identifier to it.geofence!!.action }

        fun setEnabled(enabled: Boolean) {
            configStore.runtimeFlow.value = configStore.runtimeFlow.value.copy(enabled = enabled)
        }

        /** A fix [north]/[east] meters from the fixture centre, with strictly increasing times. */
        fun fix(north: Double, east: Double = 0.0, accuracy: Float = 5f): TrackedLocation {
            fixTime += 1_000
            return Fixtures.moved(Fixtures.location(accuracy = accuracy, time = fixTime), north, east, timeDeltaMs = 0)
        }
    }

    private suspend fun GeofenceManager.transition(id: String, action: GeofenceAction, location: TrackedLocation? = null) =
        onGeofenceTransitions(listOf(OsGeofenceTransition(id, action, location)))

    private suspend fun assertRejected(code: ErrorCode, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: TrackingException) {
            assertEquals(e.message, code, e.code)
        }
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    // ---- add / validation

    @Test
    fun `add rejects invalid geofences with INVALID_ARGUMENT and stores nothing`() = runTest {
        val h = Harness(this)
        val invalid = listOf(
            Fixtures.circle(identifier = ""),
            Fixtures.circle(identifier = "   "),
            Fixtures.circle(identifier = "x".repeat(101)),
            Fixtures.circle(identifier = Constants.STATIONARY_REGION_ID),
            Fixtures.circle(radius = 0f),
            Fixtures.circle(radius = -5f),
            Fixtures.circle(radius = Float.NaN),
            Fixtures.circle(latitude = 90.5),
            Fixtures.circle(longitude = -180.5),
            Fixtures.circle(latitude = Double.NaN),
            Fixtures.circle(loiteringDelay = -1),
            Fixtures.polygon(vertices = listOf(LatLng(lat, lng), LatLng(lat + 0.001, lng))),
            Fixtures.polygon(vertices = listOf(LatLng(lat, lng), LatLng(lat + 0.001, lng), LatLng(95.0, lng))),
            Fixtures.polygon(vertices = listOf(LatLng(lat, lng), LatLng(lat + 0.001, lng), LatLng(lat + 0.002, lng))),
            Fixtures.polygon(vertices = listOf(LatLng(lat, lng), LatLng(lat, lng), LatLng(lat + 0.001, lng + 0.001))),
            Fixtures.polygon(vertices = listOf(LatLng(0.0, 179.9), LatLng(0.1, -179.9), LatLng(-0.1, -179.9))),
        )

        for (spec in invalid) {
            assertRejected(ErrorCode.INVALID_ARGUMENT) { h.manager.add(listOf(Fixtures.circle("ok"), spec)) }
        }

        assertEquals(0, h.store.count())
        assertTrue(h.backend.addCalls.isEmpty())
        assertTrue(h.events.events.isEmpty())
    }

    @Test
    fun `more than 100 geofences in total is TOO_MANY_GEOFENCES`() = runTest {
        val h = Harness(this)
        h.manager.add((1..99).map { Fixtures.circle("g$it") })

        assertRejected(ErrorCode.TOO_MANY_GEOFENCES) {
            h.manager.add(listOf(Fixtures.circle("new1"), Fixtures.circle("new2")))
        }
        assertEquals(99, h.store.count())

        // Replacing existing ids does not count twice; exactly 100 is allowed.
        h.manager.add(listOf(Fixtures.circle("g1", radius = 300f), Fixtures.circle("new1")))
        assertEquals(100, h.store.count())
        assertEquals(300f, h.store.get("g1")!!.radius)

        assertRejected(ErrorCode.TOO_MANY_GEOFENCES) { h.manager.add(listOf(Fixtures.circle("new2"))) }
        assertEquals(100, h.store.count())
    }

    @Test
    fun `add while tracking is disabled stores and emits but does not register with the OS`() = runTest {
        val h = Harness(this, enabled = false)
        val home = Fixtures.circle("home", extras = """{"a":1}""")

        h.manager.add(listOf(home))

        assertEquals(listOf(home), h.store.all())
        assertTrue(h.backend.addCalls.isEmpty())
        assertEquals(listOf(TrackingEvent.GeofencesChange(on = listOf(home), off = emptyList())), h.changes)
        assertEquals(listOf(home), h.manager.list())
        assertEquals(home, h.manager.get("home"))
        assertNull(h.manager.get("nope"))
    }

    @Test
    fun `add while tracking registers circles with the OS`() = runTest {
        val h = Harness(this, initialTriggerEntry = false)

        h.manager.add(
            listOf(
                Fixtures.circle("a", radius = 150f, loiteringDelay = 60_000, notifyOnDwell = true),
                Fixtures.circle("b", notifyOnEntry = false),
            ),
        )

        assertEquals(
            OsGeofence("a", lat, lng, 150f, onEntry = true, onExit = true, onDwell = true, 60_000, initialTriggerEntry = false),
            h.backend.registered["a"],
        )
        assertEquals(
            OsGeofence("b", lat, lng, 100f, onEntry = false, onExit = true, onDwell = false, 30_000, initialTriggerEntry = false),
            h.backend.registered["b"],
        )
    }

    @Test
    fun `polygon gets a padded enclosing circle and is registered with ENTER and EXIT only`() = runTest {
        val h = Harness(this, initialTriggerEntry = false)
        val zone = Fixtures.polygon("zone", notifyOnDwell = true)

        h.manager.add(listOf(zone))

        val stored = h.store.get("zone")!!
        assertEquals(zone.vertices, stored.vertices)
        assertEquals(0.0, PolygonMath.distanceMeters(lat, lng, stored.latitude, stored.longitude), 1.0)
        assertEquals(100.0 * Math.sqrt(2.0) * 1.1, stored.radius.toDouble(), 1.0)
        for (v in zone.vertices!!) {
            assertTrue(PolygonMath.distanceMeters(stored.latitude, stored.longitude, v.latitude, v.longitude) < stored.radius)
        }
        val os = h.backend.registered.getValue("zone")
        assertEquals(stored.radius, os.radius)
        assertTrue(os.onEntry && os.onExit && !os.onDwell && os.initialTriggerEntry)
        assertEquals(listOf(stored), h.changes.single().on)
    }

    @Test
    fun `geofences with every notification off are stored but not registered`() = runTest {
        val h = Harness(this)

        h.manager.add(listOf(Fixtures.circle("mute", notifyOnEntry = false, notifyOnExit = false)))

        assertEquals(1, h.store.count())
        assertTrue(h.backend.registered.isEmpty())
    }

    @Test
    fun `failed OS registration rejects the add and rolls back`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("old")))
        h.backend.failWith = TrackingException(ErrorCode.PERMISSION_DENIED, "no background location")

        assertRejected(ErrorCode.PERMISSION_DENIED) {
            h.manager.add(listOf(Fixtures.circle("new"), Fixtures.circle("old", radius = 500f)))
        }

        assertEquals(listOf("old"), h.store.all().map { it.identifier })
        assertEquals(100f, h.store.get("old")!!.radius)
        assertEquals(1, h.changes.size)
    }

    // ---- remove

    @Test
    fun `remove unregisters, deletes and emits the removed ids`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("a"), Fixtures.circle("b"), Fixtures.circle("c")))
        h.events.clear()

        h.manager.remove(listOf("a", "c", "unknown"))

        assertEquals(listOf("b"), h.store.all().map { it.identifier })
        assertEquals(listOf(listOf("a", "c")), h.backend.removeCalls)
        assertEquals(setOf("b"), h.backend.registered.keys)
        assertEquals(listOf(TrackingEvent.GeofencesChange(emptyList(), listOf("a", "c"))), h.changes)

        h.manager.remove(listOf("unknown"))
        assertEquals(1, h.changes.size)
    }

    @Test
    fun `removeAll clears the OS registrations and the store`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("a"), Fixtures.circle("b")))
        h.events.clear()

        h.manager.removeAll()

        assertEquals(0, h.store.count())
        // The stored ids, not backend.removeAll(), which would also drop the engine's stationary region.
        assertTrue(h.backend.registered.isEmpty())
        assertEquals(0, h.backend.removeAllCalls)
        assertEquals(listOf(listOf("a", "b")), h.backend.removeCalls)
        assertEquals(listOf(TrackingEvent.GeofencesChange(emptyList(), listOf("a", "b"))), h.changes)
    }

    @Test
    fun `remove while tracking is disabled does not touch the OS`() = runTest {
        val h = Harness(this, enabled = false)
        h.manager.add(listOf(Fixtures.circle("a"), Fixtures.circle("b")))

        h.manager.remove(listOf("a"))
        h.manager.removeAll()

        assertTrue(h.backend.removeCalls.isEmpty())
        assertEquals(0, h.backend.removeAllCalls)
        assertEquals(0, h.store.count())
    }

    // ---- tracking lifecycle

    @Test
    fun `tracking start registers every stored geofence in batches and stop unregisters them`() = runTest {
        val h = Harness(this, enabled = false)
        h.manager.add((1..60).map { Fixtures.circle("g$it") })
        assertTrue(h.backend.addCalls.isEmpty())

        h.setEnabled(true)
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)

        assertEquals(listOf(25, 25, 10), h.backend.addCalls.map { it.size })
        assertEquals(60, h.backend.registered.size)

        h.manager.onTrackingStopped()

        assertTrue(h.backend.registered.isEmpty())
        assertEquals(60, h.store.count())
        assertFalse(h.manager.needsContinuousLocation.value)
    }

    @Test
    fun `a failing OS batch does not stop the others at tracking start`() = runTest {
        val h = Harness(this, enabled = false)
        h.manager.add((1..30).map { Fixtures.circle("g$it") })
        h.setEnabled(true)
        h.backend.failWith = TrackingException(ErrorCode.UNAVAILABLE, "down")

        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        assertTrue(h.backend.registered.isEmpty())
        assertEquals(30, h.store.count())
    }

    // ---- circle transitions

    @Test
    fun `circle ENTER and EXIT become geofence records and events`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("home", extras = """{"k":"v"}""")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        val enterFix = h.fix(10.0)
        val exitFix = h.fix(300.0)

        h.manager.transition("home", GeofenceAction.ENTER, enterFix)
        h.manager.transition("home", GeofenceAction.EXIT, exitFix)

        val records = h.sink.records
        assertEquals(2, records.size)
        assertTrue(records.all { it.event == RecordEvent.GEOFENCE })
        assertEquals(listOf(enterFix, exitFix), records.map { it.location })
        assertEquals("home", records[0].geofence!!.identifier)
        assertEquals(GeofenceAction.ENTER, records[0].geofence!!.action)
        assertEquals("""{"k":"v"}""", records[0].geofence!!.extras)
        assertEquals(GeofenceAction.EXIT, records[1].geofence!!.action)

        val events = h.geofenceEvents
        assertEquals(
            listOf(
                TrackingEvent.Geofence("home", GeofenceAction.ENTER, records[0], """{"k":"v"}"""),
                TrackingEvent.Geofence("home", GeofenceAction.EXIT, records[1], """{"k":"v"}"""),
            ),
            events,
        )
    }

    @Test
    fun `transition without a fix uses the last known location`() = runTest {
        val h = Harness(this)
        val last = Fixtures.location(latitude = 1.0, longitude = 2.0)
        h.configStore.runtimeFlow.value = h.configStore.runtimeFlow.value.copy(lastLocation = last)
        h.manager.add(listOf(Fixtures.circle("home")))

        h.manager.transition("home", GeofenceAction.ENTER)

        assertSame(last, h.sink.records.single().location)
    }

    @Test
    fun `notifyOn flags are respected`() = runTest {
        val h = Harness(this)
        h.manager.add(
            listOf(
                Fixtures.circle("noEnter", notifyOnEntry = false),
                Fixtures.circle("noExit", notifyOnExit = false),
                Fixtures.circle("noDwell", notifyOnDwell = false),
            ),
        )

        for (id in listOf("noEnter", "noExit", "noDwell")) {
            h.manager.transition(id, GeofenceAction.ENTER, h.fix(0.0))
            h.manager.transition(id, GeofenceAction.DWELL, h.fix(0.0))
            h.manager.transition(id, GeofenceAction.EXIT, h.fix(500.0))
        }

        assertEquals(
            listOf(
                "noEnter" to GeofenceAction.EXIT,
                "noExit" to GeofenceAction.ENTER,
                "noDwell" to GeofenceAction.ENTER,
                "noDwell" to GeofenceAction.EXIT,
            ),
            h.hits,
        )
        assertEquals(4, h.geofenceEvents.size)
    }

    @Test
    fun `unknown ids and transitions while stopped are ignored`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("home")))

        h.manager.transition("ghost", GeofenceAction.ENTER, h.fix(0.0))
        h.setEnabled(false)
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))

        assertTrue(h.sink.records.isEmpty())
        assertTrue(h.geofenceEvents.isEmpty())
    }

    @Test
    fun `transitions after onTrackingStopped are ignored`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("home")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        h.manager.onTrackingStopped()

        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))

        assertTrue(h.sink.records.isEmpty())
    }

    @Test
    fun `a repeated ENTER while inside is a duplicate`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("home")))

        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))
        h.manager.transition("home", GeofenceAction.EXIT, h.fix(500.0))
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))

        assertEquals(listOf(GeofenceAction.ENTER, GeofenceAction.EXIT, GeofenceAction.ENTER), h.hits.map { it.second })
    }

    @Test
    fun `OS dwell is passed through when the backend supports it`() = runTest {
        val h = Harness(this, supportsDwell = true)
        h.manager.add(listOf(Fixtures.circle("home", notifyOnDwell = true, loiteringDelay = 10_000)))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(GeofenceAction.ENTER), h.hits.map { it.second })

        h.manager.transition("home", GeofenceAction.DWELL, h.fix(0.0))
        assertEquals(listOf(GeofenceAction.ENTER, GeofenceAction.DWELL), h.hits.map { it.second })
    }

    @Test
    fun `dwell is synthesized after loiteringDelay when the backend lacks it`() = runTest {
        val h = Harness(this, supportsDwell = false)
        h.manager.add(listOf(Fixtures.circle("home", notifyOnEntry = false, notifyOnDwell = true, loiteringDelay = 30_000)))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        val os = h.backend.registered.getValue("home")
        assertTrue("ENTER is needed to start the timer", os.onEntry)
        assertTrue(os.onExit)
        assertFalse(os.onDwell)

        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))
        assertTrue("notifyOnEntry=false", h.hits.isEmpty())
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(h.hits.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("home" to GeofenceAction.DWELL), h.hits)
        assertEquals(GeofenceAction.DWELL, h.geofenceEvents.single().action)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, h.hits.size)
    }

    @Test
    fun `EXIT before loiteringDelay cancels the synthesized dwell`() = runTest {
        val h = Harness(this, supportsDwell = false)
        h.manager.add(listOf(Fixtures.circle("home", notifyOnDwell = true, loiteringDelay = 30_000)))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))
        advanceTimeBy(20_000)
        runCurrent()
        h.manager.transition("home", GeofenceAction.EXIT, h.fix(500.0))
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(GeofenceAction.ENTER, GeofenceAction.EXIT), h.hits.map { it.second })
    }

    @Test
    fun `overdue synthesized dwell fires on the next fix`() = runTest {
        val h = Harness(this, supportsDwell = false)
        h.manager.add(listOf(Fixtures.circle("home", notifyOnEntry = false, notifyOnDwell = true, loiteringDelay = 30_000)))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))
        runCurrent() // the timer is now suspended in its 30 s delay

        // The wall clock moves past the deadline while the coroutine timer has not run (deep sleep).
        h.clock.advance(31_000)
        h.manager.onLocation(h.fix(0.0))
        runCurrent()
        assertEquals(listOf("home" to GeofenceAction.DWELL), h.hits)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the timer was disarmed", 1, h.hits.size)
    }

    // ---- polygons

    @Test
    fun `polygon flow - circle ENTER, hit-test ENTER and EXIT, circle EXIT`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.polygon("zone", extras = """{"z":1}""")))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        assertFalse(h.manager.needsContinuousLocation.value)

        h.manager.transition("zone", GeofenceAction.ENTER)
        assertTrue(h.manager.needsContinuousLocation.value)
        assertTrue("circle ENTER alone is not a polygon ENTER", h.hits.isEmpty())
        assertEquals(GeofenceRuntime(insideCircle = true, insidePolygon = false, enteredAt = null), h.store.runtimes()["zone"])

        val inside = h.fix(0.0)
        h.manager.onLocation(inside)
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)
        assertSame(inside, h.sink.records.last().location)
        assertEquals("""{"z":1}""", h.geofenceEvents.last().extras)
        assertTrue(h.store.runtimes().getValue("zone").insidePolygon)

        val outside = h.fix(130.0) // inside the enclosing circle, outside the square
        h.manager.onLocation(outside)
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.ENTER, "zone" to GeofenceAction.EXIT), h.hits)
        assertSame(outside, h.sink.records.last().location)
        assertTrue("still inside the circle", h.manager.needsContinuousLocation.value)

        h.manager.transition("zone", GeofenceAction.EXIT)
        assertFalse(h.manager.needsContinuousLocation.value)
        assertEquals(2, h.hits.size)
    }

    @Test
    fun `leaving the enclosing circle while inside the polygon is a polygon EXIT`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        h.manager.transition("zone", GeofenceAction.ENTER, h.fix(0.0))
        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)

        val exitFix = h.fix(400.0)
        h.manager.transition("zone", GeofenceAction.EXIT, exitFix)

        assertEquals(listOf("zone" to GeofenceAction.ENTER, "zone" to GeofenceAction.EXIT), h.hits)
        assertSame(exitFix, h.sink.records.last().location)
        assertFalse(h.manager.needsContinuousLocation.value)
    }

    @Test
    fun `concave polygon uses the real shape, not the circle`() = runTest {
        val h = Harness(this)
        fun at(n: Double, e: Double) = Fixtures.moved(Fixtures.location(), n, e).let { LatLng(it.latitude, it.longitude) }
        val u = listOf(
            at(0.0, 0.0), at(0.0, 300.0), at(300.0, 300.0), at(300.0, 200.0),
            at(100.0, 200.0), at(100.0, 100.0), at(300.0, 100.0), at(300.0, 0.0),
        )
        h.manager.add(listOf(Fixtures.polygon("u", vertices = u)))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.onLocation(h.fix(250.0, 150.0)) // in the notch
        runCurrent()
        assertTrue(h.hits.isEmpty())

        h.manager.onLocation(h.fix(250.0, 50.0)) // in the left arm
        runCurrent()
        assertEquals(listOf("u" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `fixes straddling the boundary need two consecutive agreeing fixes`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        h.manager.onLocation(h.fix(300.0)) // certainly outside: state known
        runCurrent()

        h.manager.onLocation(h.fix(95.0, accuracy = 20f)) // inside, 5 m from the edge
        runCurrent()
        assertTrue(h.hits.isEmpty())
        h.manager.onLocation(h.fix(105.0, accuracy = 20f)) // outside again: resets the candidate
        runCurrent()
        h.manager.onLocation(h.fix(95.0, accuracy = 20f))
        runCurrent()
        assertTrue(h.hits.isEmpty())

        h.manager.onLocation(h.fix(90.0, accuracy = 20f))
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)

        // One accurate fix well inside the boundary is enough.
        h.manager.onLocation(h.fix(300.0, accuracy = 5f))
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.ENTER, "zone" to GeofenceAction.EXIT), h.hits)
    }

    @Test
    fun `polygon dwell is synthesized after loiteringDelay and cancelled by EXIT`() = runTest {
        val h = Harness(this, supportsDwell = true)
        h.manager.add(listOf(Fixtures.polygon("zone", notifyOnEntry = false, notifyOnDwell = true, loiteringDelay = 60_000)))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.onLocation(h.fix(0.0))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.DWELL), h.hits)

        h.manager.onLocation(h.fix(500.0))
        runCurrent()
        h.manager.onLocation(h.fix(0.0))
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        h.manager.onLocation(h.fix(500.0))
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(
            listOf("zone" to GeofenceAction.DWELL, "zone" to GeofenceAction.EXIT, "zone" to GeofenceAction.EXIT),
            h.hits,
        )
    }

    @Test
    fun `initialTriggerEntry false makes the first polygon classification silent`() = runTest {
        val h = Harness(this, initialTriggerEntry = false)
        h.manager.add(listOf(Fixtures.polygon("zone", notifyOnDwell = true, loiteringDelay = 1_000)))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.onLocation(h.fix(0.0))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(h.hits.isEmpty())

        h.manager.onLocation(h.fix(500.0))
        runCurrent()
        h.manager.onLocation(h.fix(0.0))
        runCurrent()

        assertEquals(listOf("zone" to GeofenceAction.EXIT, "zone" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `initialTriggerEntry true reports the first classification inside`() = runTest {
        val h = Harness(this, initialTriggerEntry = true)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.onLocation(h.fix(0.0))
        runCurrent()

        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `locations are ignored while tracking is disabled and stale fixes are skipped`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        val older = h.fix(500.0)
        val newer = h.fix(0.0)

        h.manager.onLocation(newer)
        h.manager.onLocation(older)
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)

        h.setEnabled(false)
        h.manager.onLocation(h.fix(500.0))
        runCurrent()
        assertEquals(1, h.hits.size)
    }

    // ---- runtime persistence

    @Test
    fun `polygon runtime is persisted and restored by a new process`() = runTest {
        val store = FakeGeofenceStore()
        val first = Harness(this, store = store)
        first.manager.add(listOf(Fixtures.polygon("zone")))
        first.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        first.manager.transition("zone", GeofenceAction.ENTER, first.fix(0.0))
        assertEquals(listOf("zone" to GeofenceAction.ENTER), first.hits)
        val persisted = store.runtimes().getValue("zone")
        assertTrue(persisted.insideCircle && persisted.insidePolygon && persisted.enteredAt != null)

        // A new process (tracking still enabled) restores the state: no second ENTER, and leaving is an EXIT.
        val second = Harness(this, store = store)
        second.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        assertTrue(second.manager.needsContinuousLocation.value)
        second.manager.transition("zone", GeofenceAction.ENTER) // initial trigger after re-registration
        second.manager.onLocation(second.fix(0.0))
        runCurrent()
        assertTrue(second.hits.isEmpty())

        second.manager.onLocation(second.fix(500.0))
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.EXIT), second.hits)
    }

    /** [spec] as `add` stores it (a polygon gets its enclosing circle). */
    private fun normalized(spec: GeofenceSpec): GeofenceSpec {
        val circle = PolygonMath.enclosingCircle(spec.vertices ?: return spec)
        return spec.copy(latitude = circle.latitude, longitude = circle.longitude, radius = circle.radius.toFloat())
    }

    /** A store holding the runtime a previous process left behind (tracking still enabled). */
    private suspend fun restoredStore(spec: GeofenceSpec, runtime: GeofenceRuntime) = FakeGeofenceStore().apply {
        upsert(listOf(normalized(spec)))
        setRuntime(spec.identifier, runtime)
    }

    @Test
    fun `restored circle re-arms the remaining synthesized dwell`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val store = restoredStore(
            Fixtures.circle("home", notifyOnDwell = true, loiteringDelay = 60_000),
            GeofenceRuntime(insideCircle = true, insidePolygon = false, enteredAt = clock.now() - 20_000),
        )
        val h = Harness(this, supportsDwell = false, store = store)

        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        advanceTimeBy(39_999)
        runCurrent()
        assertTrue(h.hits.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("home" to GeofenceAction.DWELL), h.hits)
    }

    @Test
    fun `restored dwell whose deadline passed while the process was gone is not re-armed`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val store = restoredStore(
            Fixtures.circle("home", notifyOnDwell = true, loiteringDelay = 60_000),
            GeofenceRuntime(insideCircle = true, insidePolygon = false, enteredAt = clock.now() - 61_000),
        )
        val h = Harness(this, supportsDwell = false, store = store)

        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        advanceTimeBy(600_000)
        runCurrent()

        assertTrue(h.hits.isEmpty())
    }

    @Test
    fun `restored inside circle reports the OS ENTER after re-registration because the state may be stale`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val store = restoredStore(Fixtures.circle("home"), GeofenceRuntime(true, false, clock.now() - 3_600_000))
        val h = Harness(this, store = store)
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))

        assertEquals("the second one is a duplicate", listOf("home" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `a fix certainly outside a restored inside circle reports the EXIT missed while the process was gone`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val store = restoredStore(Fixtures.circle("home", radius = 100f), GeofenceRuntime(true, false, clock.now() - 3_600_000))
        val h = Harness(this, store = store)
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.onLocation(h.fix(120.0, accuracy = 30f)) // could still be inside
        runCurrent()
        assertTrue(h.hits.isEmpty())
        val far = h.fix(400.0)
        h.manager.onLocation(far)
        runCurrent()
        h.manager.onLocation(h.fix(500.0))
        runCurrent()

        assertEquals(listOf("home" to GeofenceAction.EXIT), h.hits)
        assertSame(far, h.sink.records.single().location)
        assertEquals(GeofenceRuntime(false, false, null), store.runtimes()["home"])
    }

    @Test
    fun `a fix confirming a restored inside circle makes the later OS initial ENTER a duplicate`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val store = restoredStore(Fixtures.circle("home", radius = 100f), GeofenceRuntime(true, false, clock.now() - 3_600_000))
        val h = Harness(this, store = store)
        h.manager.onTrackingStarted(TrackingMode.LOCATION)

        h.manager.onLocation(h.fix(10.0))
        runCurrent()
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(10.0))

        assertTrue(h.hits.isEmpty())
    }

    @Test
    fun `stale runtime is discarded when loaded while tracking is disabled`() = runTest {
        val store = FakeGeofenceStore()
        store.upsert(listOf(Fixtures.circle("home")))
        store.setRuntime("home", GeofenceRuntime(true, false, 1L))
        val h = Harness(this, enabled = false, store = store)

        h.manager.add(listOf(Fixtures.circle("other")))

        assertEquals(GeofenceRuntime(false, false, null), store.runtimes()["home"])
    }

    @Test
    fun `tracking stop resets runtime and cancels pending dwell`() = runTest {
        val h = Harness(this, supportsDwell = false)
        h.manager.add(listOf(Fixtures.circle("home", notifyOnDwell = true), Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))
        h.manager.transition("zone", GeofenceAction.ENTER, h.fix(0.0))
        assertTrue(h.manager.needsContinuousLocation.value)

        h.manager.onTrackingStopped()
        advanceTimeBy(120_000)
        runCurrent()

        assertFalse(h.manager.needsContinuousLocation.value)
        assertEquals(listOf(GeofenceAction.ENTER, GeofenceAction.ENTER), h.hits.map { it.second })
        val reset = GeofenceRuntime(false, false, null)
        assertEquals(mapOf("home" to reset, "zone" to reset), h.store.runtimes())
    }

    @Test
    fun `replacing a geofence resets its state`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.circle("home")))
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))

        h.manager.add(listOf(Fixtures.circle("home", radius = 250f)))
        h.manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))

        assertEquals(listOf(GeofenceAction.ENTER, GeofenceAction.ENTER), h.hits.map { it.second })
        assertEquals(250f, h.backend.registered.getValue("home").radius)
    }

    @Test
    fun `silent initial polygon state does not re-arm a dwell after a restart`() = runTest {
        val store = FakeGeofenceStore()
        val first = Harness(this, initialTriggerEntry = false, store = store)
        first.manager.add(listOf(Fixtures.polygon("zone", notifyOnDwell = true, loiteringDelay = 60_000)))
        first.manager.onTrackingStarted(TrackingMode.LOCATION)
        first.manager.onLocation(first.fix(0.0))
        runCurrent()
        assertTrue(store.runtimes().getValue("zone").insidePolygon)

        val second = Harness(this, initialTriggerEntry = false, store = store)
        second.manager.onTrackingStarted(TrackingMode.LOCATION)
        advanceTimeBy(600_000)
        runCurrent()

        assertTrue(first.hits.isEmpty())
        assertTrue(second.hits.isEmpty())
    }

    // ---- polygon edge cases

    @Test
    fun `initialTriggerEntry false still reports a polygon entered after the initial window`() = runTest {
        val h = Harness(this, initialTriggerEntry = false)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        advanceTimeBy(3 * 60_000)

        // A real circle entry long after registration: the polygon was outside, so the first fix inside is an ENTER.
        h.manager.transition("zone", GeofenceAction.ENTER, h.fix(0.0))

        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `initialTriggerEntry false - a first fix after the window without an initial circle ENTER is an ENTER`() = runTest {
        val h = Harness(this, initialTriggerEntry = false)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        advanceTimeBy(3 * 60_000) // no OS initial ENTER: we were outside the circle at registration

        h.manager.onLocation(h.fix(0.0))
        runCurrent()

        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `the OS initial circle ENTER keeps the first classification silent even if the fix is late`() = runTest {
        val h = Harness(this, initialTriggerEntry = false)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        advanceTimeBy(5_000)
        h.manager.transition("zone", GeofenceAction.ENTER)
        advanceTimeBy(5 * 60_000) // a slow first fix

        h.manager.onLocation(h.fix(0.0))
        runCurrent()
        assertTrue(h.hits.isEmpty())

        h.manager.onLocation(h.fix(130.0))
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.EXIT), h.hits)
    }

    @Test
    fun `a fix certainly outside the enclosing circle clears a stale circle state`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val store = restoredStore(Fixtures.polygon("zone"), GeofenceRuntime(true, false, clock.now() - 60_000))
        val h = Harness(this, store = store)
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        assertTrue(h.manager.needsContinuousLocation.value)

        h.manager.onLocation(h.fix(5_000.0))
        runCurrent()

        assertFalse(h.manager.needsContinuousLocation.value)
        assertFalse(store.runtimes().getValue("zone").insideCircle)
        assertTrue(h.hits.isEmpty())
    }

    @Test
    fun `the same fix from the OS transition and the location stream counts once`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        h.manager.onLocation(h.fix(300.0)) // known outside
        runCurrent()
        val straddling = h.fix(95.0, accuracy = 20f)

        h.manager.transition("zone", GeofenceAction.ENTER, straddling)
        h.manager.onLocation(straddling)
        runCurrent()
        assertTrue(h.hits.isEmpty())

        h.manager.onLocation(h.fix(94.0, accuracy = 20f))
        runCurrent()
        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `a fix delivered with one polygon's OS ENTER is still used for the other geofences`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.polygon("a"), Fixtures.polygon("b")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        h.manager.onLocation(h.fix(300.0)) // both known outside
        runCurrent()
        val inside = h.fix(0.0)

        h.manager.transition("a", GeofenceAction.ENTER, inside)
        h.manager.onLocation(inside)
        runCurrent()

        assertEquals(listOf("a" to GeofenceAction.ENTER, "b" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `initialTriggerEntry false - a fresh fix outside at registration makes an early entry an ENTER`() = runTest {
        val h = Harness(this, initialTriggerEntry = false)
        val far = Fixtures.moved(Fixtures.location(time = h.clock.now() - 10_000), 2_000.0)
        h.configStore.runtimeFlow.value = h.configStore.runtimeFlow.value.copy(lastLocation = far)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)
        advanceTimeBy(20_000) // well inside the initial-trigger window

        h.manager.transition("zone", GeofenceAction.ENTER, h.fix(0.0))

        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `a stale last fix does not settle the initial state`() = runTest {
        val h = Harness(this, initialTriggerEntry = false)
        val far = Fixtures.moved(Fixtures.location(time = h.clock.now() - 3_600_000), 2_000.0)
        h.configStore.runtimeFlow.value = h.configStore.runtimeFlow.value.copy(lastLocation = far)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.GEOFENCES)

        h.manager.transition("zone", GeofenceAction.ENTER, h.fix(0.0))

        assertTrue("treated as the OS initial trigger", h.hits.isEmpty())
    }

    @Test
    fun `a fix with a bogus future timestamp does not block later fixes`() = runTest {
        val h = Harness(this)
        h.manager.add(listOf(Fixtures.polygon("zone")))
        h.manager.onTrackingStarted(TrackingMode.LOCATION)
        val bogus = h.fix(500.0).let { it.copy(time = it.time + 86_400_000) }

        h.manager.onLocation(bogus)
        runCurrent()
        h.manager.onLocation(h.fix(0.0))
        runCurrent()

        assertEquals(listOf("zone" to GeofenceAction.ENTER), h.hits)
    }

    @Test
    fun `tracking start after a failed load still processes later transitions`() = runTest {
        val h = Harness(this)
        h.store.upsert(listOf(Fixtures.circle("home")))
        var failing = true
        val flaky = object : GeofenceStore by h.store {
            override suspend fun all(): List<GeofenceSpec> = if (failing) throw IOException("disk") else h.store.all()
        }
        val manager = DefaultGeofenceManager(
            flaky, h.providers, h.configStore, h.recordFactory, h.sink, h.events, h.clock, backgroundScope,
        )
        manager.onTrackingStopped()

        manager.onTrackingStarted(TrackingMode.LOCATION)
        failing = false
        manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))

        assertEquals(listOf("home" to GeofenceAction.ENTER), h.hits)
    }

    // ---- provider change

    @Test
    fun `registrations move to the new backend when the provider changes`() = runTest {
        val h = Harness(this)
        val hms = FakeGeofenceBackend(supportsDwell = false, kind = ProviderKind.HMS)
        var current: GeofenceBackend = h.backend
        val switching = object : ProviderFactory by h.providers {
            override fun geofence(): GeofenceBackend = current
        }
        val manager = DefaultGeofenceManager(
            h.store, switching, h.configStore, h.recordFactory, h.sink, h.events, h.clock, backgroundScope,
        )
        manager.add(listOf(Fixtures.circle("a"), Fixtures.circle("b")))
        manager.onTrackingStarted(TrackingMode.LOCATION)
        assertEquals(setOf("a", "b"), h.backend.registered.keys)

        current = hms
        manager.add(listOf(Fixtures.circle("c")))

        assertTrue(h.backend.registered.isEmpty())
        assertEquals(setOf("a", "b", "c"), hms.registered.keys)

        manager.onTrackingStopped()
        assertTrue(hms.registered.isEmpty())
        assertNotNull(h.store.get("c"))
    }

    @Test
    fun `moving to a backend with dwell support cancels synthesized dwell timers`() = runTest {
        val h = Harness(this, supportsDwell = false)
        val gms = FakeGeofenceBackend(supportsDwell = true, kind = ProviderKind.GMS)
        val android = FakeGeofenceBackend(supportsDwell = false, kind = ProviderKind.ANDROID)
        var current: GeofenceBackend = android
        val switching = object : ProviderFactory by h.providers {
            override fun geofence(): GeofenceBackend = current
        }
        val manager = DefaultGeofenceManager(
            h.store, switching, h.configStore, h.recordFactory, h.sink, h.events, h.clock, backgroundScope,
        )
        manager.add(listOf(Fixtures.circle("home", notifyOnEntry = false, notifyOnDwell = true, loiteringDelay = 30_000)))
        manager.onTrackingStarted(TrackingMode.LOCATION)
        manager.transition("home", GeofenceAction.ENTER, h.fix(0.0))

        current = gms
        manager.add(listOf(Fixtures.circle("other")))
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue("the OS reports DWELL now", h.hits.isEmpty())
        assertTrue(gms.registered.getValue("home").onDwell)
        manager.transition("home", GeofenceAction.DWELL, h.fix(0.0))
        assertEquals(listOf("home" to GeofenceAction.DWELL), h.hits)
    }

    @Test
    fun `list and get read the store`() = runTest {
        val h = Harness(this)
        val spec: GeofenceSpec = Fixtures.circle("x")
        h.store.upsert(listOf(spec))

        assertEquals(listOf(spec), h.manager.list())
        assertEquals(spec, h.manager.get("x"))
    }
}
