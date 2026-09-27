package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.config.ConfigSamples.CUSTOM
import com.brickssoft.locationtracking.config.ConfigSamples.CUSTOM_JSON
import com.brickssoft.locationtracking.config.ConfigSamples.DEFAULT_JSON
import com.brickssoft.locationtracking.config.ConfigSamples.RUNTIME
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.LocationProviderSetting
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigJsonTest {
    private val log = FakeLogStore()

    @Before
    fun setUp() {
        Logger.sink = log
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    // ---- toJson

    @Test
    fun `toJson of the defaults emits every key of the TS Config`() {
        assertJsonEquals(DEFAULT_JSON, ConfigJson.toJson(Config()))
    }

    @Test
    fun `toJson emits every field of a custom config`() {
        assertJsonEquals(CUSTOM_JSON, ConfigJson.toJson(CUSTOM))
    }

    @Test
    fun `toJson emits unset nullable values as JSON null`() {
        val json = ConfigJson.toJson(Config())

        val http = json.getJSONObject("http")
        assertTrue(http.has("url") && http.isNull("url"))
        assertTrue(http.has("authorization") && http.isNull("authorization"))
        val notification = json.getJSONObject("notification")
        assertTrue(notification.has("title") && notification.isNull("title"))
    }

    @Test
    fun `toJson emits invalid stored JSON text as an empty object`() {
        val c = Config(http = HttpConfig(params = "not json"), persistence = PersistenceConfig(extras = "[1]"))

        val json = ConfigJson.toJson(c)

        assertJsonEquals(JSONObject(), json.getJSONObject("http").getJSONObject("params"))
        assertJsonEquals(JSONObject(), json.getJSONObject("persistence").getJSONObject("extras"))
    }

    // ---- parse: full, empty, round trip

    @Test
    fun `parse of a full config onto the defaults sets every field`() {
        assertEquals(CUSTOM, ConfigJson.parse(JSONObject(CUSTOM_JSON)))
    }

    @Test
    fun `parse of an empty object keeps every base value`() {
        assertEquals(CUSTOM, ConfigJson.parse(JSONObject(), CUSTOM))
        assertEquals(Config(), ConfigJson.parse(JSONObject()))
    }

    @Test
    fun `parse of toJson round-trips`() {
        assertEquals(Config(), ConfigJson.parse(ConfigJson.toJson(Config())))
        assertEquals(CUSTOM, ConfigJson.parse(ConfigJson.toJson(CUSTOM)))
        assertEquals(CUSTOM, ConfigJson.parse(ConfigJson.toJson(CUSTOM), Config()))
        assertEquals(Config(), ConfigJson.parse(ConfigJson.toJson(Config()), CUSTOM))
        // through text, as persisted
        assertEquals(CUSTOM, ConfigJson.parse(JSONObject(ConfigJson.toJson(CUSTOM).toString())))
    }

    // ---- parse: deep merge

    @Test
    fun `parse merges only the given keys of a group`() {
        val json = JSONObject("""{"geolocation":{"distanceFilter":5,"desiredAccuracy":"low"},"heartbeat":{"enabled":true}}""")

        val result = ConfigJson.parse(json, CUSTOM)

        assertEquals(
            CUSTOM.copy(
                geolocation = CUSTOM.geolocation.copy(distanceFilter = 5.0, desiredAccuracy = DesiredAccuracy.LOW),
                heartbeat = CUSTOM.heartbeat.copy(enabled = true),
            ),
            result,
        )
    }

    @Test
    fun `parse merges the nested location filter key by key`() {
        val json = JSONObject("""{"geolocation":{"filter":{"useKalman":false,"maxImpliedSpeed":30}}}""")

        val result = ConfigJson.parse(json, CUSTOM)

        val filter = CUSTOM.geolocation.filter.copy(useKalman = false, maxImpliedSpeed = 30.0)
        assertEquals(CUSTOM.copy(geolocation = CUSTOM.geolocation.copy(filter = filter)), result)
    }

    @Test
    fun `parse merges authorization onto the existing one`() {
        val json = JSONObject("""{"http":{"authorization":{"accessToken":"access-2","expires":-1}}}""")

        val result = ConfigJson.parse(json, CUSTOM)

        val auth = CUSTOM.http.authorization!!.copy(accessToken = "access-2", expires = -1)
        assertEquals(CUSTOM.copy(http = CUSTOM.http.copy(authorization = auth)), result)
    }

    @Test
    fun `parse creates authorization from defaults when the base has none`() {
        val json = JSONObject("""{"http":{"authorization":{"refreshUrl":"https://x/refresh","strategy":"jwt"}}}""")

        val result = ConfigJson.parse(json)

        assertEquals(AuthorizationConfig(refreshUrl = "https://x/refresh"), result.http.authorization)
    }

    @Test
    fun `parse replaces arrays as a whole`() {
        val one = ConfigJson.parse(
            JSONObject("""{"notification":{"actions":[{"id":"sos","label":"SOS"}]}}"""),
            CUSTOM,
        )
        val none = ConfigJson.parse(JSONObject("""{"notification":{"actions":[]}}"""), CUSTOM)

        assertEquals(listOf(NotificationActionButton("sos", "SOS")), one.notification.actions)
        assertEquals(emptyList<NotificationActionButton>(), none.notification.actions)
        assertEquals(CUSTOM.notification.copy(actions = one.notification.actions), one.notification)
    }

    @Test
    fun `parse replaces map-valued objects as a whole`() {
        val json = JSONObject(
            """
            {
              "http": {
                "headers": { "A": "1", "N": 2, "Dropped": null },
                "params": { "only": true },
                "authorization": { "refreshPayload": { "token": "{refreshToken}" }, "refreshHeaders": {} }
              },
              "persistence": { "extras": { "nested": { "x": 1 } } }
            }
            """,
        )

        val result = ConfigJson.parse(json, CUSTOM)

        assertEquals(mapOf("A" to "1", "N" to "2"), result.http.headers)
        assertJsonEquals("""{"only":true}""", JSONObject(result.http.params))
        assertJsonEquals("""{"token":"{refreshToken}"}""", JSONObject(result.http.authorization!!.refreshPayload))
        assertEquals(emptyMap<String, String>(), result.http.authorization!!.refreshHeaders)
        assertJsonEquals("""{"nested":{"x":1}}""", JSONObject(result.persistence.extras))
    }

    // ---- parse: null resets

    @Test
    fun `null resets every leaf key to its default`() {
        // Same shape as the full config, but every leaf value is null.
        val json = JSONObject()
        val full = ConfigJson.toJson(CUSTOM)
        for (group in full.keys()) {
            val value = full.get(group)
            if (value is JSONObject) {
                val nulls = JSONObject()
                for (key in value.keys()) nulls.put(key, JSONObject.NULL)
                json.put(group, nulls)
            } else {
                json.put(group, JSONObject.NULL)
            }
        }
        json.getJSONObject("geolocation").put("filter", nullLeaves(full.getJSONObject("geolocation").getJSONObject("filter")))

        val result = ConfigJson.parse(json, CUSTOM)

        assertEquals(Config(), result)
    }

    @Test
    fun `null resets nested authorization keys to their defaults`() {
        val auth = nullLeaves(ConfigJson.toJson(CUSTOM).getJSONObject("http").getJSONObject("authorization"))
        val json = JSONObject().put("http", JSONObject().put("authorization", auth))

        val result = ConfigJson.parse(json, CUSTOM)

        assertEquals(AuthorizationConfig(), result.http.authorization)
    }

    @Test
    fun `null resets a whole group to its default`() {
        val defaults = Config()
        val groups = ConfigJson.toJson(CUSTOM).keys().asSequence().toList()
        assertEquals(11, groups.size)

        for (group in groups) {
            val result = ConfigJson.parse(JSONObject().put(group, JSONObject.NULL), CUSTOM)

            val expected = when (group) {
                "geolocation" -> CUSTOM.copy(geolocation = defaults.geolocation)
                "activity" -> CUSTOM.copy(activity = defaults.activity)
                "heartbeat" -> CUSTOM.copy(heartbeat = defaults.heartbeat)
                "http" -> CUSTOM.copy(http = defaults.http)
                "persistence" -> CUSTOM.copy(persistence = defaults.persistence)
                "app" -> CUSTOM.copy(app = defaults.app)
                "notification" -> CUSTOM.copy(notification = defaults.notification)
                "geofence" -> CUSTOM.copy(geofence = defaults.geofence)
                "logger" -> CUSTOM.copy(logger = defaults.logger)
                "backgroundPermissionRationale" ->
                    CUSTOM.copy(backgroundPermissionRationale = defaults.backgroundPermissionRationale)
                "locationProvider" -> CUSTOM.copy(locationProvider = LocationProviderSetting.AUTO)
                else -> error("unexpected group $group")
            }
            assertEquals(group, expected, result)
        }
    }

    @Test
    fun `null resets nested filter and authorization groups`() {
        val json = JSONObject("""{"geolocation":{"filter":null},"http":{"authorization":null,"url":null}}""")

        val result = ConfigJson.parse(json, CUSTOM)

        assertEquals(LocationFilterConfig(), result.geolocation.filter)
        assertEquals(CUSTOM.geolocation.copy(filter = LocationFilterConfig()), result.geolocation)
        assertNull(result.http.authorization)
        assertNull(result.http.url)
        assertEquals(CUSTOM.http.copy(authorization = null, url = null), result.http)
    }

    // ---- parse: unknown keys, enums, types

    @Test
    fun `unknown keys are ignored with a warning`() {
        val json = JSONObject(
            """
            {
              "foo": 1,
              "geolocation": { "bar": 2, "distanceFilter": 3, "filter": { "baz": true } },
              "notification": { "actions": [ { "id": "a", "label": "A", "icon": "x" } ] }
            }
            """,
        )

        val result = ConfigJson.parse(json)

        assertEquals(3.0, result.geolocation.distanceFilter, 0.0)
        assertEquals(listOf(NotificationActionButton("a", "A")), result.notification.actions)
        val warnings = log.lines.filter { it.level == LogLevel.WARN }.map { it.message }
        for (key in listOf("'foo'", "'geolocation.bar'", "'geolocation.filter.baz'", "'notification.actions[0].icon'")) {
            assertTrue("$key in $warnings", warnings.any { it.contains(key) })
        }
        assertEquals(4, warnings.size)
    }

    @Test
    fun `enum values are parsed from wire strings, ignoring case`() {
        val json = JSONObject(
            """
            {"geolocation":{"desiredAccuracy":"PASSIVE"},"http":{"method":"patch"},
             "notification":{"priority":"Max"},"logger":{"logLevel":"off"},"locationProvider":"android"}
            """,
        )

        val result = ConfigJson.parse(json)

        assertEquals(DesiredAccuracy.PASSIVE, result.geolocation.desiredAccuracy)
        assertEquals(HttpMethod.PATCH, result.http.method)
        assertEquals(NotificationPriority.MAX, result.notification.priority)
        assertEquals(LogLevel.OFF, result.logger.logLevel)
        assertEquals(LocationProviderSetting.ANDROID, result.locationProvider)
    }

    @Test
    fun `invalid enum values throw INVALID_ARGUMENT`() {
        val cases = listOf(
            """{"geolocation":{"desiredAccuracy":"best"}}""" to "geolocation.desiredAccuracy",
            """{"http":{"method":"GET"}}""" to "http.method",
            """{"http":{"authorization":{"strategy":"basic"}}}""" to "http.authorization.strategy",
            """{"http":{"authorization":{"refreshPayloadEncoding":"xml"}}}""" to "http.authorization.refreshPayloadEncoding",
            """{"notification":{"priority":"urgent"}}""" to "notification.priority",
            """{"logger":{"logLevel":"trace"}}""" to "logger.logLevel",
            """{"locationProvider":"gps"}""" to "locationProvider",
            """{"locationProvider":1}""" to "locationProvider",
        )
        for ((text, path) in cases) assertInvalid(text, path)
    }

    @Test
    fun `values of the wrong type throw INVALID_ARGUMENT`() {
        val cases = listOf(
            """{"geolocation":"high"}""" to "geolocation",
            """{"geolocation":{"distanceFilter":"far"}}""" to "geolocation.distanceFilter",
            """{"geolocation":{"distanceFilter":true}}""" to "geolocation.distanceFilter",
            """{"geolocation":{"filter":[]}}""" to "geolocation.filter",
            """{"heartbeat":{"minInterval":{}}}""" to "heartbeat.minInterval",
            """{"http":{"autoSync":"maybe"}}""" to "http.autoSync",
            """{"http":{"url":5}}""" to "http.url",
            """{"http":{"headers":"X-A: 1"}}""" to "http.headers",
            """{"http":{"params":[1]}}""" to "http.params",
            """{"http":{"authorization":"token"}}""" to "http.authorization",
            """{"persistence":{"extras":"x"}}""" to "persistence.extras",
            """{"notification":{"actions":{}}}""" to "notification.actions",
            """{"notification":{"actions":[null]}}""" to "notification.actions[0]",
            """{"notification":{"actions":["stop"]}}""" to "notification.actions[0]",
            """{"notification":{"actions":[{"label":"x"}]}}""" to "notification.actions[0].id",
            """{"notification":{"actions":[{"id":"a","label":"A"},{"id":"b"}]}}""" to "notification.actions[1].label",
            """{"notification":{"text":false}}""" to "notification.text",
        )
        for ((text, path) in cases) assertInvalid(text, path)
    }

    @Test
    fun `numeric and boolean strings are accepted`() {
        val json = JSONObject("""{"geolocation":{"distanceFilter":"15.5","stopTimeout":"7"},"http":{"autoSync":"false"}}""")

        val result = ConfigJson.parse(json)

        assertEquals(15.5, result.geolocation.distanceFilter, 0.0)
        assertEquals(7, result.geolocation.stopTimeout)
        assertEquals(false, result.http.autoSync)
    }

    @Test
    fun `parse does not clamp`() {
        val result = ConfigJson.parse(JSONObject("""{"heartbeat":{"minInterval":10}}"""))

        assertEquals(10, result.heartbeat.minInterval)
    }

    // ---- stateToJson

    @Test
    fun `stateToJson emits the TS State shape`() {
        val state = State(config = CUSTOM, runtime = RUNTIME, backend = ProviderKind.HMS)

        val json = ConfigJson.stateToJson(state)

        val expected = JSONObject(
            """
            {"enabled":true,"trackingMode":"geofences","isMoving":true,"odometer":1532.4,"backend":"hms",
             "lastRecordAt":"2026-09-26T10:15:30.456Z"}
            """,
        ).put("config", JSONObject(CUSTOM_JSON))
        assertJsonEquals(expected, json)
    }

    @Test
    fun `stateToJson of a fresh state has defaults and a null lastRecordAt`() {
        val json = ConfigJson.stateToJson(State(Config(), RuntimeState(), ProviderKind.GMS))

        val expected = JSONObject(
            """{"enabled":false,"trackingMode":"location","isMoving":false,"odometer":0,"backend":"gms","lastRecordAt":null}""",
        ).put("config", JSONObject(DEFAULT_JSON))
        assertJsonEquals(expected, json)
        assertTrue(json.has("lastRecordAt"))
    }

    private fun nullLeaves(obj: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in obj.keys()) out.put(key, JSONObject.NULL)
        return out
    }

    private fun assertInvalid(text: String, path: String) {
        try {
            ConfigJson.parse(JSONObject(text), CUSTOM)
            fail("expected INVALID_ARGUMENT for $text")
        } catch (e: TrackingException) {
            assertEquals(text, ErrorCode.INVALID_ARGUMENT, e.code)
            assertTrue("'${e.message}' should name $path", e.message!!.startsWith("config.$path "))
        }
    }
}
