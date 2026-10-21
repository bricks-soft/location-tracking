package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.AuthorizationConfig
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Config mistakes rejected when the app sets them, instead of at upload time (recommendation 14). */
@RunWith(RobolectricTestRunner::class)
class ConfigChecksTest {
    private fun config(http: HttpConfig) = Config(http = http)

    private fun rejected(http: HttpConfig): String {
        try {
            ConfigChecks.check(config(http))
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
            return e.message!!
        }
        fail("expected INVALID_ARGUMENT")
        throw AssertionError()
    }

    @Test
    fun `a valid config passes without warnings`() {
        val http = HttpConfig(
            url = "https://example.com/locations?fleet=7",
            headers = mapOf("X-Api-Key" to "k1"),
            locationTemplate = """{"lat":<%= latitude %>,"id":"<%= uuid %>","at":"<%= recorded_at %>"}""",
            geofenceTemplate = """[{"id":"<%= geofence.identifier %>","device":<%= device %>}]""",
        )

        assertEquals(emptyList<String>(), ConfigChecks.check(config(http)))
        assertEquals(emptyList<String>(), ConfigChecks.check(Config()))
    }

    @Test
    fun `urls must be http or https with a host and without user info or fragment`() {
        assertTrue(rejected(HttpConfig(url = "ftp://example.com/x")).startsWith("config.http.url"))
        assertTrue(rejected(HttpConfig(url = "example.com/x")).startsWith("config.http.url"))
        assertTrue(rejected(HttpConfig(url = "https://user:pw@example.com/x")).contains("user info"))
        assertTrue(rejected(HttpConfig(url = "https://example.com/x#part")).contains("fragment"))
        ConfigChecks.check(config(HttpConfig(url = "  ")))
    }

    @Test
    fun `an http url is accepted with a warning naming the host`() {
        val warnings = ConfigChecks.check(config(HttpConfig(url = "http://10.0.2.2:8080/ingest")))

        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().contains("10.0.2.2"))
    }

    @Test
    fun `headers with line breaks or non-ASCII characters are rejected without echoing the value`() {
        val message = rejected(HttpConfig(headers = mapOf("Authorization" to "Bearer secret-value\r\nX-Evil: 1")))
        assertTrue(message, message.contains("http.headers") && message.contains("Authorization"))
        assertFalse(message.contains("secret-value"))

        assertTrue(rejected(HttpConfig(headers = mapOf("Bad Name" to "v"))).contains("Bad Name"))
        val refresh = HttpConfig(authorization = AuthorizationConfig(refreshHeaders = mapOf("X-Client" to "é")))
        assertTrue(rejected(refresh).contains("refreshHeaders"))
    }

    @Test
    fun `templates with unknown placeholders are rejected and the names are listed`() {
        val message = rejected(HttpConfig(locationTemplate = """{"a":<%= latitude %>,"b":<%= latitud %>}"""))

        assertTrue(message, message.startsWith("config.http.locationTemplate") && message.contains("'latitud'"))
    }

    @Test
    fun `templates that do not render to JSON are rejected`() {
        // A string placeholder without quotes renders to bare text.
        assertTrue(rejected(HttpConfig(locationTemplate = """{"id":<%= uuid %>}""")).contains("JSON"))
        // A scalar is not an object or array.
        assertTrue(rejected(HttpConfig(geofenceTemplate = """<%= latitude %>""")).startsWith("config.http.geofenceTemplate"))
        // A quoted placeholder renders as a string, or as null for a record without that value.
        ConfigChecks.check(config(HttpConfig(locationTemplate = """{"lat":"<%= latitude %>"}""")))
    }
}
