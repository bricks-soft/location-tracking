package com.brickssoft.premisemonitor

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** Uploads to the audit URL: `{device_id, entries}`, in order, retried after a failure. */
@RunWith(RobolectricTestRunner::class)
internal class AuditUploadTest : PremiseTestBase() {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        PremiseMonitorCore.install(null)
        server.shutdown()
    }

    private val auditUrl get() = server.url("/premise-audit").toString()

    @Test
    fun entriesAreUploadedInOrderWithAStableDeviceId() {
        repeat(10) { server.enqueue(MockResponse().setBody("""{"ok":true}""")) }
        startMonitoring(auditUrl = auditUrl)
        repeat(5) { i -> listener.onRecord(app, wireRecord("location").put("uuid", "r$i")) }
        listener.onEvent(app, "heartbeat", JSONObject())
        val expected = entries().map { it.getString("id") }
        assertEquals(7, expected.size)

        val uploaded = ArrayList<String>()
        val deviceIds = HashSet<String>()
        while (uploaded.size < expected.size) {
            val request = takeRequest() ?: break
            assertEquals("POST", request.method)
            assertEquals("/premise-audit", request.path)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            val body = JSONObject(request.body.readUtf8())
            deviceIds.add(body.getString("device_id"))
            val batch = body.getJSONArray("entries")
            for (i in 0 until batch.length()) uploaded.add(batch.getJSONObject(i).getString("id"))
        }
        assertEquals(expected, uploaded)
        assertEquals(setOf(PremiseState(app).deviceId), deviceIds)
        waitForPending(0)
        assertEquals(0, status().getInt("pendingUploads"))
    }

    @Test
    fun aFailedUploadStaysPendingAndIsRetriedAfterTheDelay() {
        PremiseMonitorCore.install(newCore(retryDelayMs = 500).also { core = it })
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("{}"))
        // Entries are written while no URL is set; setting the URL (same premise) writes one entry and uploads once.
        startMonitoring()
        repeat(3) { i -> listener.onRecord(app, wireRecord("location").put("uuid", "r$i")) }
        core.awaitIdle()
        startMonitoring(auditUrl = auditUrl)
        val expected = entries().map { it.getString("id") }

        val first = takeRequest()!!
        val firstIds = ids(first)
        assertEquals(expected, firstIds)
        val failedAt = System.nanoTime()
        val second = takeRequest()!!
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - failedAt)
        assertEquals("the retry sends the same entries in the same order", firstIds, ids(second))
        assertTrue("retried after the delay, not at once ($waitedMs ms)", waitedMs >= 300)
        waitForPending(0)
    }

    @Test
    fun aFailedUploadIsRetriedWithTheNextEntry() {
        PremiseMonitorCore.install(newCore(retryDelayMs = 60_000).also { core = it })
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("{}"))
        startMonitoring(auditUrl = auditUrl)
        val first = takeRequest()!!
        core.uploader.awaitIdle()
        assertEquals(1, status().getInt("pendingUploads"))

        listener.onRecord(app, wireRecord("location").put("uuid", "next"))
        val second = takeRequest()!!
        assertEquals(ids(first) + entries().last().getString("id"), ids(second))
        waitForPending(0)
    }

    @Test
    fun anUnreachableEndpointKeepsEntriesPending() {
        val url = auditUrl
        server.shutdown()
        PremiseMonitorCore.install(newCore(retryDelayMs = 60_000).also { core = it })
        startMonitoring(auditUrl = url)
        listener.onRecord(app, wireRecord("location"))
        core.awaitIdle()
        core.uploader.awaitIdle()
        core.uploader.awaitIdle()
        assertEquals(2, status().getInt("pendingUploads"))
        server = MockWebServer() // for @After
    }

    @Test
    fun withoutAnAuditUrlNothingIsSent() {
        startMonitoring()
        listener.onRecord(app, wireRecord("location"))
        core.awaitIdle()
        core.uploader.awaitIdle()
        assertEquals(0, server.requestCount)
        val status = status()
        assertTrue(status.isNull("auditUrl"))
        assertEquals(2, status.getInt("pendingUploads"))
        assertEquals(0, core.uploader.attempts)
    }

    private fun takeRequest(): RecordedRequest? = server.takeRequest(10, TimeUnit.SECONDS)

    private fun ids(request: RecordedRequest): List<String> {
        val entries = JSONObject(request.body.clone().readUtf8()).getJSONArray("entries")
        return (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }
    }

    private fun waitForPending(expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (status().getInt("pendingUploads") != expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(expected, status().getInt("pendingUploads"))
    }
}
