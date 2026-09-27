package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.AuthorizationConfig
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.config.RefreshPayloadEncoding
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import com.brickssoft.locationtracking.testing.RecordingEventBus
import com.brickssoft.locationtracking.testing.testDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AuthorizationManagerTest {
    private val server = MockWebServer()
    private val clock = FakeClock()
    private val events = RecordingEventBus()
    private val client = OkHttpClient()
    private lateinit var configStore: FakeConfigStore

    @Before
    fun setUp() {
        server.start()
        Logger.sink = FakeLogStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Logger.sink = null
    }

    private fun auth(
        accessToken: String? = "old",
        refreshToken: String? = "r1",
        refreshUrl: String? = server.url("/refresh").toString(),
        payload: String = """{"refresh_token":"{refreshToken}","grant_type":"refresh_token"}""",
        encoding: RefreshPayloadEncoding = RefreshPayloadEncoding.JSON,
        headers: Map<String, String> = mapOf("X-Api-Key" to "k"),
        expires: Long = -1,
    ) = AuthorizationConfig(
        accessToken = accessToken,
        refreshToken = refreshToken,
        refreshUrl = refreshUrl,
        refreshPayload = payload,
        refreshHeaders = headers,
        refreshPayloadEncoding = encoding,
        expires = expires,
    )

    private fun TestScope.manager(auth: AuthorizationConfig?): AuthorizationManager {
        configStore = FakeConfigStore(Config(http = HttpConfig(url = "https://example.com", authorization = auth)))
        return AuthorizationManager(configStore, events, clock, testDispatchers(testScheduler))
    }

    private val storedAuth get() = configStore.config.value.http.authorization!!

    private fun takeRequest(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test
    fun `refresh posts the JSON payload and persists the new tokens`() = runTest {
        val expiresSeconds = 1_790_421_330L // 2026-09-26T11:15:30Z
        server.enqueue(
            MockResponse().setBody("""{"accessToken":"new","refreshToken":"r2","expires":$expiresSeconds}"""),
        )
        val manager = manager(auth())

        val token = manager.refresh(client, "old")

        assertEquals("new", token)
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/refresh", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("k", request.getHeader("X-Api-Key"))
        assertNull(request.getHeader("Authorization"))
        assertJsonEquals(
            """{"refresh_token":"r1","grant_type":"refresh_token"}""",
            JSONObject(request.body.readUtf8()),
        )
        assertEquals("new", storedAuth.accessToken)
        assertEquals("r2", storedAuth.refreshToken)
        assertEquals(expiresSeconds * 1000, storedAuth.expires)
        assertEquals(storedAuth.refreshUrl, server.url("/refresh").toString())
        assertTrue(configStore.calls.contains("update"))

        val event = events.ofType<TrackingEvent.Authorization>().single()
        assertTrue(event.success)
        assertEquals(200, event.status)
        assertNull(event.error)
        assertEquals("new", JSONObject(event.responseJson!!).getString("accessToken"))
    }

    @Test
    fun `form encoding, snake_case fields, expires_in, and the refresh token is kept when absent`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"new","expires_in":3600}"""))
        val manager = manager(
            auth(
                refreshToken = "r 1&x",
                encoding = RefreshPayloadEncoding.FORM,
                payload = """{"refresh_token":"{refreshToken}","grant_type":"refresh_token","n":2}""",
            ),
        )

        assertEquals("new", manager.refresh(client, "old"))

        val request = takeRequest()
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
        assertEquals("refresh_token=r%201%26x&grant_type=refresh_token&n=2", request.body.readUtf8())
        assertEquals("new", storedAuth.accessToken)
        assertEquals("r 1&x", storedAuth.refreshToken)
        assertEquals(clock.now() + 3_600_000, storedAuth.expires)
    }

    @Test
    fun `refreshHeaders may override the content type`() = runTest {
        server.enqueue(MockResponse().setBody("""{"accessToken":"new"}"""))
        val manager = manager(auth(headers = mapOf("content-type" to "application/vnd.api+json")))

        manager.refresh(client, "old")

        val request = takeRequest()
        assertEquals("application/vnd.api+json", request.getHeader("Content-Type"))
        assertEquals(1, request.headers.values("Content-Type").size)
        assertEquals(-1L, storedAuth.expires)
    }

    @Test
    fun `failed refresh keeps the old tokens and emits a failed authorization event`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val manager = manager(auth(expires = 123))

        assertNull(manager.refresh(client, "old"))

        assertEquals(auth(expires = 123), storedAuth)
        val event = events.ofType<TrackingEvent.Authorization>().single()
        assertFalse(event.success)
        assertEquals(500, event.status)
        assertEquals("HTTP 500", event.error)
        assertEquals("boom", JSONObject(event.responseJson!!).getString("error"))
    }

    @Test
    fun `a 2xx response without an access token is a failure`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token_type":"bearer"}"""))
        val manager = manager(auth())

        assertNull(manager.refresh(client, "old"))

        assertEquals("old", storedAuth.accessToken)
        val event = events.ofType<TrackingEvent.Authorization>().single()
        assertFalse(event.success)
        assertEquals(200, event.status)
    }

    @Test
    fun `network error emits status 0`() = runTest {
        val deadUrl = server.url("/refresh").toString()
        server.shutdown()
        val manager = manager(auth(refreshUrl = deadUrl))

        assertNull(manager.refresh(client, "old"))

        val event = events.ofType<TrackingEvent.Authorization>().single()
        assertFalse(event.success)
        assertEquals(0, event.status)
        assertTrue(event.error!!.isNotEmpty())
    }

    @Test
    fun `invalid refreshUrl fails without a request`() = runTest {
        val manager = manager(auth(refreshUrl = "not a url"))

        assertNull(manager.refresh(client, "old"))

        assertEquals(0, server.requestCount)
        assertFalse(events.ofType<TrackingEvent.Authorization>().single().success)
    }

    @Test
    fun `concurrent refreshes share one request`() = runTest {
        server.enqueue(MockResponse().setBody("""{"accessToken":"new","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("""{"accessToken":"newer","expires_in":3600}"""))
        val manager = manager(auth())

        val a = async { manager.refresh(client, "old") }
        val b = async { manager.refresh(client, "old") }

        assertEquals("new", a.await())
        assertEquals("new", b.await())
        assertEquals(1, server.requestCount)
        assertEquals(1, events.ofType<TrackingEvent.Authorization>().size)
    }

    @Test
    fun `tokenForRequest uses a valid token without refreshing`() = runTest {
        val manager = manager(auth(expires = clock.now() + 61_000))

        assertEquals(AuthorizationManager.Token("old", refreshAttempted = false), manager.tokenForRequest(client))

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tokenForRequest refreshes within 60 s of expiry`() = runTest {
        server.enqueue(MockResponse().setBody("""{"accessToken":"new","expires_in":3600}"""))
        val manager = manager(auth(expires = clock.now() + 60_000))

        assertEquals(AuthorizationManager.Token("new", refreshAttempted = true), manager.tokenForRequest(client))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `tokenForRequest fetches a missing token and falls back to the old one on failure`() = runTest {
        server.enqueue(MockResponse().setBody("""{"accessToken":"first"}"""))
        val missing = manager(auth(accessToken = null))
        assertEquals(AuthorizationManager.Token("first", refreshAttempted = true), missing.tokenForRequest(client))

        server.enqueue(MockResponse().setResponseCode(503))
        val expired = manager(auth(expires = clock.now() - 1))
        assertEquals(AuthorizationManager.Token("old", refreshAttempted = true), expired.tokenForRequest(client))
    }

    @Test
    fun `a token refreshed with a lifetime inside the margin is reused until it really expires`() = runTest {
        server.enqueue(MockResponse().setBody("""{"accessToken":"short","expires_in":30}"""))
        server.enqueue(MockResponse().setBody("""{"accessToken":"next","expires_in":3600}"""))
        val manager = manager(auth(expires = clock.now() + 10_000))

        assertEquals(AuthorizationManager.Token("short", refreshAttempted = true), manager.tokenForRequest(client))
        assertEquals(AuthorizationManager.Token("short", refreshAttempted = false), manager.tokenForRequest(client))
        assertEquals(1, server.requestCount)

        clock.advance(30_000)
        assertEquals(AuthorizationManager.Token("next", refreshAttempted = true), manager.tokenForRequest(client))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `without refreshUrl the configured token is used as is`() = runTest {
        val manager = manager(auth(refreshUrl = null, expires = clock.now() - 1))

        assertEquals(AuthorizationManager.Token("old", refreshAttempted = false), manager.tokenForRequest(client))
        assertNull(manager.refresh(client, "old"))
        assertEquals(0, server.requestCount)
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `no authorization config means no token`() = runTest {
        val manager = manager(null)

        assertEquals(AuthorizationManager.Token(null, refreshAttempted = false), manager.tokenForRequest(client))
        assertFalse(manager.appliesTo(HttpConfig()))
    }

    @Test
    fun `an explicit Authorization header disables the bearer token`() = runTest {
        val manager = manager(auth())

        assertTrue(manager.appliesTo(HttpConfig(authorization = auth())))
        val explicit = HttpConfig(authorization = auth(), headers = mapOf("authorization" to "Basic x"))
        assertFalse(manager.appliesTo(explicit))
    }

    @Test
    fun `refresh does not resurrect an authorization config removed while in flight`() = runTest {
        val manager = manager(auth())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                configStore.update { it.copy(http = it.http.copy(authorization = null)) }
                return MockResponse().setBody("""{"accessToken":"new"}""")
            }
        }

        assertEquals("new", manager.refresh(client, "old"))

        assertNull(configStore.config.value.http.authorization)
        assertTrue(events.ofType<TrackingEvent.Authorization>().single().success)
    }

    @Test
    fun `parseTokens reads every expiry format`() {
        val now = clock.now()
        fun expires(json: String) = AuthorizationManager.parseTokens(JSONObject(json), now)?.expires

        assertEquals(1_790_421_330_000L, expires("""{"accessToken":"a","expires":1790421330}"""))
        assertEquals(1_790_421_330_123L, expires("""{"accessToken":"a","expires":1790421330123}"""))
        assertEquals(1_790_421_330_000L, expires("""{"accessToken":"a","expires_at":"1790421330"}"""))
        assertEquals(1_790_421_330_000L, expires("""{"accessToken":"a","expires_at":"2026-09-26T11:15:30.000Z"}"""))
        assertEquals(now + 90_000, expires("""{"access_token":"a","expires_in":90}"""))
        assertEquals(-1L, expires("""{"accessToken":"a"}"""))
        assertEquals(-1L, expires("""{"accessToken":"a","expires":0,"expires_in":-5}"""))
        assertNull(AuthorizationManager.parseTokens(JSONObject("""{"accessToken":" "}"""), now))
        assertNull(AuthorizationManager.parseTokens(null, now))
        assertEquals(
            AuthorizationManager.Tokens("a", "b", -1),
            AuthorizationManager.parseTokens(JSONObject("""{"access_token":"a","refresh_token":"b"}"""), now),
        )
    }

    @Test
    fun `refreshBody substitutes the refresh token in string values only`() {
        val (type, body) = AuthorizationManager.refreshBody(
            auth(payload = """{"t":"Bearer {refreshToken}","n":1,"b":true}""", refreshToken = "xyz"),
        )

        assertEquals(HttpSupport.JSON_CONTENT_TYPE, type)
        assertJsonEquals("""{"t":"Bearer xyz","n":1,"b":true}""", JSONObject(body))
    }
}
