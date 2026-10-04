package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.HeartbeatMeta
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TemplateRendererTest {
    private val log = FakeLogStore()
    private val sentAt = Fixtures.RECORDED_AT + 14_545

    @Before
    fun setUp() {
        Logger.sink = log
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun render(template: String, record: Record = Fixtures.record()) =
        TemplateRenderer.render(template, record, sentAt)

    @Test
    fun `numbers and booleans are inserted bare`() {
        val json = render(
            """{"lat":<%= latitude %>,"lng":<%=longitude%>,"acc":<%= accuracy %>,"alt":<%= altitude %>,
               "speed":<%= speed %>,"heading":<%= heading %>,"odometer":<%= odometer %>,"moving":<%= is_moving %>,
               "mock":<%= mock %>,"conf":<%= activity.confidence %>,"level":<%= battery.level %>,
               "charging":<%= battery.is_charging %>,"elapsed":<%= elapsed_realtime_ms %>,"boot":<%= boot_count %>}""",
        ) as JSONObject

        assertJsonEquals(
            """{"lat":24.7136,"lng":46.6753,"acc":5.2,"alt":612.3,"speed":13.4,"heading":271.5,"odometer":1532.4,
               "moving":true,"mock":false,"conf":92,"level":0.81,"charging":false,"elapsed":86400123,"boot":42}""",
            json,
        )
        // Floats keep their short decimal form (5.2, not 5.199999809265137).
        assertEquals("5.2", TemplateRenderer.literal("accuracy", Fixtures.record(), sentAt))
    }

    @Test
    fun `strings are inserted without quotes so templates quote them`() {
        val json = render(
            """{"id":"<%= uuid %>","event":"<%= event %>","ts":"<%= timestamp %>","recorded":"<%= recorded_at %>",
               "sent":"<%= sent_at %>","activity":"<%= activity.type %>","backend":"<%= backend %>"}""",
        ) as JSONObject

        assertJsonEquals(
            """{"id":"0d6c6a1e-6c1e-4b8e-9d8f-2b6f3f0f7a11","event":"location","ts":"2026-09-26T10:15:30.123Z",
               "recorded":"2026-09-26T10:15:30.456Z","sent":"2026-09-26T10:15:45.001Z","activity":"in_vehicle",
               "backend":"gms"}""",
            json,
        )
    }

    @Test
    fun `quotes, backslashes and control characters in strings are escaped`() {
        val reason = "he said \"hi\" \\ then\nleft\t\u0001"
        val json = render("""{"r":"<%= reason %>"}""", Fixtures.record(reason = reason)) as JSONObject

        assertEquals(reason, json.getString("r"))
    }

    @Test
    fun `missing values are inserted as null`() {
        val heartbeat = Fixtures.record(event = RecordEvent.HEARTBEAT, location = null, backend = null)
        val json = render(
            """{"lat":<%= latitude %>,"lng":<%= longitude %>,"acc":<%= accuracy %>,"ts":<%= timestamp %>,
               "reason":<%= reason %>,"backend":<%= backend %>,"fence":<%= geofence.identifier %>,
               "gps":<%= provider.gps %>,"mock":<%= mock %>}""",
            heartbeat,
        ) as JSONObject

        assertJsonEquals(
            """{"lat":null,"lng":null,"acc":null,"ts":null,"reason":null,"backend":null,"fence":null,"gps":null,
               "mock":false}""",
            json,
        )
        // Unknown optional coordinate fields are null too.
        val bare = Fixtures.record(location = Fixtures.location(altitude = null, speed = null))
        assertEquals("null", TemplateRenderer.literal("altitude", bare, sentAt))
        assertEquals("null", TemplateRenderer.literal("speed", bare, sentAt))
    }

    @Test
    fun `a quoted placeholder with a null value becomes JSON null`() {
        val heartbeat = Fixtures.record(event = RecordEvent.HEARTBEAT, location = null, backend = null)
        val json = render(
            """{"ts":"<%= timestamp %>","backend":"<%=backend%>","lat":"<%= latitude %>","note":"at <%= reason %>",
               "id":"<%= uuid %>"}""",
            heartbeat,
        ) as JSONObject

        assertJsonEquals(
            """{"ts":null,"backend":null,"lat":null,"note":"at null","id":"0d6c6a1e-6c1e-4b8e-9d8f-2b6f3f0f7a11"}""",
            json,
        )
    }

    @Test
    fun `extras is inserted as JSON object text`() {
        val withExtras = Fixtures.record(extras = """{"driver_id":7,"tags":["a","b"],"meta":{"x":null}}""")
        assertJsonEquals(
            """{"extras":{"driver_id":7,"tags":["a","b"],"meta":{"x":null}}}""",
            render("""{"extras":<%= extras %>}""", withExtras) as JSONObject,
        )
        assertJsonEquals(
            """{"extras":{}}""",
            render("""{"extras":<%= extras %>}""", Fixtures.record(extras = null)) as JSONObject,
        )
    }

    @Test
    fun `geofence and provider placeholders`() {
        val geofence = Fixtures.record(
            event = RecordEvent.GEOFENCE,
            geofence = GeofenceHit("home", GeofenceAction.EXIT, """{"k":1}"""),
        )
        assertJsonEquals(
            """{"id":"home","action":"EXIT"}""",
            render("""{"id":"<%= geofence.identifier %>","action":"<%= geofence.action %>"}""", geofence) as JSONObject,
        )

        val providerChange = Fixtures.record(
            event = RecordEvent.PROVIDERCHANGE,
            provider = Fixtures.providerState(enabled = false, gps = false, network = true),
        )
        assertJsonEquals(
            """{"enabled":false,"gps":false,"network":true,"permission":"always"}""",
            render(
                """{"enabled":<%= provider.enabled %>,"gps":<%= provider.gps %>,"network":<%= provider.network %>,
                   "permission":"<%= provider.permission %>"}""",
                providerChange,
            ) as JSONObject,
        )
    }

    @Test
    fun `placeholders tolerate surrounding whitespace`() {
        val json = render("{\"a\":<%=latitude%>,\"b\":<%=   latitude   %>,\"c\":<%=\n\tlatitude \n%>}") as JSONObject

        assertJsonEquals("""{"a":24.7136,"b":24.7136,"c":24.7136}""", json)
    }

    @Test
    fun `every documented placeholder is known`() {
        val record = Fixtures.record(
            geofence = GeofenceHit("home", GeofenceAction.ENTER, null),
            provider = Fixtures.providerState(),
            reason = "start",
        )
        for (name in TemplateRenderer.PLACEHOLDERS) {
            assertNotNull("placeholder $name", TemplateRenderer.literal(name, record, sentAt))
        }
        assertEquals(33, TemplateRenderer.PLACEHOLDERS.size)
    }

    @Test
    fun `record is the default record object, with sent_at`() {
        val heartbeat = Fixtures.record(
            event = RecordEvent.HEARTBEAT,
            location = null,
            provider = Fixtures.providerState(),
            heartbeat = HeartbeatMeta(HeartbeatStrategy.EXACT, 180, 300, null, true, false),
        )
        val json = render("""{"id":"<%= uuid %>","raw":<%= record %>}""", heartbeat) as JSONObject

        assertJsonEquals(RecordJson.toJson(heartbeat, sentAt).toString(), json.getJSONObject("raw"))
        assertTrue(json.getJSONObject("raw").isNull("coords"))
        assertEquals("precise", json.getJSONObject("raw").getJSONObject("provider").getString("accuracy"))
    }

    @Test
    fun `unknown placeholder becomes an empty string and logs a warning`() {
        val json = render("""{"a":"<%= nope %>","b":<%= latitude %>}""") as JSONObject

        assertJsonEquals("""{"a":"","b":24.7136}""", json)
        assertTrue(log.lines.any { it.level == LogLevel.WARN && it.message.contains("'nope'") })
    }

    @Test
    fun `a template may render an array`() {
        val json = render("""[<%= longitude %>, <%= latitude %>]""")

        assertJsonEquals(JSONArray("[46.6753, 24.7136]"), json)
    }

    @Test
    fun `invalid JSON after rendering returns null and logs an error`() {
        // An unquoted string placeholder, a missing brace, trailing garbage and a scalar.
        val invalid = listOf(
            """{"ts":<%= timestamp %>}""",
            """{"lat":<%= latitude %>""",
            """{"lat":<%= latitude %>} extra""",
            """<%= latitude %>""",
            """{"n": 1e999}""",
        )
        for (template in invalid) {
            log.lines.clear()
            assertNull(template, render(template))
            assertTrue(template, log.lines.any { it.level == LogLevel.ERROR })
        }
    }

    @Test
    fun `templateFor picks the geofence template for geofence records only`() {
        val http = HttpConfig(locationTemplate = "L", geofenceTemplate = "G")
        assertEquals("G", TemplateRenderer.templateFor(Fixtures.record(event = RecordEvent.GEOFENCE), http))
        assertEquals("L", TemplateRenderer.templateFor(Fixtures.record(event = RecordEvent.LOCATION), http))
        assertEquals("L", TemplateRenderer.templateFor(Fixtures.record(event = RecordEvent.HEARTBEAT), http))

        val onlyLocation = HttpConfig(locationTemplate = "L", geofenceTemplate = " ")
        assertEquals("L", TemplateRenderer.templateFor(Fixtures.record(event = RecordEvent.GEOFENCE), onlyLocation))
        assertNull(TemplateRenderer.templateFor(Fixtures.record(), HttpConfig(locationTemplate = "")))
    }

    @Test
    fun `sent_at uses the send time`() {
        val json = render("""{"sent":"<%= sent_at %>","recorded":"<%= recorded_at %>"}""") as JSONObject

        assertEquals(Iso8601.format(sentAt), json.getString("sent"))
        assertEquals(Iso8601.format(Fixtures.RECORDED_AT), json.getString("recorded"))
    }
}
