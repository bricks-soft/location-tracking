package com.brickssoft.locationtracking.model

import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordJsonTest {
    private val sentAt = 1_790_417_745_001L // 2026-09-26T10:15:45.001Z

    @Test
    fun `location record matches the golden wire format`() {
        val record = Fixtures.record(extras = """{"driver_id":7}""")

        val json = RecordJson.toJson(record, sentAt)

        assertJsonEquals(
            """
            {
              "uuid": "0d6c6a1e-6c1e-4b8e-9d8f-2b6f3f0f7a11",
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
    fun `float fields serialize without binary noise`() {
        val text = RecordJson.toJson(Fixtures.record()).toString()

        assertTrue(text, text.contains("\"accuracy\":5.2"))
        assertTrue(text, text.contains("\"speed\":13.4"))
        assertTrue(text, text.contains("\"speed_accuracy\":0.8"))
        assertTrue(text, text.contains("\"level\":0.81"))
    }

    @Test
    fun `sent_at is omitted unless given`() {
        val json = RecordJson.toJson(Fixtures.record())

        assertFalse(json.has("sent_at"))
    }

    @Test
    fun `unknown optional coords are null and event keys are absent`() {
        val record = Fixtures.record(
            location = Fixtures.location(
                altitude = null,
                altitudeAccuracy = null,
                speed = null,
                speedAccuracy = null,
                heading = null,
                headingAccuracy = null,
            ),
        )

        val json = RecordJson.toJson(record)
        val coords = json.getJSONObject("coords")

        for (key in listOf("altitude", "altitude_accuracy", "speed", "speed_accuracy", "heading", "heading_accuracy")) {
            assertTrue("$key present", coords.has(key))
            assertTrue("$key null", coords.isNull(key))
        }
        for (key in listOf("extras", "geofence", "provider", "reason")) assertFalse(key, json.has(key))
    }

    @Test
    fun `heartbeat without any known location has null coords and timestamp`() {
        val record = Fixtures.record(
            uuid = "hb-1",
            event = RecordEvent.HEARTBEAT,
            location = null,
            isMoving = false,
            backend = null,
        )

        val json = RecordJson.toJson(record, sentAt)

        assertJsonEquals(
            """
            {
              "uuid": "hb-1",
              "event": "heartbeat",
              "timestamp": null,
              "recorded_at": "2026-09-26T10:15:30.456Z",
              "sent_at": "2026-09-26T10:15:45.001Z",
              "elapsed_realtime_ms": 86400123,
              "boot_count": 42,
              "is_moving": false,
              "odometer": 1532.4,
              "mock": false,
              "coords": null,
              "activity": { "type": "in_vehicle", "confidence": 92 },
              "battery": { "level": 0.81, "is_charging": false },
              "backend": null
            }
            """,
            json,
        )
        assertTrue(json.has("coords"))
        assertTrue(json.has("timestamp"))
    }

    @Test
    fun `tracking_start carries its reason`() {
        val json = RecordJson.toJson(Fixtures.record(event = RecordEvent.TRACKING_START, reason = "boot"))

        assertEquals("tracking_start", json.getString("event"))
        assertEquals("boot", json.getString("reason"))
        assertFalse(json.has("geofence"))
        assertFalse(json.has("provider"))
    }

    @Test
    fun `providerchange carries the provider state`() {
        val state = Fixtures.providerState(enabled = false, gps = false, network = true)

        val json = RecordJson.toJson(Fixtures.record(event = RecordEvent.PROVIDERCHANGE, provider = state))

        assertJsonEquals(
            """{"enabled":false,"gps":false,"network":true,"permission":"always","accuracy":"precise","backend":"gms"}""",
            json.getJSONObject("provider"),
        )
    }

    @Test
    fun `geofence record carries identifier, action and extras`() {
        val hit = GeofenceHit("home", GeofenceAction.ENTER, """{"floor":2}""")

        val json = RecordJson.toJson(Fixtures.record(event = RecordEvent.GEOFENCE, geofence = hit))

        assertJsonEquals("""{"identifier":"home","action":"ENTER","extras":{"floor":2}}""", json.getJSONObject("geofence"))
        assertEquals(24.7136, json.getJSONObject("coords").getDouble("latitude"), 0.0)
    }

    @Test
    fun `fromJson round-trips every record variant`() {
        val records = listOf(
            Fixtures.record(extras = """{"driver_id":7}"""),
            Fixtures.record(location = Fixtures.location(isMock = true, altitude = null, heading = null)),
            Fixtures.record(uuid = "hb", event = RecordEvent.HEARTBEAT, location = null, backend = null),
            Fixtures.record(event = RecordEvent.TRACKING_STOP, reason = "stop_after_elapsed"),
            Fixtures.record(event = RecordEvent.PROVIDERCHANGE, provider = Fixtures.providerState(enabled = false)),
            Fixtures.record(
                event = RecordEvent.GEOFENCE,
                geofence = GeofenceHit("home", GeofenceAction.DWELL, """{"a":"b"}"""),
            ),
            Fixtures.record(battery = BatterySnapshot.UNKNOWN, activity = ActivitySample.UNKNOWN, bootCount = -1),
        )

        for (record in records) {
            assertEquals(record, RecordJson.fromJson(RecordJson.toJson(record, sentAt)))
            assertEquals(record, RecordJson.fromJson(JSONObject(RecordJson.toJson(record).toString())))
        }
    }

    @Test
    fun `toJsonArray keeps order`() {
        val array = RecordJson.toJsonArray(listOf(Fixtures.record(uuid = "a"), Fixtures.record(uuid = "b")), sentAt)

        assertEquals(2, array.length())
        assertEquals("a", array.getJSONObject(0).getString("uuid"))
        assertEquals("2026-09-26T10:15:45.001Z", array.getJSONObject(1).getString("sent_at"))
    }

    @Test
    fun `geofence json round-trips circles and polygons`() {
        val circle = Fixtures.circle(extras = """{"k":1}""", notifyOnDwell = true, loiteringDelay = 5_000L)
        val polygon = Fixtures.polygon()

        assertEquals(circle, GeofenceJson.fromJson(GeofenceJson.toJson(circle)))
        assertEquals(polygon, GeofenceJson.fromJson(GeofenceJson.toJson(polygon)))
        val vertices = GeofenceJson.toJson(polygon).getJSONArray("vertices")
        assertEquals(4, vertices.length())
        assertEquals(2, vertices.getJSONArray(0).length())
    }
}
