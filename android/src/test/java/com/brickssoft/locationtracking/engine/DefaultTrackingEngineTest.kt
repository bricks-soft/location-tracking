package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.ForceStopProbe
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.LocationProviderSetting
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.permission.PermissionState
import com.brickssoft.locationtracking.permission.PermissionType
import com.brickssoft.locationtracking.processing.FilterResult
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.testing.FakeActivityBackend
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeGeofenceManager
import com.brickssoft.locationtracking.testing.FakeHeartbeatScheduler
import com.brickssoft.locationtracking.testing.FakeHttpSyncer
import com.brickssoft.locationtracking.testing.FakeLocationBackend
import com.brickssoft.locationtracking.testing.FakeLocationProcessor
import com.brickssoft.locationtracking.testing.FakeOdometer
import com.brickssoft.locationtracking.testing.FakePermissionManager
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.FakeRecordSink
import com.brickssoft.locationtracking.testing.FakeServiceController
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end tests of [DefaultTrackingEngine] with every collaborator faked. The engine scope is the test's
 * `backgroundScope` (StandardTestDispatcher on the test scheduler) and [FakeClock] follows virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultTrackingEngineTest {
    private val configStore = FakeConfigStore()
    private val locationBackend = FakeLocationBackend(ProviderKind.GMS)
    private val activityBackend = FakeActivityBackend(ProviderKind.GMS)
    private val providers = FakeProviderFactory(ProviderKind.GMS, locationBackend, activityBackend)
    private val processor = FakeLocationProcessor()
    private val odometer = FakeOdometer()
    private val recordSink = FakeRecordSink()
    private val heartbeat = FakeHeartbeatScheduler()
    private val geofences = FakeGeofenceManager()
    private val service = FakeServiceController()
    private val device = FakeDeviceMonitor()
    private val syncer = FakeHttpSyncer()
    private val permissions = FakePermissionManager()
    private val events = RecordingEventBus()
    private lateinit var clock: FakeClock

    private val origin = Fixtures.location(accuracy = 5f, speed = 0f)
    private val geolocation get() = configStore.config.value.geolocation
    /** STATIONARY with the stationary region registered (the fake geofence backend accepts it). */
    private val stationarySpec get() = LocationRequests.passive(geolocation)
    private val movingSpec get() = LocationRequests.moving(geolocation)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun TestScope.newEngine(
        providerFactory: ProviderFactory = providers,
        store: ConfigStore = configStore,
        forceStopProbe: ForceStopProbe = ForceStopProbe.NEVER,
    ): DefaultTrackingEngine {
        clock = FakeClock(scheduler = testScheduler)
        return DefaultTrackingEngine(
            store, providerFactory, processor, odometer, FakeRecordFactory(clock, configStore), recordSink,
            heartbeat, geofences, service, device, syncer, permissions, events, clock, backgroundScope, forceStopProbe,
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

    /** Starts LOCATION tracking with [origin] as the initial fix and settles the initial motionchange. */
    private suspend fun TestScope.started(engine: DefaultTrackingEngine) {
        locationBackend.currentLocation = origin
        engine.start()
        runCurrent()
    }

    private fun records(): List<String> = recordSink.records.map { r ->
        when (r.event) {
            RecordEvent.MOTIONCHANGE -> "motionchange:${r.isMoving}"
            RecordEvent.TRACKING_START, RecordEvent.TRACKING_STOP -> "${r.event.wire}:${r.reason}"
            else -> r.event.wire
        }
    }

    private fun activeSpec(): LocationRequestSpec? = locationBackend.active.values.singleOrNull()

    private fun configure(transform: (GeolocationConfig) -> GeolocationConfig) {
        configStore.update { it.copy(geolocation = transform(it.geolocation)) }
    }

    private suspend fun assertStartFails(block: suspend () -> Unit): TrackingException {
        try {
            block()
        } catch (e: TrackingException) {
            return e
        }
        fail("expected a TrackingException")
        throw AssertionError()
    }

    // ------------------------------------------------------------------ full scenario

    @Test
    fun `start, moving, stationary and stop produce the exact record and event sequence`() = runTest {
        val engine = newEngine()

        started(engine)

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertNull(recordSink.records[0].location) // nothing was known before the initial fix
        assertSame(origin, recordSink.records[1].location)
        assertEquals(listOf(TrackingEvent.EnabledChange(true)), events.events)
        assertEquals(stationarySpec, activeSpec())
        assertEquals(DesiredAccuracy.PASSIVE, activeSpec()?.accuracy)
        assertEquals(listOf(DesiredAccuracy.HIGH to 30_000L), locationBackend.currentLocationCalls)
        assertEquals(listOf(10_000L), activityBackend.startCalls)
        assertEquals(1, service.startCalls)
        assertEquals(1, device.startCalls)
        assertEquals(1, syncer.startCalls)
        assertEquals(1, heartbeat.startCalls)
        assertEquals(listOf(TrackingMode.LOCATION), geofences.startedModes)
        assertEquals(1, processor.resetCalls)
        val runtime = configStore.runtime.value
        assertTrue(runtime.enabled)
        assertEquals(TrackingMode.LOCATION, runtime.trackingMode)
        assertEquals(clock.now(), runtime.trackingStartedAt)
        assertFalse(runtime.isMoving)

        // leaves the stationary radius
        val l1 = Fixtures.moved(origin, 100.0).copy(speed = 13.4f)
        emit(l1)
        assertEquals("motionchange:true", records().last())
        assertSame(l1, recordSink.records.last().location)
        assertTrue(configStore.runtime.value.isMoving)
        assertEquals(movingSpec, activeSpec())

        // 50 m at 13.4 m/s (elastic threshold 30 m) is recorded; 5 m more is not
        val l2 = Fixtures.moved(l1, 50.0)
        val l3 = Fixtures.moved(l2, 5.0)
        emit(l2)
        emit(l3)
        assertEquals(listOf(l2), recordSink.ofEvent(RecordEvent.LOCATION).map { it.location })
        assertEquals(listOf(l1, l2, l3), odometer.locations)

        engine.onActivitySamples(listOf(ActivitySample(ActivityType.STILL, 10), ActivitySample(ActivityType.IN_VEHICLE, 90)))
        runCurrent()
        assertEquals(ActivitySample(ActivityType.IN_VEHICLE, 90), configStore.runtime.value.activity)

        // no more motion: STATIONARY stopTimeout (5 min) after the last evidence of motion
        advance(5 * MINUTE - 1)
        assertTrue(configStore.runtime.value.isMoving)
        advance(1)
        assertEquals("motionchange:false", records().last())
        assertSame(l3, recordSink.records.last().location)
        assertFalse(configStore.runtime.value.isMoving)
        assertEquals(stationarySpec, activeSpec())

        engine.stop()

        assertEquals(
            listOf(
                "tracking_start:start",
                "motionchange:false",
                "motionchange:true",
                "location",
                "motionchange:false",
                "tracking_stop:stop",
            ),
            records(),
        )
        assertEquals(
            listOf(
                TrackingEvent.EnabledChange(true),
                TrackingEvent.ActivityChange(ActivitySample(ActivityType.IN_VEHICLE, 90)),
                TrackingEvent.EnabledChange(false),
            ),
            events.events,
        )
        assertSame(l3, recordSink.records.last().location)
        assertFalse(locationBackend.isRequesting)
        assertEquals(1, activityBackend.stopCalls)
        assertEquals(1, service.stopCalls)
        assertEquals(1, heartbeat.stopCalls)
        assertEquals(1, geofences.stoppedCalls)
        assertFalse(configStore.runtime.value.enabled)

        // late callbacks after stop are ignored
        emit(Fixtures.moved(l3, 500.0))
        advance(10 * MINUTE)
        assertEquals(6, recordSink.records.size)
    }

    @Test
    fun `tracking_stop is submitted before the service stops`() = runTest {
        val order = mutableListOf<String>()
        val sink = object : com.brickssoft.locationtracking.record.RecordSink {
            override suspend fun submit(record: com.brickssoft.locationtracking.model.Record) =
                recordSink.submit(record).also { order += record.event.wire }
        }
        val controller = object : com.brickssoft.locationtracking.service.ServiceController by service {
            override fun stop() {
                order += "service.stop"
                service.stop()
            }
        }
        clock = FakeClock(scheduler = testScheduler)
        val engine = DefaultTrackingEngine(
            configStore, providers, processor, odometer, FakeRecordFactory(clock, configStore), sink,
            heartbeat, geofences, controller, device, syncer, permissions, events, clock, backgroundScope,
        )
        started(engine)

        engine.stop()

        assertEquals(listOf("tracking_start", "motionchange", "tracking_stop", "service.stop"), order)
    }

    // ------------------------------------------------------------------ start failures and idempotency

    @Test
    fun `start without location permission fails without side effects`() = runTest {
        val engine = newEngine()
        permissions.set(PermissionType.LOCATION, PermissionState.DENIED)

        val error = assertStartFails { engine.start() }

        assertEquals(ErrorCode.PERMISSION_DENIED, error.code)
        assertTrue(recordSink.records.isEmpty())
        assertTrue(events.events.isEmpty())
        assertEquals(0, service.startCalls)
        assertFalse(configStore.runtime.value.enabled)
        assertFalse(locationBackend.isRequesting)
    }

    @Test
    fun `refused foreground service records tracking_stop service_start_failed and throws`() = runTest {
        val engine = newEngine()
        service.startResult = false
        locationBackend.lastLocation = origin

        val error = assertStartFails { engine.start() }
        runCurrent()

        // Location permission is granted: Android refused the service. The JS error code stays PERMISSION_DENIED.
        assertEquals(ErrorCode.PERMISSION_DENIED, error.code)
        assertTrue(error.message!!.contains("background"))
        assertEquals(listOf("tracking_stop:service_start_failed"), records())
        assertSame(origin, recordSink.records.single().location)
        assertFalse(configStore.runtime.value.enabled)
        assertTrue(events.events.isEmpty())
        assertEquals(0, heartbeat.startCalls)
        assertTrue(activityBackend.startCalls.isEmpty())
        assertFalse(locationBackend.isRequesting)
    }

    @Test
    fun `start is idempotent in the same mode`() = runTest {
        val engine = newEngine()
        started(engine)

        engine.start()
        runCurrent()

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertEquals(1, service.startCalls)
        assertEquals(listOf(TrackingEvent.EnabledChange(true)), events.events)
    }

    @Test
    fun `start without activity permission, support or with updates disabled skips activity recognition`() = runTest {
        permissions.set(PermissionType.ACTIVITY_RECOGNITION, PermissionState.DENIED)
        val engine = newEngine()
        started(engine)
        engine.stop()
        permissions.set(PermissionType.ACTIVITY_RECOGNITION, PermissionState.GRANTED)
        configStore.update { it.copy(activity = it.activity.copy(disableMotionActivityUpdates = true)) }
        started(engine)
        engine.stop()

        assertTrue(activityBackend.startCalls.isEmpty())
        assertEquals(0, activityBackend.stopCalls)

        val unsupported = FakeActivityBackend(ProviderKind.ANDROID, isSupported = false)
        configStore.update { it.copy(activity = it.activity.copy(disableMotionActivityUpdates = false)) }
        val engine2 = newEngine(FakeProviderFactory(ProviderKind.ANDROID, locationBackend, unsupported))
        started(engine2)
        assertTrue(unsupported.startCalls.isEmpty())
    }

    // ------------------------------------------------------------------ motion

    @Test
    fun `fixes inside the stationary radius are not recorded and not counted`() = runTest {
        val engine = newEngine()
        started(engine)

        val near = Fixtures.moved(origin, 15.0)
        advance(10_000)
        emit(near)

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertTrue(odometer.locations.isEmpty())
        assertEquals(listOf(origin, near), geofences.locations)
        // STATIONARY fixes never replace runtime.lastLocation, so heartbeats keep the anchor fix (round 2, §3); the
        // fake sink does not persist the motionchange location, so nothing wrote it here.
        assertNull(configStore.runtime.value.lastLocation)
        assertEquals(listOf(origin to false, near to false), processor.processed)
    }

    @Test
    fun `rejected fixes are ignored`() = runTest {
        val engine = newEngine()
        started(engine)
        processor.rejectAll = "accuracy"

        emit(Fixtures.moved(origin, 500.0))

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertEquals(listOf(origin), geofences.locations)
    }

    @Test
    fun `stop timeout is restarted by motion and fires after the last evidence`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        runCurrent()

        advance(4 * MINUTE)
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.WALKING, 80)))
        runCurrent()
        advance(4 * MINUTE)
        emit(Fixtures.moved(origin, 100.0)) // displacement restarts the timer again
        advance(4 * MINUTE)
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.STILL, 95))) // still does not restart it
        runCurrent()
        assertTrue(configStore.runtime.value.isMoving)

        advance(1 * MINUTE)

        assertEquals("motionchange:false", records().last())
        assertFalse(configStore.runtime.value.isMoving)
    }

    @Test
    fun `an overdue stop timeout fires on the next fix when delay did not`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        runCurrent()

        clock.advance(5 * MINUTE) // elapsed realtime moved (deep sleep) but no delay() fired
        emit(Fixtures.moved(origin, 5.0))

        assertEquals(listOf("tracking_start:start", "motionchange:false", "motionchange:true", "motionchange:false"), records())
        advance(10 * MINUTE)
        assertEquals(4, recordSink.records.size)
    }

    @Test
    fun `motion in a batch wins over a stop timeout that expired while asleep`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        runCurrent()

        clock.advance(5 * MINUTE)
        emit(Fixtures.moved(origin, 60.0), Fixtures.moved(origin, 120.0, timeDeltaMs = 2_000))

        assertTrue(configStore.runtime.value.isMoving)
        assertEquals(listOf("location", "location"), records().takeLast(2))
        advance(5 * MINUTE - 1)
        assertTrue(configStore.runtime.value.isMoving)
        advance(1)
        assertFalse(configStore.runtime.value.isMoving)
    }

    @Test
    fun `a heartbeat fires an overdue stop timeout when no fix arrives`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        runCurrent()

        clock.advance(4 * MINUTE)
        events.emit(TrackingEvent.Heartbeat(Fixtures.record(event = RecordEvent.HEARTBEAT)))
        runCurrent()
        assertTrue(configStore.runtime.value.isMoving) // not due yet

        clock.advance(1 * MINUTE)
        events.emit(TrackingEvent.Heartbeat(Fixtures.record(event = RecordEvent.HEARTBEAT)))
        runCurrent()

        assertFalse(configStore.runtime.value.isMoving)
        assertEquals("motionchange:false", records().last())

        engine.stop()
        assertEquals(0, events.subscriberCount)
    }

    @Test
    fun `a coarse backend location does not replace a vetted fix`() = runTest {
        val engine = newEngine()
        started(engine)
        locationBackend.lastLocation = Fixtures.location(latitude = 25.0, accuracy = 1_500f, time = origin.time + 60_000)

        engine.changePace(true)

        assertSame(origin, recordSink.records.last().location)
    }

    @Test
    fun `a coarse backend location is used when nothing else is known`() = runTest {
        val engine = newEngine()
        val coarse = Fixtures.location(accuracy = 1_500f)
        locationBackend.lastLocation = coarse

        engine.start()

        assertSame(coarse, recordSink.records.single().location)
    }

    @Test
    fun `switching mode checks the location permission`() = runTest {
        val engine = newEngine()
        started(engine)
        permissions.set(PermissionType.LOCATION, PermissionState.DENIED)

        val error = assertStartFails { engine.startGeofences() }

        assertEquals(ErrorCode.PERMISSION_DENIED, error.code)
        assertEquals(TrackingMode.LOCATION, configStore.runtime.value.trackingMode)
        assertEquals(2, recordSink.records.size)
    }

    @Test
    fun `disableStopDetection keeps moving`() = runTest {
        configStore.update { it.copy(activity = it.activity.copy(disableStopDetection = true)) }
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        runCurrent()

        engine.onActivitySamples(listOf(ActivitySample(ActivityType.STILL, 100)))
        advance(60 * MINUTE)

        assertTrue(configStore.runtime.value.isMoving)
        assertEquals("motionchange:true", records().last())
    }

    @Test
    fun `elastic distance filter scales with speed`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true) // motionchange at origin
        runCurrent()

        val slow = Fixtures.moved(origin, 11.0).copy(speed = 1f) // threshold 10 m
        val fastNear = Fixtures.moved(slow, 25.0).copy(speed = 15f) // threshold 30 m
        val fastFar = Fixtures.moved(slow, 35.0).copy(speed = 15f)
        emit(slow, fastNear, fastFar)

        assertEquals(listOf(slow, fastFar), recordSink.ofEvent(RecordEvent.LOCATION).map { it.location })
    }

    @Test
    fun `disableElasticity records by the plain distance filter`() = runTest {
        configure { it.copy(disableElasticity = true) }
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        runCurrent()

        val a = Fixtures.moved(origin, 12.0).copy(speed = 30f)
        val b = Fixtures.moved(a, 8.0).copy(speed = 30f)
        val c = Fixtures.moved(b, 4.0).copy(speed = 30f)
        emit(a, b, c)

        assertEquals(listOf(a, c), recordSink.ofEvent(RecordEvent.LOCATION).map { it.location })
    }

    @Test
    fun `changePace forces transitions and is ignored when not tracking`() = runTest {
        val engine = newEngine()
        engine.changePace(true)
        assertTrue(recordSink.records.isEmpty())

        started(engine)
        engine.changePace(true)
        assertEquals("motionchange:true", records().last())
        assertSame(origin, recordSink.records.last().location)
        assertTrue(recordSink.records.last().isMoving)
        assertEquals(movingSpec, activeSpec())

        engine.changePace(true)
        assertEquals(3, recordSink.records.size)

        engine.changePace(false)
        assertEquals("motionchange:false", records().last())
        assertFalse(recordSink.records.last().isMoving)
        assertEquals(stationarySpec, activeSpec())

        engine.changePace(false)
        assertEquals(4, recordSink.records.size)
    }

    @Test
    fun `confident moving activity leaves stationary after the motion trigger delay`() = runTest {
        configStore.update { it.copy(activity = it.activity.copy(motionTriggerDelay = 30_000)) }
        val engine = newEngine()
        started(engine)

        engine.onActivitySamples(listOf(ActivitySample(ActivityType.WALKING, 80)))
        advance(20_000)
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.STILL, 80))) // cancels
        advance(60_000)
        assertFalse(configStore.runtime.value.isMoving)

        engine.onActivitySamples(listOf(ActivitySample(ActivityType.ON_BICYCLE, 90)))
        advance(29_999)
        assertFalse(configStore.runtime.value.isMoving)
        advance(1)

        assertTrue(configStore.runtime.value.isMoving)
        assertEquals("motionchange:true", records().last())
        assertEquals(movingSpec, activeSpec())
    }

    @Test
    fun `activity samples pick the most confident and respect the threshold`() = runTest {
        val engine = newEngine()
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.WALKING, 99)))
        runCurrent()
        assertTrue(events.events.isEmpty()) // not tracking

        started(engine)
        events.clear()
        engine.onActivitySamples(
            listOf(
                ActivitySample(ActivityType.STILL, 40),
                ActivitySample(ActivityType.ON_FOOT, 80),
                ActivitySample(ActivityType.IN_VEHICLE, 60),
            ),
        )
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.ON_FOOT, 85))) // same type: no event
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.IN_VEHICLE, 70))) // below 75: ignored
        engine.onActivitySamples(emptyList())
        runCurrent()

        assertEquals(listOf(TrackingEvent.ActivityChange(ActivitySample(ActivityType.ON_FOOT, 80))), events.events)
        // a confidence-only change is persisted at most once a minute
        assertEquals(ActivitySample(ActivityType.ON_FOOT, 80), configStore.runtime.value.activity)
        assertTrue(configStore.runtime.value.isMoving) // motionTriggerDelay 0

        advance(60_000)
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.ON_FOOT, 85)))
        assertEquals(ActivitySample(ActivityType.ON_FOOT, 85), configStore.runtime.value.activity)
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.RUNNING, 90))) // new type: at once
        assertEquals(ActivitySample(ActivityType.RUNNING, 90), configStore.runtime.value.activity)
        assertEquals(TrackingEvent.ActivityChange(ActivitySample(ActivityType.RUNNING, 90)), events.events.last())
    }

    @Test
    fun `activity update in a cold process restores tracking first`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true, lastLocation = origin)
        val engine = newEngine()

        engine.onActivitySamples(listOf(ActivitySample(ActivityType.IN_VEHICLE, 90)))
        runCurrent()

        assertEquals(listOf("tracking_start:restore", "motionchange:true"), records())
        assertEquals(listOf(TrackingEvent.ActivityChange(ActivitySample(ActivityType.IN_VEHICLE, 90))), events.events)
        assertEquals(1, service.startCalls)
        assertEquals(movingSpec, activeSpec())
    }

    @Test
    fun `activity update after a user force stop does not restore tracking`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true, lastLocation = origin)
        val engine = newEngine(forceStopProbe = ForceStopProbe { true })

        engine.onActivitySamples(listOf(ActivitySample(ActivityType.IN_VEHICLE, 90)))
        runCurrent()

        assertEquals(emptyList<String>(), records())
        assertEquals(0, service.startCalls)
        assertTrue(events.events.isEmpty())
        // The leftover activity registration is released, so it stops waking the app; opening the app still restores.
        assertEquals(1, activityBackend.stopCalls)
        assertTrue(configStore.runtime.value.enabled)
        assertFalse(engine.state().runtime.isMoving)
    }

    @Test
    fun `ready after a user force stop restores tracking`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true, lastLocation = origin)
        val engine = newEngine(forceStopProbe = ForceStopProbe { true })
        engine.onActivitySamples(listOf(ActivitySample(ActivityType.STILL, 90)))
        runCurrent()

        engine.ready(null, reset = false)
        runCurrent()

        assertEquals("tracking_start:restore", records().first())
        assertEquals(1, service.startCalls)
    }

    @Test
    fun `stopOnStationary stops when stop detection enters stationary`() = runTest {
        configure { it.copy(stopOnStationary = true) }
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        engine.changePace(false) // a forced transition does not stop
        assertTrue(configStore.runtime.value.enabled)
        engine.changePace(true)

        advance(5 * MINUTE)

        assertEquals(
            listOf(
                "tracking_start:start",
                "motionchange:false",
                "motionchange:true",
                "motionchange:false",
                "motionchange:true",
                "motionchange:false",
                "tracking_stop:stop_on_stationary",
            ),
            records(),
        )
        assertFalse(configStore.runtime.value.enabled)
        assertEquals(TrackingEvent.EnabledChange(false), events.events.last())
        assertEquals(1, service.stopCalls)
    }

    @Test
    fun `stopAfterElapsedMinutes stops relative to trackingStartedAt`() = runTest {
        configure { it.copy(stopAfterElapsedMinutes = 2) }
        val engine = newEngine()
        started(engine)

        advance(2 * MINUTE - 1)
        assertTrue(configStore.runtime.value.enabled)
        advance(1)

        assertEquals("tracking_stop:stop_after_elapsed", records().last())
        assertFalse(configStore.runtime.value.enabled)
    }

    // ------------------------------------------------------------------ restore / terminate

    @Test
    fun `restore boot restarts the persisted session with tracking_start boot`() = runTest {
        val startedAt = FakeClock.DEFAULT_NOW - 3_600_000
        configStore.runtimeFlow.value = RuntimeState(
            enabled = true,
            trackingMode = TrackingMode.LOCATION,
            isMoving = true,
            trackingStartedAt = startedAt,
            lastLocation = origin,
        )
        val engine = newEngine()
        locationBackend.currentLocation = Fixtures.moved(origin, 3.0)

        engine.restore("boot")
        runCurrent()

        assertEquals(listOf("tracking_start:boot", "motionchange:false"), records())
        assertSame(origin, recordSink.records[0].location)
        assertTrue(events.events.isEmpty()) // was already enabled
        assertEquals(1, service.startCalls)
        assertEquals(1, heartbeat.startCalls)
        assertEquals(listOf(TrackingMode.LOCATION), geofences.startedModes)
        assertEquals(stationarySpec, activeSpec())
        assertEquals(startedAt, configStore.runtime.value.trackingStartedAt)
        assertFalse(configStore.runtime.value.isMoving)
    }

    @Test
    fun `restore keeps the geofences mode`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true, trackingMode = TrackingMode.GEOFENCES)
        val engine = newEngine()

        engine.restore("package_replaced")
        runCurrent()

        assertEquals(listOf("tracking_start:package_replaced"), records())
        assertEquals(listOf(TrackingMode.GEOFENCES), geofences.startedModes)
        assertFalse(locationBackend.isRequesting)
        assertTrue(activityBackend.startCalls.isEmpty())
    }

    @Test
    fun `restore does nothing when tracking is not enabled`() = runTest {
        val engine = newEngine()

        engine.restore("boot")
        runCurrent()

        assertTrue(recordSink.records.isEmpty())
        assertEquals(0, service.startCalls)
    }

    @Test
    fun `restore stops with service_start_failed when the service is refused`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true, lastLocation = origin)
        service.startResult = false
        val engine = newEngine()

        engine.restore("restore")
        runCurrent()

        assertFalse(configStore.runtime.value.enabled)
        assertEquals(listOf("tracking_stop:service_start_failed"), records())
        assertSame(origin, recordSink.records.single().location)
        assertEquals(listOf(TrackingEvent.EnabledChange(false)), events.events)
        assertFalse(locationBackend.isRequesting)
        assertEquals(1, heartbeat.stopCalls)

        engine.restore("restore") // a later heartbeat alarm: nothing left to restore
        runCurrent()
        assertEquals(1, recordSink.records.size)
    }

    @Test
    fun `restore after a system restart of the service does not start it again`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true)
        service.isRunning = true // START_STICKY: the system re-created the service, which calls restore()
        service.startResult = false // a second start from the background would be refused
        val engine = newEngine()

        engine.restore("restore")
        runCurrent()

        assertEquals(0, service.startCalls)
        assertTrue(configStore.runtime.value.enabled)
        assertEquals("tracking_start:restore", records().first())
    }

    @Test
    fun `endWithoutRestore records tracking_stop with the reason and disables tracking`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true, lastLocation = origin)
        val engine = newEngine()

        engine.endWithoutRestore("reboot")
        runCurrent()

        assertEquals(listOf("tracking_stop:reboot"), records())
        assertSame(origin, recordSink.records.single().location)
        assertFalse(configStore.runtime.value.enabled)
        assertEquals(listOf(TrackingEvent.EnabledChange(false)), events.events)
    }

    @Test
    fun `endWithoutRestore does nothing when tracking is not enabled`() = runTest {
        val engine = newEngine()

        engine.endWithoutRestore("reboot")
        runCurrent()

        assertTrue(recordSink.records.isEmpty())
    }

    @Test
    fun `restore of a running session stops when the service is refused again`() = runTest {
        val engine = newEngine()
        started(engine)
        service.isRunning = false
        service.startResult = false

        engine.restore("restore")
        runCurrent()

        assertEquals("tracking_stop:service_start_failed", records().last())
        assertFalse(configStore.runtime.value.enabled)
        assertFalse(locationBackend.isRequesting)
    }

    @Test
    fun `onServiceStartFailed stops a running session with an audit record`() = runTest {
        val engine = newEngine()
        started(engine)

        engine.onServiceStartFailed("SecurityException: background start")
        runCurrent()

        assertEquals("tracking_stop:service_start_failed", records().last())
        assertFalse(configStore.runtime.value.enabled)
        assertFalse(locationBackend.isRequesting)
        assertEquals(TrackingEvent.EnabledChange(false), events.events.last())
    }

    @Test
    fun `onServiceStartFailed after the location permission was revoked records permission_denied`() = runTest {
        val engine = newEngine()
        started(engine)
        permissions.set(PermissionType.LOCATION, PermissionState.DENIED)

        // Android 14+: the system's START_STICKY restart fails in startForeground without location permission.
        engine.onServiceStartFailed("SecurityException: FOREGROUND_SERVICE_TYPE_LOCATION requires permissions")
        runCurrent()

        assertEquals("tracking_stop:permission_denied", records().last())
        assertFalse(configStore.runtime.value.enabled)
        assertFalse(locationBackend.isRequesting)
    }

    @Test
    fun `onServiceStartFailed does nothing when tracking is not enabled`() = runTest {
        val engine = newEngine()

        engine.onServiceStartFailed("SecurityException")
        runCurrent()

        assertTrue(recordSink.records.isEmpty())
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `geofences are registered again when location services come back`() = runTest {
        val engine = newEngine()
        started(engine)
        assertEquals(1, geofences.startedModes.size)

        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = false, gps = false)))
        runCurrent()
        assertEquals(1, geofences.startedModes.size)

        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = true)))
        runCurrent()
        assertEquals(listOf(TrackingMode.LOCATION, TrackingMode.LOCATION), geofences.startedModes.toList())

        // Same availability and permission: nothing to do.
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(enabled = true, network = false)))
        runCurrent()
        assertEquals(2, geofences.startedModes.size)

        // Permission upgraded to "always": register again so background geofences fire.
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(permission = PermissionLevel.WHEN_IN_USE)))
        runCurrent()
        events.emit(TrackingEvent.ProviderChange(Fixtures.providerState(permission = PermissionLevel.ALWAYS)))
        runCurrent()
        assertEquals(4, geofences.startedModes.size)
    }

    @Test
    fun `restore without location permission stops with permission_denied`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true, lastLocation = origin)
        permissions.set(PermissionType.LOCATION, PermissionState.DENIED)
        val engine = newEngine()

        engine.restore("restore")

        assertEquals(listOf("tracking_stop:permission_denied"), records())
        assertSame(origin, recordSink.records.single().location)
        assertFalse(configStore.runtime.value.enabled)
        assertEquals(listOf(TrackingEvent.EnabledChange(false)), events.events)
        assertEquals(0, service.startCalls)
        assertEquals(1, heartbeat.stopCalls)
        assertEquals(1, geofences.stoppedCalls)
        assertEquals(1, activityBackend.stopCalls)
    }

    @Test
    fun `stop in a cold process releases the previous registrations`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true)
        val engine = newEngine()

        engine.stop()
        engine.stop() // idempotent

        assertEquals(listOf("tracking_stop:stop"), records())
        assertEquals(1, heartbeat.stopCalls)
        assertEquals(1, geofences.stoppedCalls)
        assertEquals(1, activityBackend.stopCalls)
        assertFalse(configStore.runtime.value.enabled)
    }

    @Test
    fun `onTerminate stops only with stopOnTerminate`() = runTest {
        val engine = newEngine()
        configStore.update { it.copy(app = it.app.copy(stopOnTerminate = false)) }
        started(engine)

        engine.onTerminate()
        assertTrue(configStore.runtime.value.enabled)
        assertTrue(service.isRunning)

        configStore.update { it.copy(app = it.app.copy(stopOnTerminate = true)) }
        engine.onTerminate()

        assertEquals("tracking_stop:terminate", records().last())
        assertFalse(configStore.runtime.value.enabled)
        assertFalse(service.isRunning)
    }

    @Test
    fun `ready delegates to the store and restores an enabled session`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = true)
        val engine = newEngine()

        val state = engine.ready(null, true)
        runCurrent()

        assertTrue(configStore.calls.contains("ready(reset=true)"))
        assertTrue(state.runtime.didReady)
        assertTrue(state.runtime.enabled)
        assertEquals(ProviderKind.GMS, state.backend)
        assertEquals(listOf("tracking_start:restore", "motionchange:false"), records())

        engine.ready(null, false) // already running: nothing new
        runCurrent()
        assertEquals(2, recordSink.records.size)
    }

    // ------------------------------------------------------------------ geofences mode

    @Test
    fun `geofences mode requests location only while polygons need it`() = runTest {
        val engine = newEngine()

        engine.startGeofences()
        runCurrent()

        assertEquals(listOf("tracking_start:start_geofences"), records())
        assertEquals(listOf(TrackingEvent.EnabledChange(true)), events.events)
        assertEquals(TrackingMode.GEOFENCES, configStore.runtime.value.trackingMode)
        assertEquals(listOf(TrackingMode.GEOFENCES), geofences.startedModes)
        assertFalse(locationBackend.isRequesting)
        assertTrue(activityBackend.startCalls.isEmpty())
        assertTrue(locationBackend.currentLocationCalls.isEmpty())

        geofences.needsContinuousLocation.value = true
        runCurrent()
        assertEquals(movingSpec, activeSpec())
        assertEquals(0f, activeSpec()?.distanceFilterM)

        val fix = Fixtures.moved(origin, 300.0)
        emit(fix)
        assertEquals(listOf(fix), geofences.locations)
        assertEquals(listOf(fix), odometer.locations)
        assertEquals(1, recordSink.records.size) // no location records in geofences mode

        engine.changePace(true) // ignored in geofences mode
        assertEquals(1, recordSink.records.size)

        geofences.needsContinuousLocation.value = false
        runCurrent()
        assertFalse(locationBackend.isRequesting)

        engine.stop()
        assertEquals(listOf("tracking_start:start_geofences", "tracking_stop:stop"), records())
    }

    @Test
    fun `polygon demand upgrades the stationary request in location mode`() = runTest {
        val engine = newEngine()
        started(engine)

        geofences.needsContinuousLocation.value = true
        runCurrent()
        assertEquals(movingSpec, activeSpec())
        val requests = locationBackend.requests.size

        engine.changePace(true)
        engine.changePace(false)
        assertEquals(movingSpec, activeSpec())
        assertEquals(requests, locationBackend.requests.size) // same request throughout

        geofences.needsContinuousLocation.value = false
        runCurrent()
        assertEquals(stationarySpec, activeSpec())
    }

    @Test
    fun `switching between location and geofences mode`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)

        engine.startGeofences()
        runCurrent()

        // leaving the location mode while moving ends motion tracking with a motionchange
        assertEquals(
            listOf(
                "tracking_start:start",
                "motionchange:false",
                "motionchange:true",
                "motionchange:false",
                "tracking_start:start_geofences",
            ),
            records(),
        )
        assertEquals(TrackingMode.GEOFENCES, configStore.runtime.value.trackingMode)
        assertFalse(configStore.runtime.value.isMoving)
        assertFalse(locationBackend.isRequesting)
        assertEquals(1, activityBackend.stopCalls)
        assertEquals(listOf(TrackingMode.LOCATION, TrackingMode.GEOFENCES), geofences.startedModes)
        advance(10 * MINUTE) // the stop timer of the location mode was cancelled
        assertEquals(5, recordSink.records.size)

        locationBackend.currentLocation = origin.copy(time = clock.now()) // a current initial fix
        engine.start()
        runCurrent()

        assertEquals(listOf("tracking_start:start", "motionchange:false"), records().takeLast(2))
        assertEquals(stationarySpec, activeSpec())
        assertEquals(2, activityBackend.startCalls.size)
        assertEquals(1, service.startCalls)
        assertEquals(listOf(TrackingEvent.EnabledChange(true)), events.events)
    }

    // ------------------------------------------------------------------ config

    @Test
    fun `setConfig while tracking re-applies the location request`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)
        val requestsBefore = locationBackend.requests.size

        val state = engine.setConfig(JSONObject().put("geolocation", JSONObject().put("locationUpdateInterval", 5_000)))

        assertEquals(5_000L, state.config.geolocation.locationUpdateInterval)
        assertEquals(5_000L, activeSpec()?.intervalMs)
        assertEquals(0f, activeSpec()?.distanceFilterM) // the engine applies the distance filter itself
        assertEquals(requestsBefore + 1, locationBackend.requests.size)
        assertEquals(0, providers.reselectCalls)

        // unrelated change while moving: same request, no new call
        engine.setConfig(JSONObject().put("heartbeat", JSONObject().put("minInterval", 240)))
        assertEquals(requestsBefore + 1, locationBackend.requests.size)
    }

    @Test
    fun `setConfig applies stop detection and elapsed-stop changes while tracking`() = runTest {
        val engine = newEngine()
        started(engine)
        engine.changePace(true)

        engine.setConfig(JSONObject().put("activity", JSONObject().put("disableStopDetection", true)))
        advance(30 * MINUTE)
        assertTrue(configStore.runtime.value.isMoving)

        engine.setConfig(JSONObject().put("activity", JSONObject().put("disableStopDetection", false)))
        advance(5 * MINUTE)
        assertFalse(configStore.runtime.value.isMoving)

        engine.setConfig(JSONObject().put("geolocation", JSONObject().put("stopAfterElapsedMinutes", 30)))
        runCurrent() // 35 min since start: already elapsed
        assertEquals("tracking_stop:stop_after_elapsed", records().last())
    }

    @Test
    fun `setConfig restarts activity updates when their settings change`() = runTest {
        val engine = newEngine()
        started(engine)

        engine.setConfig(JSONObject().put("activity", JSONObject().put("activityRecognitionInterval", 5_000)))
        assertEquals(listOf(10_000L, 5_000L), activityBackend.startCalls)
        assertEquals(1, activityBackend.stopCalls)

        engine.setConfig(JSONObject().put("activity", JSONObject().put("disableMotionActivityUpdates", true)))
        assertEquals(2, activityBackend.stopCalls)
        assertEquals(2, activityBackend.startCalls.size)
    }

    @Test
    fun `notification changes refresh the notification while tracking`() = runTest {
        val store = object : ConfigStore by configStore {
            override fun merge(json: JSONObject) = configStore.update {
                it.copy(notification = it.notification.copy(text = json.optString("text", it.notification.text)))
            }
        }
        val engine = newEngine(store = store)
        engine.setConfig(JSONObject().put("text", "idle"))
        assertEquals(0, service.refreshCalls)
        started(engine)

        engine.setConfig(JSONObject().put("text", "tracking"))
        engine.setConfig(JSONObject().put("text", "tracking"))

        assertEquals(1, service.refreshCalls)
    }

    @Test
    fun `reset delegates to the store`() = runTest {
        configure { it.copy(distanceFilter = 99.0) }
        val engine = newEngine()

        val state = engine.reset(null)

        assertTrue(configStore.calls.contains("reset"))
        assertEquals(GeolocationConfig().distanceFilter, state.config.geolocation.distanceFilter, 0.0)
    }

    @Test
    fun `provider change while tracking moves listeners, activity and geofences to the new backend`() = runTest {
        val gms = FakeProviderFactory(ProviderKind.GMS, locationBackend, activityBackend)
        val hmsLocation = FakeLocationBackend(ProviderKind.HMS)
        val hmsActivity = FakeActivityBackend(ProviderKind.HMS)
        val hms = FakeProviderFactory(ProviderKind.HMS, hmsLocation, hmsActivity)
        val factory = SwitchingProviderFactory(mapOf(ProviderKind.GMS to gms, ProviderKind.HMS to hms), configStore)
        val engine = newEngine(providerFactory = factory)
        started(engine)
        engine.changePace(true)

        val state = engine.setConfig(JSONObject().put("locationProvider", "hms"))

        assertEquals(ProviderKind.HMS, state.backend)
        assertEquals(1, factory.reselectCalls)
        assertFalse(locationBackend.isRequesting)
        assertEquals(movingSpec, hmsLocation.active.values.single())
        assertEquals(1, activityBackend.stopCalls)
        assertEquals(listOf(10_000L), hmsActivity.startCalls)
        assertEquals(1, geofences.stoppedCalls)
        assertEquals(listOf(TrackingMode.LOCATION, TrackingMode.LOCATION), geofences.startedModes)
        assertEquals(listOf("reselect"), device.checkReasons)

        // fixes now come from the new backend
        hmsLocation.emit(Fixtures.moved(origin, 200.0))
        runCurrent()
        assertEquals("location", records().last())

        engine.stop()
        assertFalse(hmsLocation.isRequesting)
        assertEquals(1, hmsActivity.stopCalls)
    }

    @Test
    fun `provider change while stopped only reselects`() = runTest {
        val engine = newEngine()

        engine.setConfig(JSONObject().put("locationProvider", "android"))
        engine.setConfig(JSONObject().put("locationProvider", "android"))

        assertEquals(LocationProviderSetting.ANDROID, configStore.config.value.locationProvider)
        assertEquals(1, providers.reselectCalls)
        assertEquals(0, geofences.stoppedCalls)
        assertTrue(locationBackend.requests.isEmpty())
        assertTrue(device.checkReasons.isEmpty()) // reselect reported no change
    }

    @Test
    fun `initial fix falls back to the last known location and goes through the processor`() = runTest {
        val engine = newEngine()
        val last = Fixtures.location(latitude = 1.0, time = Fixtures.FIX_TIME - 60_000)
        locationBackend.lastLocation = last
        locationBackend.currentLocation = null

        engine.start()
        runCurrent()
        assertEquals(listOf("tracking_start:start", "motionchange:false"), records())
        assertSame(last, recordSink.records[0].location)
        assertSame(last, recordSink.records[1].location)
        engine.stop()

        recordSink.records.clear()
        val fresh = Fixtures.location(latitude = 2.0)
        locationBackend.currentLocation = fresh
        processor.scripted.addLast(FilterResult.Rejected("mock"))
        engine.start()
        runCurrent()
        assertSame(last, recordSink.records.last().location) // fresh fix rejected -> last known
    }

    /** Selects between fake bundles by `config.locationProvider` (AUTO = GMS). */
    private class SwitchingProviderFactory(
        private val bundles: Map<ProviderKind, FakeProviderFactory>,
        private val store: ConfigStore,
    ) : ProviderFactory {
        private var selected = ProviderKind.GMS
        var reselectCalls = 0

        override val kind: ProviderKind get() = selected

        override fun location(): LocationBackend = bundles.getValue(selected).locationBackend

        override fun activity(): ActivityBackend = bundles.getValue(selected).activityBackend

        override fun geofence(): GeofenceBackend = bundles.getValue(selected).geofenceBackend

        override fun isAvailable(kind: ProviderKind): Boolean = kind in bundles

        override fun reselect(): Boolean {
            reselectCalls++
            val wanted = when (store.config.value.locationProvider) {
                LocationProviderSetting.HMS -> ProviderKind.HMS
                LocationProviderSetting.ANDROID -> ProviderKind.ANDROID
                LocationProviderSetting.GMS, LocationProviderSetting.AUTO -> ProviderKind.GMS
            }.takeIf { it in bundles } ?: ProviderKind.GMS
            val changed = wanted != selected
            selected = wanted
            return changed
        }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
