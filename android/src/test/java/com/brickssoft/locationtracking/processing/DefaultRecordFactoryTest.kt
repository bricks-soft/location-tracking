package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.PersistenceConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DefaultRecordFactoryTest {
    private val clock = FakeClock()
    private val runtime = RuntimeState(
        isMoving = true,
        odometer = 1532.4,
        activity = ActivitySample(ActivityType.IN_VEHICLE, 92),
    )
    private val configStore = FakeConfigStore(runtime = runtime)
    private val device = FakeDeviceMonitor().apply { batteryValue = BatterySnapshot(0.81f, false) }
    private val providers = FakeProviderFactory(ProviderKind.GMS)
    private val factory = DefaultRecordFactory(configStore, device, providers, clock)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun setPersistenceExtras(json: String) {
        configStore.configFlow.value = Config(persistence = PersistenceConfig(extras = json))
    }

    private fun assertInvalid(input: String, messagePart: String) {
        try {
            factory.fromExternal(JSONObject(input))
            fail("expected INVALID_ARGUMENT for $input")
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
            assertTrue("message '${e.message}' should mention '$messagePart'", e.message!!.contains(messagePart))
        }
    }

    // ---- create

    @Test
    fun `create populates metadata, runtime, battery and backend`() {
        val location = Fixtures.location()

        val record = factory.create(RecordEvent.LOCATION, location)

        assertEquals(4, UUID.fromString(record.uuid).version())
        assertEquals(RecordEvent.LOCATION, record.event)
        assertEquals(location, record.location)
        assertEquals(FakeClock.DEFAULT_NOW, record.recordedAt)
        assertEquals(FakeClock.DEFAULT_ELAPSED, record.elapsedRealtimeMs)
        assertEquals(FakeClock.DEFAULT_BOOT_COUNT, record.bootCount)
        assertTrue(record.isMoving)
        assertEquals(1532.4, record.odometer, 0.0)
        assertEquals(ActivitySample(ActivityType.IN_VEHICLE, 92), record.activity)
        assertEquals(BatterySnapshot(0.81f, false), record.battery)
        assertEquals(ProviderKind.GMS, record.backend)
        assertNull(record.extras)
        assertNull(record.geofence)
        assertNull(record.provider)
        assertNull(record.reason)
    }

    @Test
    fun `create reads clock, runtime, battery and backend at call time`() {
        factory.create(RecordEvent.LOCATION, null)
        clock.advance(5_000)
        clock.bootCountValue = 43
        configStore.updateRuntime { it.copy(isMoving = false, odometer = 2_000.0, activity = ActivitySample.UNKNOWN) }
        device.batteryValue = BatterySnapshot(0.2f, true)
        providers.kind = ProviderKind.HMS

        val record = factory.create(RecordEvent.HEARTBEAT, null)

        assertEquals(FakeClock.DEFAULT_NOW + 5_000, record.recordedAt)
        assertEquals(FakeClock.DEFAULT_ELAPSED + 5_000, record.elapsedRealtimeMs)
        assertEquals(43, record.bootCount)
        assertFalse(record.isMoving)
        assertEquals(2_000.0, record.odometer, 0.0)
        assertEquals(ActivitySample.UNKNOWN, record.activity)
        assertEquals(BatterySnapshot(0.2f, true), record.battery)
        assertEquals(ProviderKind.HMS, record.backend)
    }

    @Test
    fun `uuids are unique`() {
        val uuids = (1..200).map { factory.create(RecordEvent.LOCATION, null).uuid }.toSet()

        assertEquals(200, uuids.size)
    }

    @Test
    fun `geofence, provider and reason pass through`() {
        val hit = GeofenceHit("home", GeofenceAction.ENTER, """{"zone":1}""")
        val state = Fixtures.providerState(enabled = false)

        val geofence = factory.create(RecordEvent.GEOFENCE, Fixtures.location(), geofence = hit)
        val providerChange = factory.create(RecordEvent.PROVIDERCHANGE, null, provider = state)
        val stop = factory.create(RecordEvent.TRACKING_STOP, null, reason = "stop")

        assertEquals(hit, geofence.geofence)
        assertEquals(state, providerChange.provider)
        assertEquals("stop", stop.reason)
        assertNull(stop.location)
    }

    @Test
    fun `battery and backend failures degrade to unknown`() {
        val failingDevice = object : DeviceMonitor by FakeDeviceMonitor() {
            override fun battery(): BatterySnapshot = throw IllegalStateException("no battery")
        }
        val failingProviders = object : ProviderFactory by FakeProviderFactory() {
            override val kind: ProviderKind get() = throw IllegalStateException("no backend")
        }

        val record = DefaultRecordFactory(configStore, failingDevice, failingProviders, clock)
            .create(RecordEvent.LOCATION, Fixtures.location())

        assertEquals(BatterySnapshot.UNKNOWN, record.battery)
        assertNull(record.backend)
    }

    // ---- extras

    @Test
    fun `per-call extras win over persistence extras`() {
        setPersistenceExtras("""{"driver_id":7,"fleet":"a","nested":{"x":1}}""")

        val record = factory.create(RecordEvent.LOCATION, null, extras = """{"fleet":"b","trip":12}""")

        assertJsonEquals("""{"driver_id":7,"fleet":"b","nested":{"x":1},"trip":12}""", JSONObject(record.extras!!))
    }

    @Test
    fun `persistence extras alone are used`() {
        setPersistenceExtras("""{"driver_id":7}""")

        assertJsonEquals("""{"driver_id":7}""", JSONObject(factory.create(RecordEvent.LOCATION, null).extras!!))
    }

    @Test
    fun `per-call extras alone are used`() {
        val record = factory.create(RecordEvent.LOCATION, null, extras = """{"a":[1,2],"b":null}""")

        assertJsonEquals("""{"a":[1,2],"b":null}""", JSONObject(record.extras!!))
    }

    @Test
    fun `extras are null when both are empty`() {
        assertNull(factory.create(RecordEvent.LOCATION, null).extras)
        assertNull(factory.create(RecordEvent.LOCATION, null, extras = "{}").extras)
        assertNull(factory.create(RecordEvent.LOCATION, null, extras = " ").extras)
        setPersistenceExtras("")
        assertNull(factory.create(RecordEvent.LOCATION, null).extras)
    }

    @Test
    fun `invalid extras text is ignored`() {
        setPersistenceExtras("""{"driver_id":7}""")

        val record = factory.create(RecordEvent.LOCATION, null, extras = "[1,2]")

        assertJsonEquals("""{"driver_id":7}""", JSONObject(record.extras!!))
        setPersistenceExtras("not json")
        assertJsonEquals("""{"a":1}""", JSONObject(factory.create(RecordEvent.LOCATION, null, extras = """{"a":1}""").extras!!))
    }

    @Test
    fun `persistence extras are read live`() {
        assertNull(factory.create(RecordEvent.LOCATION, null).extras)

        setPersistenceExtras("""{"shift":3}""")

        assertJsonEquals("""{"shift":3}""", JSONObject(factory.create(RecordEvent.LOCATION, null).extras!!))
    }

    // ---- fromExternal

    @Test
    fun `fromExternal parses a full input`() {
        setPersistenceExtras("""{"driver_id":7}""")
        val input = JSONObject(
            """
            {
              "coords": { "latitude": 24.7136, "longitude": 46.6753, "accuracy": 5.2, "altitude": 612.3,
                          "altitude_accuracy": 3.0, "speed": 13.4, "speed_accuracy": 0.8,
                          "heading": 271.5, "heading_accuracy": 5.0 },
              "timestamp": "2026-09-26T10:15:30.123Z",
              "event": "motionchange",
              "is_moving": false,
              "extras": { "source": "import" }
            }
            """,
        )

        val record = factory.fromExternal(input)

        assertEquals(RecordEvent.MOTIONCHANGE, record.event)
        assertEquals(
            Fixtures.location(),
            record.location,
        )
        assertFalse(record.isMoving)
        assertEquals(FakeClock.DEFAULT_NOW, record.recordedAt)
        assertEquals(1532.4, record.odometer, 0.0)
        assertEquals(ProviderKind.GMS, record.backend)
        assertJsonEquals("""{"driver_id":7,"source":"import"}""", JSONObject(record.extras!!))
    }

    @Test
    fun `fromExternal applies defaults`() {
        val record = factory.fromExternal(JSONObject("""{"coords":{"latitude":1.5,"longitude":-2.25}}"""))

        assertEquals(RecordEvent.LOCATION, record.event)
        val location = record.location!!
        assertEquals(1.5, location.latitude, 0.0)
        assertEquals(-2.25, location.longitude, 0.0)
        assertEquals(0f, location.accuracy, 0f)
        assertNull(location.altitude)
        assertNull(location.speed)
        assertNull(location.heading)
        assertFalse(location.isMock)
        assertEquals(clock.now(), location.time)
        assertTrue("isMoving comes from runtime", record.isMoving)
        assertNull(record.extras)
    }

    @Test
    fun `fromExternal treats JSON nulls as missing`() {
        val record = factory.fromExternal(
            JSONObject(
                """{"coords":{"latitude":1,"longitude":2,"accuracy":null,"speed":null},
                   "timestamp":null,"event":null,"is_moving":null,"extras":null}""",
            ),
        )

        assertEquals(RecordEvent.LOCATION, record.event)
        assertEquals(0f, record.location!!.accuracy, 0f)
        assertNull(record.location!!.speed)
        assertEquals(clock.now(), record.location!!.time)
        assertTrue(record.isMoving)
        assertNull(record.extras)
    }

    @Test
    fun `fromExternal maps every wire event name`() {
        for (event in RecordEvent.entries) {
            val record = factory.fromExternal(
                JSONObject().put("coords", JSONObject().put("latitude", 1).put("longitude", 2)).put("event", event.wire),
            )
            assertEquals(event, record.event)
        }
    }

    @Test
    fun `fromExternal accepts timestamps with an offset`() {
        val record = factory.fromExternal(
            JSONObject("""{"coords":{"latitude":1,"longitude":2},"timestamp":"2026-09-26T13:15:30.123+03:00"}"""),
        )

        assertEquals(Fixtures.FIX_TIME, record.location!!.time)
    }

    @Test
    fun `fromExternal accepts numeric strings for coordinates`() {
        val record = factory.fromExternal(JSONObject("""{"coords":{"latitude":"24.5","longitude":"46.25"}}"""))

        assertEquals(24.5, record.location!!.latitude, 0.0)
        assertEquals(46.25, record.location!!.longitude, 0.0)
    }

    @Test
    fun `fromExternal rejects missing or invalid coordinates`() {
        assertInvalid("""{}""", "coords")
        assertInvalid("""{"coords":null}""", "coords")
        assertInvalid("""{"coords":"24,46"}""", "coords")
        assertInvalid("""{"coords":{"longitude":46.6}}""", "latitude")
        assertInvalid("""{"coords":{"latitude":24.7}}""", "longitude")
        assertInvalid("""{"coords":{"latitude":null,"longitude":46.6}}""", "latitude")
        assertInvalid("""{"coords":{"latitude":"north","longitude":46.6}}""", "latitude")
        assertInvalid("""{"coords":{"latitude":95,"longitude":46.6}}""", "out of range")
        assertInvalid("""{"coords":{"latitude":24.7,"longitude":181}}""", "out of range")
    }

    @Test
    fun `fromExternal rejects invalid optional fields`() {
        assertInvalid("""{"coords":{"latitude":1,"longitude":2},"timestamp":"yesterday"}""", "timestamp")
        assertInvalid("""{"coords":{"latitude":1,"longitude":2},"timestamp":1790417730123}""", "timestamp")
        assertInvalid("""{"coords":{"latitude":1,"longitude":2},"event":"teleport"}""", "event")
        assertInvalid("""{"coords":{"latitude":1,"longitude":2},"extras":"x=1"}""", "extras")
        assertInvalid("""{"coords":{"latitude":1,"longitude":2},"is_moving":1}""", "is_moving")
        assertInvalid("""{"coords":{"latitude":1,"longitude":2,"accuracy":-1}}""", "accuracy")
    }

    @Test
    fun `fromExternal maps negative unknown sentinels to null`() {
        val record = factory.fromExternal(
            JSONObject(
                """{"coords":{"latitude":1,"longitude":2,"altitude":-12.5,"altitude_accuracy":-1,"speed":-1,
                   "speed_accuracy":-1,"heading":-1,"heading_accuracy":-1}}""",
            ),
        )

        val location = record.location!!
        assertEquals(-12.5, location.altitude!!, 0.0)
        assertNull(location.altitudeAccuracy)
        assertNull(location.speed)
        assertNull(location.speedAccuracy)
        assertNull(location.heading)
        assertNull(location.headingAccuracy)
    }

    @Test
    fun `fromExternal accepts is_moving as a boolean string`() {
        configStore.updateRuntime { it.copy(isMoving = false) }

        val record = factory.fromExternal(JSONObject("""{"coords":{"latitude":1,"longitude":2},"is_moving":"true"}"""))

        assertTrue(record.isMoving)
    }

    // ---- wire format

    @Test
    fun `wire JSON of a created record has exactly the documented keys`() {
        setPersistenceExtras("""{"driver_id":7}""")

        val json = RecordJson.toJson(factory.create(RecordEvent.LOCATION, Fixtures.location()))

        assertEquals(
            setOf(
                "uuid", "event", "timestamp", "recorded_at", "elapsed_realtime_ms", "boot_count", "is_moving",
                "odometer", "mock", "coords", "activity", "battery", "backend", "extras",
            ),
            json.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `wire JSON of a created record matches the golden record`() {
        setPersistenceExtras("""{"driver_id":7}""")
        val record = factory.create(RecordEvent.LOCATION, Fixtures.location())
        val sentAt = Iso8601.parse("2026-09-26T10:15:45.001Z")!!

        val json = RecordJson.toJson(record, sentAt)

        assertJsonEquals(
            """
            {
              "uuid": "${record.uuid}",
              "event": "location",
              "timestamp": "2026-09-26T10:15:30.123Z",
              "recorded_at": "2026-09-26T10:15:30.456Z",
              "sent_at": "2026-09-26T10:15:45.001Z",
              "elapsed_realtime_ms": 86400123,
              "boot_count": 42,
              "is_moving": true,
              "odometer": 1532.4,
              "mock": false,
              "coords": { "latitude": 24.7136, "longitude": 46.6753, "accuracy": 5.2,
                          "altitude": 612.3, "altitude_accuracy": 3.0, "speed": 13.4, "speed_accuracy": 0.8,
                          "heading": 271.5, "heading_accuracy": 5.0 },
              "activity": { "type": "in_vehicle", "confidence": 92 },
              "battery": { "level": 0.81, "is_charging": false },
              "backend": "gms",
              "extras": { "driver_id": 7 }
            }
            """,
            json,
        )
    }

    @Test
    fun `heartbeat without a location has null timestamp and coords`() {
        val json = RecordJson.toJson(factory.create(RecordEvent.HEARTBEAT, null))

        assertEquals("heartbeat", json.getString("event"))
        assertTrue(json.isNull("timestamp"))
        assertTrue(json.isNull("coords"))
        assertFalse(json.has("extras"))
    }

    @Test
    fun `an inserted record round-trips through the wire format`() {
        val record = factory.fromExternal(
            JSONObject(
                """{"coords":{"latitude":24.7136,"longitude":46.6753,"accuracy":5.2,"speed":1.5},
                   "timestamp":"2026-09-26T10:15:30.123Z","extras":{"k":"v"}}""",
            ),
        )

        assertEquals(record, RecordJson.fromJson(RecordJson.toJson(record)))
    }
}
