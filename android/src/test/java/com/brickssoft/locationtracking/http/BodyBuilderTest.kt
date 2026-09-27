package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BodyBuilderTest {
    private val log = FakeLogStore()

    /** 2026-09-26T10:15:45.001Z */
    private val sentAt = Fixtures.RECORDED_AT + 14_545
    private val golden = Fixtures.record(extras = """{"driver_id":7}""")

    @Before
    fun setUp() {
        Logger.sink = log
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `single record matches the architecture example`() {
        val http = HttpConfig(url = "https://example.com", params = """{"device_id":"abc"}""")

        val body = JSONObject(BodyBuilder.build(listOf(golden), http, sentAt))

        assertJsonEquals(GOLDEN_BODY, body)
        assertEquals(listOf("location", "device_id"), body.keys().asSequence().toList())
    }

    @Test
    fun `params never overwrite record data and invalid params are ignored`() {
        val clash = HttpConfig(params = """{"location":"nope","n":1,"nested":{"a":[1]}}""")
        val body = JSONObject(BodyBuilder.single(golden, clash, sentAt))
        assertEquals(golden.uuid, body.getJSONObject("location").getString("uuid"))
        assertJsonEquals(JSONObject("""{"a":[1]}"""), body.get("nested"))
        assertEquals(1, body.getInt("n"))

        val invalid = JSONObject(BodyBuilder.single(golden, HttpConfig(params = "not json"), sentAt))
        assertEquals(setOf("location"), invalid.keys().asSequence().toSet())
        assertTrue(log.lines.any { it.level == LogLevel.WARN })
    }

    @Test
    fun `custom rootProperty wraps the record`() {
        val body = JSONObject(BodyBuilder.single(golden, HttpConfig(rootProperty = "data"), sentAt))

        assertJsonEquals(RecordJson.toJson(golden, sentAt), body.getJSONObject("data"))
        assertEquals(setOf("data"), body.keys().asSequence().toSet())
    }

    @Test
    fun `rootProperty dot merges a single record into the root with params`() {
        val http = HttpConfig(rootProperty = ".", params = """{"device_id":"abc","uuid":"ignored"}""")

        val body = JSONObject(BodyBuilder.build(listOf(golden), http, sentAt))

        val expected = RecordJson.toJson(golden, sentAt).put("device_id", "abc")
        assertJsonEquals(expected, body)
    }

    @Test
    fun `batch wraps records oldest first under rootProperty with params`() {
        val records = (1..3).map { Fixtures.record(uuid = "r$it", recordedAt = Fixtures.RECORDED_AT + it) }
        val http = HttpConfig(batchSync = true, params = """{"device_id":"abc"}""")

        val body = JSONObject(BodyBuilder.build(records, http, sentAt))

        val array = body.getJSONArray("location")
        val uuids = (0 until array.length()).map { array.getJSONObject(it).getString("uuid") }
        assertEquals(listOf("r1", "r2", "r3"), uuids)
        for (i in 0 until array.length()) {
            assertEquals("2026-09-26T10:15:45.001Z", array.getJSONObject(i).getString("sent_at"))
        }
        assertEquals("abc", body.getString("device_id"))
    }

    @Test
    fun `batch with rootProperty dot is a bare array and params are ignored`() {
        val records = listOf(Fixtures.record(uuid = "a"), Fixtures.record(uuid = "b"))
        val http = HttpConfig(batchSync = true, rootProperty = ".", params = """{"device_id":"abc"}""")

        val body = JSONArray(BodyBuilder.build(records, http, sentAt))

        assertJsonEquals(RecordJson.toJsonArray(records, sentAt), body)
    }

    @Test
    fun `a batch of one is still an array`() {
        val body = JSONObject(BodyBuilder.build(listOf(golden), HttpConfig(batchSync = true), sentAt))

        assertEquals(1, body.getJSONArray("location").length())
    }

    @Test
    fun `sent_at is added and recorded_at is unchanged`() {
        val body = JSONObject(BodyBuilder.single(golden, HttpConfig(), sentAt)).getJSONObject("location")

        assertEquals("2026-09-26T10:15:45.001Z", body.getString("sent_at"))
        assertEquals("2026-09-26T10:15:30.456Z", body.getString("recorded_at"))
    }

    @Test
    fun `templates replace the record shape and keep the envelope`() {
        val http = HttpConfig(
            locationTemplate = """{"lat":<%= latitude %>,"id":"<%= uuid %>"}""",
            geofenceTemplate = """{"fence":"<%= geofence.identifier %>","action":"<%= geofence.action %>"}""",
            params = """{"device_id":"abc"}""",
            batchSync = true,
        )
        val geofence = Fixtures.record(
            uuid = "g",
            event = RecordEvent.GEOFENCE,
            geofence = GeofenceHit("home", GeofenceAction.ENTER, null),
        )

        val body = JSONObject(BodyBuilder.build(listOf(Fixtures.record(uuid = "l"), geofence), http, sentAt))

        assertJsonEquals(
            """{"location":[{"lat":24.7136,"id":"l"},{"fence":"home","action":"ENTER"}],"device_id":"abc"}""",
            body,
        )
    }

    @Test
    fun `template with rootProperty dot merges, and an array template is sent bare`() {
        val merged = JSONObject(
            BodyBuilder.single(
                golden,
                HttpConfig(
                    rootProperty = ".",
                    locationTemplate = """{"lat":<%= latitude %>}""",
                    params = """{"p":1}""",
                ),
                sentAt,
            ),
        )
        assertJsonEquals("""{"lat":24.7136,"p":1}""", merged)

        val bare = BodyBuilder.single(
            golden,
            HttpConfig(rootProperty = ".", locationTemplate = """[<%= latitude %>]""", params = """{"p":1}"""),
            sentAt,
        )
        assertJsonEquals(JSONArray("[24.7136]"), JSONArray(bare))
    }

    @Test
    fun `invalid template falls back to the default shape and logs an error`() {
        val http = HttpConfig(locationTemplate = """{"ts": <%= timestamp %>}""")

        val body = JSONObject(BodyBuilder.single(golden, http, sentAt))

        assertJsonEquals(RecordJson.toJson(golden, sentAt), body.getJSONObject("location"))
        assertTrue(log.lines.any { it.level == LogLevel.ERROR && it.message.contains(golden.uuid) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-batch body needs exactly one record`() {
        BodyBuilder.build(listOf(golden, golden), HttpConfig(batchSync = false), sentAt)
    }

    companion object {
        /** architecture §2, "Single record". */
        const val GOLDEN_BODY = """
        {
          "location": {
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
          },
          "device_id": "abc"
        }
        """
    }
}
