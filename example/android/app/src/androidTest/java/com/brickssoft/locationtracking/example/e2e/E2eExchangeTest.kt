package com.brickssoft.locationtracking.example.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brickssoft.locationtracking.example.e2e.E2eProtocol.Outcome
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The request lifecycle of the debug receiver without a broadcast: one line per request, one `finish()`, the
 * timeout, the result file and the order of the `afterLog` side effect.
 */
@RunWith(AndroidJUnit4::class)
class E2eExchangeTest {
    private lateinit var executor: ScheduledThreadPoolExecutor
    private lateinit var filesDir: File
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val finished = CountDownLatch(1)

    @Before
    fun setUp() {
        executor = ScheduledThreadPoolExecutor(1)
        filesDir = File(System.getProperty("java.io.tmpdir"), "e2e-exchange-test-" + System.nanoTime())
        assertTrue(filesDir.mkdirs())
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        filesDir.deleteRecursively()
    }

    private fun exchange(id: String, cmd: String) = E2eExchange(
        filesDir = filesDir,
        id = id,
        cmd = cmd,
        log = { events.add("log:$it") },
        finish = {
            events.add("finish")
            finished.countDown()
        },
        executor = executor,
    )

    /** Waits until the exchange finished and the executor ran everything queued before this call. */
    private fun awaitIdle() {
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        executor.submit { }.get(5, TimeUnit.SECONDS)
    }

    private fun lines(): List<JSONObject> = events.filter { it.startsWith("log:") }.map { JSONObject(it.removePrefix("log:")) }

    @Test
    fun onlyTheFirstOutcomeIsAnswered() {
        val ex = exchange("r1", "state")
        ex.complete(Outcome.Success(JSONObject().put("n", 1)))
        ex.complete(Outcome.Failure("INTERNAL", "second"))
        awaitIdle()
        assertEquals(listOf("finish"), events.filter { it == "finish" })
        val lines = lines()
        assertEquals(1, lines.size)
        assertTrue(lines[0].getBoolean("ok"))
        assertEquals(1, lines[0].getJSONObject("result").getInt("n"))
    }

    @Test
    fun timeoutAnswersTimeoutAndIgnoresTheLateResult() {
        val ex = exchange("r-timeout", "start")
        ex.armTimeout(timeoutMs = 50)
        awaitIdle()
        ex.complete(Outcome.Success(JSONObject()))
        executor.submit { }.get(5, TimeUnit.SECONDS)
        val lines = lines()
        assertEquals(1, lines.size)
        assertFalse(lines[0].getBoolean("ok"))
        assertEquals("TIMEOUT", lines[0].getString("code"))
        assertEquals(1, events.count { it == "finish" })
    }

    @Test
    fun resultBeforeTimeoutCancelsTheTimeout() {
        val ex = exchange("r-fast", "state")
        ex.armTimeout(timeoutMs = 200)
        ex.complete(Outcome.Success(JSONObject()))
        awaitIdle()
        Thread.sleep(400)
        executor.submit { }.get(5, TimeUnit.SECONDS)
        assertEquals(1, lines().size)
        assertTrue(lines()[0].getBoolean("ok"))
    }

    @Test
    fun largeResultIsWrittenToTheResultFile() {
        val ex = exchange("r-big", "sync")
        ex.complete(Outcome.Success(JSONObject().put("text", "x".repeat(5000))))
        awaitIdle()
        val line = lines().single()
        assertEquals("files/e2e/r-big.json", line.getString("resultFile"))
        val file = File(filesDir, "e2e/r-big.json")
        assertTrue(file.isFile)
        assertFalse(File(filesDir, "e2e/r-big.json.tmp").exists())
        val full = JSONObject(file.readText())
        assertEquals("r-big", full.getString("id"))
        assertEquals(5000, full.getJSONObject("result").getString("text").length)
    }

    @Test
    fun afterLogRunsAfterTheLineAndFinish() {
        val ex = exchange("r-block", "blockMainThread")
        val ran = CountDownLatch(1)
        ex.complete(
            Outcome.Success(JSONObject().put("blockedMs", 10)) {
                events.add("afterLog")
                ran.countDown()
            },
        )
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("log", "finish", "afterLog"), events.map { it.substringBefore(':') })
    }

    @Test
    fun failureDoesNotRunAfterLog() {
        val ex = exchange("r1", "state")
        ex.complete(Outcome.Failure("NOT_FOUND", "gone"))
        awaitIdle()
        assertEquals(listOf("log", "finish"), events.map { it.substringBefore(':') })
    }

    @Test
    fun aFailingLogStillFinishes() {
        val ex = E2eExchange(
            filesDir = filesDir,
            id = "r1",
            cmd = "state",
            log = { throw IllegalStateException("logcat unavailable") },
            finish = { finished.countDown() },
            executor = executor,
        )
        ex.complete(Outcome.Success(null))
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }
}
