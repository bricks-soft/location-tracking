package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.logging.LogQuery
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.LatLng
import com.brickssoft.locationtracking.permission.PermissionType
import com.brickssoft.locationtracking.position.CurrentPositionOptions
import com.brickssoft.locationtracking.position.WatchPositionOptions
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OptionParsersTest {
    private fun json(text: String) = JSONObject(text)

    private fun assertInvalid(text: String, parse: (JSONObject) -> Any?) {
        try {
            parse(json(text))
            fail("expected INVALID_ARGUMENT for $text")
        } catch (e: TrackingException) {
            assertEquals(text, ErrorCode.INVALID_ARGUMENT, e.code)
        }
    }

    @Test
    fun `current position options map every field`() {
        assertEquals(CurrentPositionOptions(), OptionParsers.currentPositionOptions(JSONObject()))
        assertEquals(
            CurrentPositionOptions(1, 500L, 0L, DesiredAccuracy.PASSIVE, true, null),
            OptionParsers.currentPositionOptions(
                json("""{"samples":1,"timeout":500.9,"maximumAge":null,"desiredAccuracy":"PASSIVE","persist":true}"""),
            ),
        )
        val withExtras = OptionParsers.currentPositionOptions(json("""{"extras":{"a":[1,2]}}"""))
        assertJsonEquals("""{"a":[1,2]}""", JSONObject(withExtras.extras!!))
    }

    @Test
    fun `current position options reject bad values`() {
        for (
        text in listOf(
            """{"samples":0}""", """{"samples":1.5}""", """{"samples":"3"}""", """{"timeout":0}""",
            """{"maximumAge":-1}""", """{"desiredAccuracy":"best"}""", """{"persist":1}""", """{"extras":"x"}""",
        )
        ) {
            assertInvalid(text) { OptionParsers.currentPositionOptions(it) }
        }
    }

    @Test
    fun `watch position options`() {
        assertEquals(WatchPositionOptions(), OptionParsers.watchPositionOptions(JSONObject()))
        assertEquals(
            WatchPositionOptions(0L, DesiredAccuracy.LOW, true, """{"k":"v"}"""),
            OptionParsers.watchPositionOptions(json("""{"interval":0,"desiredAccuracy":"low","persist":true,"extras":{"k":"v"}}""")),
        )
        assertInvalid("""{"interval":-10}""") { OptionParsers.watchPositionOptions(it) }
        assertInvalid("""{"interval":"fast"}""") { OptionParsers.watchPositionOptions(it) }
    }

    @Test
    fun `circle and polygon geofences`() {
        val circle = OptionParsers.geofence(
            json(
                """{"identifier":"home","latitude":24.7,"longitude":46.6,"radius":120.5,"notifyOnExit":false,
                   "notifyOnDwell":true,"loiteringDelay":60000,"extras":{"floor":3}}""",
            ),
        )
        assertEquals("home", circle.identifier)
        assertEquals(120.5f, circle.radius, 0f)
        assertFalse(circle.notifyOnExit)
        assertTrue(circle.notifyOnDwell)
        assertEquals(60_000L, circle.loiteringDelay)
        assertEquals("""{"floor":3}""", circle.extras)

        val polygon = OptionParsers.geofence(json("""{"identifier":"zone","vertices":[[1,2],[1,3],[2,3],[2,2]]}"""))
        assertEquals(listOf(LatLng(1.0, 2.0), LatLng(1.0, 3.0), LatLng(2.0, 3.0), LatLng(2.0, 2.0)), polygon.vertices)
    }

    @Test
    fun `invalid geofences`() {
        val bad = listOf(
            """{"latitude":1,"longitude":2,"radius":3}""",
            """{"identifier":"a","latitude":1,"longitude":2}""",
            """{"identifier":"a","latitude":1,"longitude":2,"radius":-3}""",
            """{"identifier":"a","latitude":95,"longitude":2,"radius":3}""",
            """{"identifier":"a","latitude":1,"longitude":181,"radius":3}""",
            """{"identifier":"a","vertices":[[1,2],[1,3]]}""",
            """{"identifier":"a","vertices":[[1,2],[1,3],[100,3]]}""",
            """{"identifier":"a","vertices":"none"}""",
            """{"identifier":"a","latitude":1,"longitude":2,"radius":3,"loiteringDelay":-1}""",
            """{"identifier":"a","latitude":1,"longitude":2,"radius":3,"extras":"text"}""",
            """{"identifier":"a","latitude":1,"longitude":2,"radius":3,"notifyOnEntry":"yes"}""",
            """{"identifier":123,"latitude":1,"longitude":2,"radius":3}""",
            """{"identifier":"a","latitude":"45.1","longitude":2,"radius":3}""",
            """{"identifier":"a","latitude":1,"longitude":2,"radius":"100"}""",
        )
        for (text in bad) assertInvalid(text) { OptionParsers.geofence(it) }
    }

    @Test
    fun `geofence arrays`() {
        val list = OptionParsers.geofences(
            json("""{"geofences":[{"identifier":"a","latitude":1,"longitude":2,"radius":3},{"identifier":"b","latitude":1,"longitude":2,"radius":3}]}"""),
        )
        assertEquals(listOf("a", "b"), list.map { it.identifier })
        assertTrue(OptionParsers.geofences(json("""{"geofences":[]}""")).isEmpty())
        assertInvalid("""{}""") { OptionParsers.geofences(it) }
        assertInvalid("""{"geofences":[1]}""") { OptionParsers.geofences(it) }
        assertInvalid("""{"geofences":{"identifier":"a"}}""") { OptionParsers.geofences(it) }
    }

    @Test
    fun `insert location input is validated and returned unchanged`() {
        val options = json(
            """{"location":{"coords":{"latitude":-33.9,"longitude":151.2,"accuracy":4,"altitude":-10,"speed":0,
               "heading":359.9,"heading_accuracy":null},"timestamp":"2026-09-26T10:15:30Z","event":"heartbeat",
               "is_moving":false,"extras":{}}}""",
        )

        assertSame(options.getJSONObject("location"), OptionParsers.insertLocationInput(options))
    }

    @Test
    fun `log query`() {
        assertEquals(LogQuery(), OptionParsers.logQuery(JSONObject()))
        assertEquals(
            LogQuery(start = 5L, end = null, level = LogLevel.DEBUG, limit = 0, ascending = true),
            OptionParsers.logQuery(json("""{"start":5,"level":"DEBUG","limit":0,"order":"asc"}""")),
        )
        for (text in listOf("""{"start":10,"end":5}""", """{"level":"loud"}""", """{"limit":-1}""", """{"order":1}""")) {
            assertInvalid(text) { OptionParsers.logQuery(it) }
        }
    }

    @Test
    fun `log level excludes off`() {
        assertEquals(LogLevel.VERBOSE, OptionParsers.logLevel(json("""{"level":"verbose"}""")))
        assertInvalid("""{"level":"off"}""") { OptionParsers.logLevel(it) }
        assertInvalid("""{}""") { OptionParsers.logLevel(it) }
    }

    @Test
    fun `permission types`() {
        assertEquals(OptionParsers.DEFAULT_PERMISSION_ORDER, OptionParsers.permissionTypes(JSONObject()))
        assertEquals(OptionParsers.DEFAULT_PERMISSION_ORDER, OptionParsers.permissionTypes(json("""{"permissions":null}""")))
        assertEquals(
            listOf(PermissionType.NOTIFICATIONS, PermissionType.ACTIVITY_RECOGNITION),
            OptionParsers.permissionTypes(json("""{"permissions":["activityRecognition","notifications"]}""")),
        )
        assertInvalid("""{"permissions":["location","camera"]}""") { OptionParsers.permissionTypes(it) }
        assertInvalid("""{"permissions":"location"}""") { OptionParsers.permissionTypes(it) }
    }

    @Test
    fun `upload log options`() {
        val o = OptionParsers.uploadLogOptions(
            json("""{"url":"http://10.0.2.2:8080/log","headers":{"A":"1","B":true,"C":null},"params":{"p":1}}"""),
        )
        assertEquals("http://10.0.2.2:8080/log", o.url)
        assertEquals(mapOf("A" to "1", "B" to "true"), o.headers)
        assertJsonEquals("""{"p":1}""", JSONObject(o.paramsJson!!))
        assertNull(OptionParsers.uploadLogOptions(json("""{"url":"https://x.io"}""")).paramsJson)
        assertInvalid("""{"url":"not a url"}""") { OptionParsers.uploadLogOptions(it) }
        assertInvalid("""{"url":"https://x.io","headers":{"A":{"nested":1}}}""") { OptionParsers.uploadLogOptions(it) }
    }

    @Test
    fun `primitives treat null as missing and reject wrong types`() {
        val o = json("""{"s":"x","b":true,"n":1.5,"nil":null,"blank":"  ","nan":"NaN"}""")
        assertEquals("x", OptionParsers.optString(o, "s"))
        assertNull(OptionParsers.optString(o, "nil"))
        assertNull(OptionParsers.optBoolean(o, "missing"))
        assertEquals(1.5, OptionParsers.optNumber(o, "n")!!, 0.0)
        assertInvalid("""{"n":"1"}""") { OptionParsers.optNumber(it, "n") }
        assertInvalid("""{"b":"true"}""") { OptionParsers.optBoolean(it, "b") }
        assertInvalid("""{"blank":"  "}""") { OptionParsers.requireString(it, "blank") }
        assertInvalid("""{}""") { OptionParsers.requireObject(it, "config") }
        assertInvalid("""{"n":3000000000}""") { OptionParsers.optInt(it, "n", min = 0) }
        assertEquals(3_000_000_000L, OptionParsers.optLong(json("""{"n":3000000000}"""), "n", min = 0))
    }
}
