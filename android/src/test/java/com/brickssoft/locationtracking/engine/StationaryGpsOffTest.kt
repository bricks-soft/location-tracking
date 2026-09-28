package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.LocationProviderSetting
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.processing.FilterResult
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.OsGeofence
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import com.brickssoft.locationtracking.testing.FakeActivityBackend
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeGeofenceBackend
import com.brickssoft.locationtracking.testing.FakeGeofenceManager
import com.brickssoft.locationtracking.testing.FakeHeartbeatScheduler
import com.brickssoft.locationtracking.testing.FakeHttpSyncer
import com.brickssoft.locationtracking.testing.FakeLocationBackend
import com.brickssoft.locationtracking.testing.FakeLocationProcessor
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.FakeOdometer
import com.brickssoft.locationtracking.testing.FakePermissionManager
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.FakeServiceController
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stationary GPS-off mode of [DefaultTrackingEngine] (architecture round 2, §3): the stationary region, the PASSIVE
 * and LOW-power requests, the ways out of STATIONARY and `runtime.lastLocation` while STATIONARY. Collaborators are
 * fakes; the geofence backend is the "OS" the region is registered with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StationaryGpsOffTest {
    private val configStore = FakeConfigStore()
    private val locationBackend = FakeLocationBackend(ProviderKind.GMS)
    private val activityBackend = FakeActivityBackend(ProviderKind.GMS)
    private val geofenceBackend = FakeGeofenceBackend(supportsDwell = true, kind = ProviderKind.GMS)
    private val providers = FakeProviderFactory(ProviderKind.GMS, locationBackend, activityBackend, geofenceBackend)
    private val processor = FakeLocationProcessor()
    private val odometer = FakeOdometer()
    private val recordSink = PersistingSink(configStore)
    private val heartbeat = FakeHeartbeatScheduler()
    private val geofences = FakeGeofenceManager()
    private val service = FakeServiceController()
    private val device = FakeDeviceMonitor()
    private val syncer = FakeHttpSyncer()
    private val permissions = FakePermissionManager()
    private val events = RecordingEventBus()
    private val logs = FakeLogStore()
    private lateinit var clock: FakeClock

    private val origin = Fixtures.location(accuracy = 5f, speed = 0f)
    private val geolocation get() = configStore.config.value.geolocation
    private val passiveSpec get() = LocationRequests.passive(geolocation)
    private val fallbackSpec get() = LocationRequests.fallback(geolocation)
    private val movingSpec get() = LocationRequests.moving(geolocation)

    @Before
    fun setUp() {
        Logger.sink = logs
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    /** Collects records and, like `DefaultRecordSink`, makes a record's location `runtime.lastLocation`. */
    private class PersistingSink(private val store: ConfigStore) : RecordSink {
        val records = CopyOnWriteArrayList<Record>()

        override suspend fun submit(record: Record): Record {
            records += record
            store.updateRuntime { it.copy(lastLocation = record.location ?: it.lastLocation) }
            return record
        }
    }

    private fun TestScope.newEngine(providerFactory: ProviderFactory = providers): DefaultTrackingEngine {
        clock = FakeClock(scheduler = testScheduler)
        return DefaultTrackingEngine(
            configStore, providerFactory, processor, odometer, FakeRecordFactory(clock, configStore), recordSink,
            heartbeat, geofences, service, device, syncer, permissions, events, clock, backgroundScope,
        )
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.emit(vararg fixes: TrackedLocation) {
        locationBackend.emit(*fixes)
        runCurrent()
    }

    /** Starts LOCATION tracking with [at] as the initial fix and settles the initial motionchange. */
    private suspend fun TestScope.started(engine: DefaultTrackingEngine, at: TrackedLocation? = origin) {
        locationBackend.currentLocation = at
        engine.start()
        runCurrent()
    }

    private suspend fun TestScope.transition(
        engine: DefaultTrackingEngine,
        location: TrackedLocation?,
        action: GeofenceAction = GeofenceAction.EXIT,
    ) {
        engine.onStationaryRegionTransition(OsGeofenceTransition(Constants.STATIONARY_REGION_ID, action, location))
        runCurrent()
    }

    private fun records(): List<String> = recordSink.records.map { r ->
        when (r.event) {
            RecordEvent.MOTIONCHANGE -> "motionchange:${r.isMoving}"
            RecordEvent.TRACKING_START, RecordEvent.TRACKING_STOP -> "${r.event.wire}:${r.reason}"
            else -> r.event.wire
        }
    }

    private fun activeSpec(backend: FakeLocationBackend = locationBackend): LocationRequestSpec? =
        backend.active.values.singleOrNull()

    private fun region(backend: FakeGeofenceBackend = geofenceBackend): OsGeofence? =
        backend.registered[Constants.STATIONARY_REGION_ID]

    private fun regionRemovals(backend: FakeGeofenceBackend = geofenceBackend): Int =
        backend.removeCalls.count { it == listOf(Constants.STATIONARY_REGION_ID) }

    private fun configure(transform: (GeolocationConfig) -> GeolocationConfig) {
        configStore.update { it.copy(geolocation = transform(it.geolocation)) }
    }

    private fun expectedRegion(anchor: TrackedLocation, radius: Float = 150f) = OsGeofence(
        id = Constants.STATIONARY_REGION_ID,
        latitude = anchor.latitude,
        longitude = anchor.longitude,
        radius = radius,
        onEntry = false,
        onExit = true,
        onDwell = false,
        loiteringDelayMs = 0,
        initialTriggerEntry = false,
    )

    private fun assertNoErrorsLogged() {
        val errors = logs.lines.filter { it.level == LogLevel.ERROR }
        assertTrue("errors were logged: $errors", errors.isEmpty())
    }

    // ------------------------------------------------------------------ entering STATIONARY

    @Test
    fun `the initial fix after start registers the region around it and requests passive updates`() = runTest {
        val engine = newEngine()

        started(engine)

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertEquals(expectedRegion(origin), region())
        assertEquals(passiveSpec, activeSpec())
        assertEquals(DesiredAccuracy.PASSIVE, activeSpec()?.accuracy)
        assertEquals(60_000L, activeSpec()?.intervalMs)
        // While the initial fix was computed (by its own request) the passive request already ran: one request.
        assertEquals(listOf(passiveSpec), locationBackend.requests.map { it.first })
        assertEquals(1, geofenceBackend.addCalls.size)
        // The foreground service and the heartbeat keep running.
        assertTrue(service.isRunning)
        assertEquals(1, heartbeat.startCalls)
        assertEquals(0, heartbeat.stopCalls)
        assertNoErrorsLogged()
    }

    @Test
    fun `the region radius is the stationary radius, at least 150 m, and follows config changes`() = runTest {
        configure { it.copy(stationaryRadius = 400.0) }
        val engine = newEngine()
        started(engine)
        assertEquals(expectedRegion(origin, radius = 400f), region())
        val adds = geofenceBackend.addCalls.size

        engine.setConfig(JSONObject().put("heartbeat", JSONObject().put("minInterval", 240)))
        assertEquals(adds, geofenceBackend.addCalls.size) // unrelated change: no OS call

        engine.setConfig(JSONObject().put("geolocation", JSONObject().put("stationaryRadius", 20.0)))
        assertEquals(expectedRegion(origin, radius = 150f), region())
        assertEquals(adds + 1, geofenceBackend.addCalls.size)
    }

    @Test
    fun `the stop timeout registers the region at the last fix and turns GPS off`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        assertNull(region())
        assertEquals(1, regionRemovals())
        assertEquals(movingSpec, activeSpec())

        val last = Fixtures.moved(origin, 300.0)
        emit(last)
        advance(5 * MINUTE)

        assertEquals("motionchange:false", records().last())
        assertSame(last, recordSink.records.last().location)
        assertEquals(expectedRegion(last), region())
        assertEquals(passiveSpec, activeSpec())
    }

    @Test
    fun `changePace(false) registers the region at the best known location`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        val here = Fixtures.moved(origin, 500.0)
        emit(here)

        engine.changePace(false)

        assertEquals("motionchange:false", records().last())
        assertEquals(expectedRegion(here), region())
        assertEquals(passiveSpec, activeSpec())
    }

    @Test
    fun `restore with isMoving false registers the region again after the initial fix`() = runTest {
        // GMS keeps geofences across a process death: the previous process's region is still registered.
        geofenceBackend.add(listOf(expectedRegion(origin)))
        configStore.runtimeFlow.value = RuntimeState(enabled = true, isMoving = false, lastLocation = origin)
        val engine = newEngine()
        val fresh = Fixtures.moved(origin, 3.0)
        locationBackend.currentLocation = fresh

        engine.restore("restore")
        runCurrent()

        assertEquals(listOf("tracking_start:restore", "motionchange:false"), records())
        assertEquals(expectedRegion(fresh), region()) // same id: replaced, not duplicated
        assertEquals(1, geofenceBackend.registered.size)
        assertEquals(passiveSpec, activeSpec())
    }

    @Test
    fun `a fix delivered before the initial fix does not stay the anchor or the region center`() = runTest {
        val slow = object : LocationBackend by locationBackend {
            override suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation? {
                delay(5_000)
                return origin
            }
        }
        val factory = object : ProviderFactory by providers {
            override fun location(): LocationBackend = slow
        }
        val engine = newEngine(factory)
        engine.start()
        runCurrent()

        // The passive request delivers another app's older fix 400 m away before the initial fix.
        val cached = Fixtures.moved(origin, 400.0, timeDeltaMs = -120_000).copy(accuracy = 30f)
        emit(cached)
        assertNull(region()) // no region before the initial motionchange
        assertEquals(passiveSpec, activeSpec())

        advance(5_000)

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertSame(origin, recordSink.records.last().location)
        assertEquals(expectedRegion(origin), region())
        assertEquals(1, geofenceBackend.registered.size)
        assertSame(origin, configStore.runtime.value.lastLocation)
        // A fix at the real position stays STATIONARY.
        emit(Fixtures.moved(origin, 10.0))
        assertFalse(configStore.runtime.value.isMoving)
        assertEquals(passiveSpec, activeSpec())
    }

    // ------------------------------------------------------------------ while STATIONARY

    @Test
    fun `STATIONARY fixes are not recorded, not counted and keep the anchor as lastLocation`() = runTest {
        val engine = newEngine()
        started(engine)
        assertSame(origin, configStore.runtime.value.lastLocation)

        advance(2 * MINUTE)
        val near = Fixtures.moved(origin, 15.0, timeDeltaMs = 2 * MINUTE)
        val precise = Fixtures.moved(origin, 5.0, timeDeltaMs = 3 * MINUTE).copy(accuracy = 3f)
        emit(near)

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertTrue(odometer.locations.isEmpty())
        // Heartbeats carry runtime.lastLocation: still the anchor fix, with the time it was acquired.
        assertSame(origin, configStore.runtime.value.lastLocation)
        assertEquals(origin.time, configStore.runtime.value.lastLocation?.time)

        // A more accurate fix inside the radius becomes the anchor, and so the heartbeats' location.
        advance(MINUTE)
        emit(precise)
        assertEquals(2, recordSink.records.size)
        assertTrue(odometer.locations.isEmpty())
        assertEquals(listOf(origin, near, precise), geofences.locations)
        assertSame(precise, configStore.runtime.value.lastLocation)
        // The region is not moved for 5 m.
        assertEquals(expectedRegion(origin), region())
        assertEquals(1, geofenceBackend.addCalls.size)
        assertEquals(passiveSpec, activeSpec())
    }

    @Test
    fun `a passive fix certainly outside the radius with good accuracy leaves STATIONARY`() = runTest {
        val engine = newEngine()
        started(engine)

        val outside = Fixtures.moved(origin, 100.0).copy(accuracy = 10f)
        emit(outside)

        assertEquals("motionchange:true", records().last())
        assertSame(outside, recordSink.records.last().location)
        assertTrue(configStore.runtime.value.isMoving)
        assertNull(region())
        assertEquals(movingSpec, activeSpec())
        assertEquals(listOf(outside), odometer.locations)
    }

    @Test
    fun `a coarse passive fix far outside never wakes GPS by itself`() = runTest {
        configure { it.copy(filter = it.filter.copy(trackingAccuracyThreshold = 50.0)) }
        val engine = newEngine()
        started(engine)

        emit(Fixtures.moved(origin, 2_000.0).copy(accuracy = 60f))

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertFalse(configStore.runtime.value.isMoving)
        assertEquals(expectedRegion(origin), region())
        assertEquals(passiveSpec, activeSpec())
    }

    @Test
    fun `a passive fix within the radius plus its accuracy stays STATIONARY`() = runTest {
        val engine = newEngine()
        started(engine)

        // 60 m away with 40 m accuracy: at most 20 m from the anchor, inside the 25 m radius.
        emit(Fixtures.moved(origin, 60.0).copy(accuracy = 40f))

        assertEquals(2, recordSink.records.size)
        assertFalse(configStore.runtime.value.isMoving)
        assertEquals(passiveSpec, activeSpec())
    }

    @Test
    fun `a confident moving activity still leaves STATIONARY and removes the region`() = runTest {
        val engine = newEngine()
        started(engine)

        engine.onActivitySamples(listOf(ActivitySample(ActivityType.IN_VEHICLE, 90)))
        runCurrent()

        assertEquals("motionchange:true", records().last())
        assertSame(origin, recordSink.records.last().location)
        assertNull(region())
        assertEquals(movingSpec, activeSpec())
    }

    @Test
    fun `polygon demand forces the configured request while the region stays registered`() = runTest {
        val engine = newEngine()
        started(engine)

        geofences.needsContinuousLocation.value = true
        runCurrent()
        assertEquals(movingSpec, activeSpec())
        assertEquals(expectedRegion(origin), region())

        geofences.needsContinuousLocation.value = false
        runCurrent()
        assertEquals(passiveSpec, activeSpec())
    }

    // ------------------------------------------------------------------ the region's EXIT

    @Test
    fun `region EXIT leaves STATIONARY - region removed, configured request, motionchange with the exit fix`() =
        runTest {
            val engine = newEngine()
            started(engine)
            advance(2 * MINUTE)

            val exitFix = Fixtures.moved(origin, 200.0).copy(accuracy = 30f)
            transition(engine, exitFix)

            assertEquals(listOf("tracking_start:start", "motionchange:false", "motionchange:true"), records())
            val motion = recordSink.records.last()
            assertSame(exitFix, motion.location)
            assertTrue(motion.isMoving)
            assertTrue(configStore.runtime.value.isMoving)
            assertNull(region())
            assertEquals(1, regionRemovals())
            assertEquals(movingSpec, activeSpec())
            assertEquals(listOf(exitFix), odometer.locations)
            assertEquals(exitFix, geofences.locations.last())
            // The stationary region is not a user geofence: no geofence record or event.
            assertTrue(recordSink.records.none { it.event == RecordEvent.GEOFENCE })
            assertTrue(events.ofType<TrackingEvent.Geofence>().isEmpty())

            // GPS fixes are recorded again.
            emit(Fixtures.moved(exitFix, 50.0).copy(accuracy = 5f))
            assertEquals("location", records().last())
            assertNoErrorsLogged()
        }

    @Test
    fun `region EXIT with a rejected or missing fix records the best known location`() = runTest {
        val engine = newEngine()
        started(engine)

        processor.scripted.addLast(FilterResult.Rejected("accuracy"))
        transition(engine, Fixtures.moved(origin, 200.0).copy(accuracy = 900f))

        assertEquals("motionchange:true", records().last())
        assertSame(origin, recordSink.records.last().location)
        assertTrue(odometer.locations.isEmpty())

        engine.changePace(false)
        transition(engine, location = null)
        assertEquals("motionchange:true", records().last())
        assertSame(origin, recordSink.records.last().location)
    }

    @Test
    fun `region EXIT while MOVING and region ENTER are ignored`() = runTest {
        val engine = newEngine()
        started(engine)

        transition(engine, origin, GeofenceAction.ENTER)
        assertEquals(2, recordSink.records.size)
        assertEquals(expectedRegion(origin), region())

        engine.changePace(true)
        val removals = regionRemovals()
        transition(engine, Fixtures.moved(origin, 300.0))

        assertEquals(3, recordSink.records.size)
        assertTrue(configStore.runtime.value.isMoving)
        assertEquals(removals + 1, regionRemovals()) // a late EXIT: the OS registration is removed (again)
    }

    @Test
    fun `region EXIT in a process where tracking is enabled but not running restores and leaves STATIONARY`() =
        runTest {
            configStore.runtimeFlow.value = RuntimeState(enabled = true, lastLocation = origin)
            geofenceBackend.add(listOf(expectedRegion(origin))) // registered by the dead process
            val engine = newEngine()
            locationBackend.currentLocation = origin

            val exitFix = Fixtures.moved(origin, 250.0)
            transition(engine, exitFix)
            advance(MINUTE) // the restore's initial fix finds the motion already recorded

            assertEquals(listOf("tracking_start:restore", "motionchange:true"), records())
            assertSame(exitFix, recordSink.records.last().location)
            assertEquals(1, service.startCalls)
            assertNull(region())
            assertEquals(movingSpec, activeSpec())
        }

    @Test
    fun `region EXIT while tracking is off removes the region and records nothing`() = runTest {
        geofenceBackend.add(listOf(expectedRegion(origin)))
        val engine = newEngine()

        transition(engine, Fixtures.moved(origin, 250.0))

        assertTrue(recordSink.records.isEmpty())
        assertNull(region())
        assertEquals(0, service.startCalls)
    }

    // ------------------------------------------------------------------ fallback

    @Test
    fun `a failed registration falls back to one low-power fix per 3 minutes and is not retried on every fix`() =
        runTest {
            geofenceBackend.failWith = TrackingException(ErrorCode.PERMISSION_DENIED, "no background location")
            val engine = newEngine()

            started(engine)

            assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
            assertNull(region())
            assertEquals(fallbackSpec, activeSpec())
            assertEquals(DesiredAccuracy.LOW, activeSpec()?.accuracy)
            assertEquals(180_000L, activeSpec()?.intervalMs)
            assertEquals(180_000L, activeSpec()?.fastestIntervalMs)
            assertTrue(logs.lines.any { it.level == LogLevel.WARN && "low-power" in it.message })
            assertNoErrorsLogged()

            geofenceBackend.failWith = null
            emit(Fixtures.moved(origin, 5.0))
            assertNull(region())
            assertEquals(fallbackSpec, activeSpec())

            // The fallback still detects movement without GPS.
            val outside = Fixtures.moved(origin, 150.0).copy(accuracy = 20f)
            emit(outside)
            assertEquals("motionchange:true", records().last())
            assertEquals(movingSpec, activeSpec())
        }

    @Test
    fun `location services back on register the region again and a failed one switches to passive`() = runTest {
        geofenceBackend.failWith = TrackingException(ErrorCode.UNAVAILABLE, "location is off")
        val engine = newEngine()
        started(engine)
        assertEquals(fallbackSpec, activeSpec())

        geofenceBackend.failWith = null
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = false, gps = false)))
        runCurrent()
        assertNull(region())
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = true)))
        runCurrent()

        assertEquals(expectedRegion(origin), region())
        assertEquals(passiveSpec, activeSpec())
        assertEquals(2, geofences.startedModes.size)

        // GMS / HMS drop every geofence when location is switched off; switched on again, the region comes back.
        geofenceBackend.removeAll()
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = false, gps = false)))
        runCurrent()
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = true)))
        runCurrent()
        assertEquals(expectedRegion(origin), region())
    }

    @Test
    fun `a registration without an answer within 10 s falls back to the low-power request`() = runTest {
        val hanging = HangingGeofenceBackend()
        val factory = object : ProviderFactory by providers {
            override fun geofence(): GeofenceBackend = hanging
        }
        val engine = newEngine(factory)
        locationBackend.currentLocation = origin
        engine.start()
        runCurrent()
        assertEquals(listOf("tracking_start:start", "motionchange:false"), records()) // recorded before the OS call

        advance(10_000)

        assertEquals(fallbackSpec, activeSpec())
        assertEquals(1, hanging.addCalls)

        engine.stop()
        assertEquals(1, hanging.removeCalls) // the late registration may still happen: it is removed on stop
        assertNoErrorsLogged()
    }

    @Test
    fun `without any known location the fallback runs until the first fix becomes the anchor`() = runTest {
        val engine = newEngine()
        started(engine, at = null)

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertNull(recordSink.records.last().location)
        assertNull(region())
        assertEquals(fallbackSpec, activeSpec())

        emit(origin)

        assertEquals(2, recordSink.records.size)
        assertEquals(expectedRegion(origin), region())
        assertEquals(passiveSpec, activeSpec())
        assertSame(origin, configStore.runtime.value.lastLocation) // the heartbeats get the anchor
    }

    // ------------------------------------------------------------------ stop, backends

    @Test
    fun `stop removes the region, also in a process that did not register it`() = runTest {
        val engine = newEngine()
        started(engine)

        engine.stop()

        assertEquals("tracking_stop:stop", records().last())
        assertNull(region())
        assertFalse(locationBackend.isRequesting)

        // A new process after a kill: the OS still holds the region of the dead one.
        geofenceBackend.add(listOf(expectedRegion(origin)))
        configStore.runtimeFlow.value = configStore.runtimeFlow.value.copy(enabled = true)
        val cold = newEngine()
        cold.stop()
        assertNull(region())
    }

    @Test
    fun `stopOnStationary does not register the region before stopping`() = runTest {
        configure { it.copy(stopOnStationary = true) }
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        val adds = geofenceBackend.addCalls.size

        advance(5 * MINUTE)

        assertEquals("tracking_stop:stop_on_stationary", records().last())
        assertEquals(adds, geofenceBackend.addCalls.size)
        assertNull(region())
    }

    @Test
    fun `a backend switch moves the region to the new backend`() = runTest {
        val hmsLocation = FakeLocationBackend(ProviderKind.HMS)
        val hmsGeofence = FakeGeofenceBackend(supportsDwell = true, kind = ProviderKind.HMS)
        val hms = FakeProviderFactory(ProviderKind.HMS, hmsLocation, FakeActivityBackend(ProviderKind.HMS), hmsGeofence)
        val factory = SwitchingProviderFactory(mapOf(ProviderKind.GMS to providers, ProviderKind.HMS to hms), configStore)
        val engine = newEngine(factory)
        started(engine)
        assertEquals(expectedRegion(origin), region())

        engine.setConfig(JSONObject().put("locationProvider", "hms"))

        assertNull(region())
        assertEquals(expectedRegion(origin), region(hmsGeofence))
        assertEquals(passiveSpec, activeSpec(hmsLocation))
        assertFalse(locationBackend.isRequesting)

        engine.stop()
        assertNull(region(hmsGeofence))
    }

    @Test
    fun `a backend switch retries a registration that failed on the previous backend`() = runTest {
        geofenceBackend.failWith = TrackingException(ErrorCode.TOO_MANY_GEOFENCES, "limit")
        val hmsLocation = FakeLocationBackend(ProviderKind.HMS)
        val hmsGeofence = FakeGeofenceBackend(supportsDwell = true, kind = ProviderKind.HMS)
        val hms = FakeProviderFactory(ProviderKind.HMS, hmsLocation, FakeActivityBackend(ProviderKind.HMS), hmsGeofence)
        val factory = SwitchingProviderFactory(mapOf(ProviderKind.GMS to providers, ProviderKind.HMS to hms), configStore)
        val engine = newEngine(factory)
        started(engine)
        assertEquals(fallbackSpec, activeSpec())

        engine.setConfig(JSONObject().put("locationProvider", "hms"))

        assertEquals(expectedRegion(origin), region(hmsGeofence))
        assertEquals(passiveSpec, activeSpec(hmsLocation))
    }

    // ------------------------------------------------------------------ review fixes

    @Test
    fun `an old anchor gets no region until a current fix replaces it`() = runTest {
        // Restore after hours: no initial fix, the last known location is 3 hours old.
        val old = origin.copy(time = FakeClock.DEFAULT_NOW - 3 * HOUR)
        configStore.runtimeFlow.value = RuntimeState(enabled = true, lastLocation = old)
        val engine = newEngine()
        locationBackend.currentLocation = null

        engine.restore("restore")
        runCurrent()

        assertEquals(listOf("tracking_start:restore", "motionchange:false"), records())
        assertSame(old, recordSink.records.last().location)
        assertNull(region())
        assertEquals(fallbackSpec, activeSpec())

        // The low-power request delivers a current fix 80 m away (not certainly outside the 25 m radius).
        advance(3 * MINUTE)
        val current = Fixtures.moved(old, 80.0).copy(accuracy = 60f, time = clock.now())
        emit(current)

        assertEquals(2, recordSink.records.size)
        assertFalse(configStore.runtime.value.isMoving)
        assertSame(current, configStore.runtime.value.lastLocation)
        assertEquals(expectedRegion(current), region())
        assertEquals(passiveSpec, activeSpec())
    }

    @Test
    fun `a confirmed anchor stays confirmed and the region is registered again after hours`() = runTest {
        val engine = newEngine()
        started(engine)
        advance(3 * HOUR)
        val adds = geofenceBackend.addCalls.size

        // Location switched off and on: GMS dropped every geofence.
        geofenceBackend.removeAll()
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = false, gps = false)))
        runCurrent()
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = true)))
        runCurrent()

        assertEquals(adds + 1, geofenceBackend.addCalls.size)
        assertEquals(expectedRegion(origin), region())
        assertEquals(passiveSpec, activeSpec())

        // Any other provider change (here the network provider) registers it again too.
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = true, network = false)))
        runCurrent()
        assertEquals(adds + 2, geofenceBackend.addCalls.size)
    }

    @Test
    fun `a late EXIT whose fix is inside the registered region is ignored`() = runTest {
        val engine = newEngine()
        started(engine)

        transition(engine, Fixtures.moved(origin, 100.0).copy(accuracy = 20f)) // 120 m <= 150 m

        assertEquals(2, recordSink.records.size)
        assertFalse(configStore.runtime.value.isMoving)
        assertEquals(expectedRegion(origin), region())
        assertEquals(passiveSpec, activeSpec())
    }

    @Test
    fun `the region leaves the last OS geofence slot to the user geofences`() = runTest {
        geofences.add((1..99).map { Fixtures.circle("g$it") })
        val engine = newEngine()

        started(engine)
        assertNull(region())
        assertEquals(fallbackSpec, activeSpec())

        geofences.remove(listOf("g99"))
        events.emit(TrackingEvent.GeofencesChange(on = emptyList(), off = listOf("g99")))
        runCurrent()
        assertEquals(expectedRegion(origin), region())
        assertEquals(passiveSpec, activeSpec())

        geofences.add(listOf(Fixtures.circle("g99")))
        events.emit(TrackingEvent.GeofencesChange(on = listOf(Fixtures.circle("g99")), off = emptyList()))
        runCurrent()
        assertNull(region())
        assertEquals(fallbackSpec, activeSpec())
    }

    @Test
    fun `leaving STATIONARY turns GPS on and records the motionchange before the region removal returns`() = runTest {
        val slowRemove = object : GeofenceBackend by geofenceBackend {
            override suspend fun remove(ids: List<String>) {
                delay(8_000)
                geofenceBackend.remove(ids)
            }
        }
        val factory = object : ProviderFactory by providers {
            override fun geofence(): GeofenceBackend = slowRemove
        }
        val engine = newEngine(factory)
        started(engine)

        backgroundScope.launch { engine.changePace(true) }
        runCurrent()

        assertEquals("motionchange:true", records().last())
        assertEquals(movingSpec, activeSpec())
        assertEquals(expectedRegion(origin), region()) // the removal is still waiting for the OS
        advance(8_000)
        assertNull(region())
    }

    @Test
    fun `a restored session removes the region of the dead process when it stops without registering one`() =
        runTest {
            geofenceBackend.add(listOf(expectedRegion(origin)))
            configStore.runtimeFlow.value = RuntimeState(enabled = true)
            val engine = newEngine()
            locationBackend.currentLocation = null

            engine.restore("restore")
            runCurrent()
            assertEquals(expectedRegion(origin), region()) // nothing known: this session registered nothing

            engine.stop()

            assertNull(region())
        }

    // ------------------------------------------------------------------ helpers

    /** A geofence backend whose `add` never answers (a stuck Play services call); `remove` answers at once. */
    private class HangingGeofenceBackend : GeofenceBackend {
        override val kind = ProviderKind.GMS
        override val supportsDwell = true
        var addCalls = 0
        var removeCalls = 0

        override suspend fun add(regions: List<OsGeofence>) {
            addCalls++
            awaitCancellation()
        }

        override suspend fun remove(ids: List<String>) {
            removeCalls++
        }

        override suspend fun removeAll() = Unit
    }

    /** Selects between fake bundles by `config.locationProvider` (AUTO = GMS). */
    private class SwitchingProviderFactory(
        private val bundles: Map<ProviderKind, FakeProviderFactory>,
        private val store: ConfigStore,
    ) : ProviderFactory {
        private var selected = ProviderKind.GMS

        override val kind: ProviderKind get() = selected

        override fun location(): LocationBackend = bundles.getValue(selected).locationBackend

        override fun activity(): ActivityBackend = bundles.getValue(selected).activityBackend

        override fun geofence(): GeofenceBackend = bundles.getValue(selected).geofenceBackend

        override fun isAvailable(kind: ProviderKind): Boolean = kind in bundles

        override fun reselect(): Boolean {
            val wanted = when (store.config.value.locationProvider) {
                LocationProviderSetting.HMS -> ProviderKind.HMS
                else -> ProviderKind.GMS
            }
            val changed = wanted != selected
            selected = wanted
            return changed
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
