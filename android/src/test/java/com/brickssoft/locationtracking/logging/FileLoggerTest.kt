package com.brickssoft.locationtracking.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.BuildConfig
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.HttpResult
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.testDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FileLoggerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock() // 2026-09-26T10:15:30.456Z
    private var server: MockWebServer? = null
    private val logDir = File(context.filesDir, Constants.LOG_DIR)
    private val cacheLogDir = File(context.cacheDir, Constants.LOG_DIR)

    @After
    fun tearDown() {
        Logger.sink = null
        server?.shutdown()
        logDir.deleteRecursively()
        cacheLogDir.deleteRecursively()
    }

    private fun TestScope.newLogger(maxPending: Int = FileLogger.DEFAULT_MAX_PENDING) =
        FileLogger(context, clock, testDispatchers(testScheduler), lazy { OkHttpClient() }, maxPending)

    private fun at(iso: String) {
        clock.nowMs = Iso8601.parse(iso)!!
    }

    private fun logFile(day: String) = File(logDir, "lt-$day.log")

    /** Splits [log] into entries (header line plus its continuation lines). */
    private fun entries(log: String): List<String> {
        val result = mutableListOf<String>()
        for (line in log.lines().filter { it.isNotEmpty() }) {
            if (LogFormatter.parseHeader(line) != null) result += line else result[result.size - 1] += "\n$line"
        }
        return result
    }

    private fun messages(log: String): List<String> = entries(log).map { it.lines().first().substringAfter(": ") }

    private fun startServer(vararg responses: MockResponse): MockWebServer =
        MockWebServer().also { s ->
            server = s
            responses.forEach { s.enqueue(it) }
            s.start()
        }

    private fun gunzip(bytes: ByteArray): String = GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()

    @Test
    fun `write is asynchronous and lands formatted in the daily UTC file`() = runTest {
        val logger = newLogger()

        logger.write(LogLevel.INFO, "Tag", "hello", null)

        assertFalse(logFile("2026-09-26").exists())
        advanceUntilIdle()
        assertEquals("2026-09-26T10:15:30.456Z INFO    Tag: hello\n", logFile("2026-09-26").readText())
    }

    @Test
    fun `entries after UTC midnight go to the next day's file`() = runTest {
        val logger = newLogger()
        at("2026-09-26T23:59:59.999Z")
        logger.write(LogLevel.INFO, "Tag", "before midnight", null)
        clock.advance(1)
        logger.write(LogLevel.INFO, "Tag", "after midnight", null)

        logger.flush()

        assertEquals("2026-09-26T23:59:59.999Z INFO    Tag: before midnight\n", logFile("2026-09-26").readText())
        assertEquals("2026-09-27T00:00:00.000Z INFO    Tag: after midnight\n", logFile("2026-09-27").readText())
    }

    @Test
    fun `level filter defaults to info and OFF writes nothing`() = runTest {
        val logger = newLogger()
        logger.write(LogLevel.DEBUG, "Tag", "debug before configure", null)
        logger.write(LogLevel.INFO, "Tag", "info before configure", null)

        logger.configure(LogLevel.WARN, 3)
        LogLevel.entries.forEach { logger.write(it, "Tag", "at ${it.name}", null) }
        logger.configure(LogLevel.OFF, 3)
        logger.write(LogLevel.ERROR, "Tag", "error while off", null)
        logger.configure(LogLevel.VERBOSE, 3)
        logger.write(LogLevel.VERBOSE, "Tag", "verbose", null)

        assertEquals(
            listOf("info before configure", "at ERROR", "at WARN", "verbose"),
            messages(logger.read(LogQuery())),
        )
    }

    @Test
    fun `configure purges daily files older than maxDays and keeps other files`() = runTest {
        logDir.mkdirs()
        val days = listOf("2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25", "2026-09-26")
        days.forEach { logFile(it).writeText("x\n") }
        File(logDir, "notes.txt").writeText("keep")
        File(logDir, "lt-garbage.log").writeText("keep")
        val logger = newLogger()

        logger.configure(LogLevel.INFO, 2)
        advanceUntilIdle()

        assertEquals(
            setOf("lt-2026-09-24.log", "lt-2026-09-25.log", "lt-2026-09-26.log", "notes.txt", "lt-garbage.log"),
            logDir.list()!!.toSet(),
        )
    }

    @Test
    fun `day rollover purges files that became too old`() = runTest {
        val logger = newLogger()
        logger.configure(LogLevel.INFO, 1)
        advanceUntilIdle()
        logDir.mkdirs()
        logFile("2026-09-25").writeText("2026-09-25T12:00:00.000Z INFO    Old: yesterday\n")
        logger.write(LogLevel.INFO, "Tag", "today", null)
        logger.flush()
        assertTrue(logFile("2026-09-25").exists())

        at("2026-09-27T00:00:01.000Z")
        logger.write(LogLevel.INFO, "Tag", "tomorrow", null)
        logger.flush()

        assertFalse(logFile("2026-09-25").exists())
        assertEquals(listOf("today", "tomorrow"), messages(logger.read(LogQuery())))
    }

    @Test
    fun `read filters by time and level, orders, limits, and keeps stack traces with their entry`() = runTest {
        val logger = newLogger()
        logger.configure(LogLevel.VERBOSE, 3)
        val t0 = Iso8601.parse("2026-09-26T10:00:00.000Z")!!
        clock.nowMs = t0
        logger.write(LogLevel.VERBOSE, "Tag", "v1", null)
        clock.advance(1_000)
        logger.write(LogLevel.DEBUG, "Tag", "d1", null)
        clock.advance(1_000)
        logger.write(LogLevel.INFO, "Tag", "i1\nsecond line", null)
        clock.advance(1_000)
        logger.write(LogLevel.WARN, "Tag", "w1", null)
        clock.advance(1_000)
        logger.write(LogLevel.ERROR, "Tag", "e1", IOException("boom", IllegalStateException("root")))
        clock.advance(1_000)
        logger.write(LogLevel.INFO, "Tag", "i2", null)

        val all = logger.read(LogQuery())
        assertEquals(listOf("v1", "d1", "i1", "w1", "e1", "i2"), messages(all))
        assertEquals(all, logFile("2026-09-26").readText())
        val error = entries(all)[4]
        assertTrue(error.contains("\n    java.io.IOException: boom\n"))
        assertTrue(error.contains("\n    Caused by: java.lang.IllegalStateException: root"))
        assertEquals("i1\n    second line", entries(all)[2].substringAfter("Tag: "))

        assertEquals(listOf("w1", "e1"), messages(logger.read(LogQuery(level = LogLevel.WARN))))
        assertEquals(error, entries(logger.read(LogQuery(level = LogLevel.ERROR))).single())
        assertEquals("", logger.read(LogQuery(level = LogLevel.OFF)))
        assertEquals(
            listOf("i1", "w1", "e1"),
            messages(logger.read(LogQuery(start = t0 + 2_000, end = t0 + 4_000))),
        )
        assertEquals(listOf("e1", "i2"), messages(logger.read(LogQuery(start = t0 + 3_001))))
        assertEquals(listOf("v1"), messages(logger.read(LogQuery(end = t0 + 999))))
        assertEquals(listOf("v1", "d1"), messages(logger.read(LogQuery(limit = 2))))
        assertEquals(listOf("i2", "e1"), messages(logger.read(LogQuery(limit = 2, ascending = false))))
        assertEquals(listOf("w1"), messages(logger.read(LogQuery(level = LogLevel.WARN, limit = 1))))

        val descending = logger.read(LogQuery(ascending = false))
        assertEquals(entries(all).reversed(), entries(descending))
        assertTrue(descending.contains("Tag: e1\n    java.io.IOException: boom\n"))
        assertTrue(descending.endsWith("\n"))
    }

    @Test
    fun `read spans daily files in chronological order and skips files outside the range`() = runTest {
        val logger = newLogger()
        at("2026-09-25T22:00:00.000Z")
        logger.write(LogLevel.INFO, "Tag", "day 1", null)
        at("2026-09-26T08:00:00.000Z")
        logger.write(LogLevel.INFO, "Tag", "day 2", null)
        logFile("2026-09-24").also { it.parentFile!!.mkdirs() }
            .writeText("orphan line without header\n2026-09-24T01:00:00.000Z WARN    Tag: day 0\n")

        assertEquals(listOf("day 0", "day 1", "day 2"), messages(logger.read(LogQuery())))
        assertEquals(listOf("day 2", "day 1", "day 0"), messages(logger.read(LogQuery(ascending = false))))
        assertEquals(
            listOf("day 1", "day 2"),
            messages(logger.read(LogQuery(start = Iso8601.parse("2026-09-25T00:00:00.000Z")))),
        )
        assertEquals(
            listOf("day 0"),
            messages(logger.read(LogQuery(end = Iso8601.parse("2026-09-25T21:59:59.999Z")))),
        )
        assertEquals(listOf("day 0", "day 1"), messages(logger.read(LogQuery(limit = 2))))
        assertEquals(listOf("day 2", "day 1"), messages(logger.read(LogQuery(limit = 2, ascending = false))))
        assertEquals(listOf("day 2"), messages(logger.read(LogQuery(limit = 1, ascending = false))))
        assertEquals(listOf("day 0"), messages(logger.read(LogQuery(level = LogLevel.WARN, ascending = false))))
    }

    @Test
    fun `a line cut short by a crash does not swallow the next entry`() = runTest {
        logDir.mkdirs()
        logFile("2026-09-26").writeText("2026-09-26T09:00:00.000Z INFO    Tag: cut sh")
        val logger = newLogger()

        logger.write(LogLevel.WARN, "Tag", "next", null)
        logger.write(LogLevel.WARN, "Tag", "after", null)

        assertEquals(listOf("cut sh", "next", "after"), messages(logger.read(LogQuery())))
        assertEquals(listOf("next", "after"), messages(logger.read(LogQuery(level = LogLevel.WARN))))
    }

    @Test
    fun `rollover purging resumes after a wrong clock is corrected`() = runTest {
        val logger = newLogger()
        logger.configure(LogLevel.INFO, 1)
        at("2027-01-01T00:00:00.000Z")
        logger.write(LogLevel.INFO, "Tag", "clock far ahead", null)
        at("2026-09-26T12:00:00.000Z")
        logger.write(LogLevel.INFO, "Tag", "clock fixed", null)
        logger.flush()
        logFile("2026-09-24").writeText("2026-09-24T12:00:00.000Z INFO    Old: too old\n")

        at("2026-09-27T00:00:01.000Z")
        logger.write(LogLevel.INFO, "Tag", "next day", null)
        logger.flush()

        assertFalse(logFile("2026-09-24").exists())
        assertTrue(logFile("2026-09-26").exists())
    }

    @Test
    fun `the stack trace is captured when the entry is written`() = runTest {
        val logger = newLogger()
        val error = IOException("boom")

        logger.write(LogLevel.ERROR, "Tag", "failed", error)
        error.addSuppressed(IllegalStateException("added later"))

        val log = logger.read(LogQuery())
        assertTrue(log.contains("    java.io.IOException: boom\n"))
        assertFalse(log.contains("added later"))
    }

    @Test
    fun `destroy drains pending writes and deletes all log files`() = runTest {
        val logger = newLogger()
        logger.write(LogLevel.INFO, "Tag", "one", null)
        logger.flush()
        at("2026-09-27T09:00:00.000Z")
        logger.write(LogLevel.INFO, "Tag", "two (still pending)", null)
        cacheLogDir.mkdirs()
        File(cacheLogDir, "upload-123.txt.gz").writeText("leftover")
        File(cacheLogDir, "location-tracking-log.txt.gz.tmp").writeText("leftover")

        logger.destroy()

        assertEquals(emptyList<String>(), logDir.list().orEmpty().toList())
        assertEquals(emptyList<String>(), cacheLogDir.list().orEmpty().toList())
        assertEquals("", logger.read(LogQuery()))
        logger.write(LogLevel.INFO, "Tag", "after destroy", null)
        assertEquals(listOf("after destroy"), messages(logger.read(LogQuery())))
    }

    @Test
    fun `upload posts the gzipped log and params as multipart form data`() = runTest {
        val s = startServer(MockResponse().setResponseCode(201).setBody("stored"))
        val logger = newLogger()
        logger.write(LogLevel.INFO, "Tag", "upload me", null)
        logger.write(LogLevel.ERROR, "Tag", "with trace", IOException("boom"))

        val result = logger.upload(
            s.url("/logs").toString(),
            mapOf("Authorization" to "Bearer token", "X-Device" to "d-1"),
            """{"device_id":"abc","count":3,"nested":{"a":true},"nothing":null}""",
        )

        assertEquals(HttpResult(success = true, status = 201, responseText = "stored", uuids = emptyList()), result)
        val request = s.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/logs", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("d-1", request.getHeader("X-Device"))
        val contentType = request.getHeader("Content-Type")!!.toMediaType()
        assertEquals("multipart/form-data", "${contentType.type}/${contentType.subtype}")
        val fields = mutableMapOf<String, String>()
        var log: String? = null
        MultipartReader(request.body, contentType.parameter("boundary")!!).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val disposition = part.headers["Content-Disposition"]!!
                val name = Regex("name=\"([^\"]*)\"").find(disposition)!!.groupValues[1]
                if (name == "log") {
                    assertTrue(disposition.contains("filename=\"location-tracking-log.txt.gz\""))
                    assertEquals("application/gzip", part.headers["Content-Type"])
                    log = gunzip(part.body.readByteArray())
                } else {
                    fields[name] = part.body.readUtf8()
                }
            }
        }
        assertEquals(mapOf("device_id" to "abc", "count" to "3", "nested" to "{\"a\":true}", "nothing" to ""), fields)
        assertNotNull(log)
        assertTrue(log!!.startsWith("2026-09-26T10:15:30.456Z INFO    Tag: upload me\n"))
        assertTrue(log!!.contains("ERROR   Tag: with trace\n    java.io.IOException: boom\n"))
        assertEquals(logFile("2026-09-26").readText(), log)
        assertEquals(emptyList<String>(), cacheLogDir.list().orEmpty().toList())
    }

    @Test
    fun `upload reports non-2xx responses`() = runTest {
        val s = startServer(MockResponse().setResponseCode(500).setBody("nope"))
        val logger = newLogger()

        val result = logger.upload(s.url("/").toString(), emptyMap(), null)

        assertEquals(HttpResult(success = false, status = 500, responseText = "nope", uuids = emptyList()), result)
        assertEquals(1, s.requestCount)
    }

    @Test
    fun `upload maps network errors to status 0 and rejects bad urls and headers`() = runTest {
        val s = startServer()
        val url = s.url("/").toString()
        val logger = newLogger()
        logger.write(LogLevel.INFO, "Tag", "kept", null)

        val badUrl = runCatching { logger.upload("not a url", emptyMap(), null) }.exceptionOrNull()
        assertEquals(ErrorCode.INVALID_ARGUMENT, (badUrl as TrackingException).code)
        val badHeader = runCatching { logger.upload(url, mapOf("Bad Header" to "x"), null) }.exceptionOrNull()
        assertEquals(ErrorCode.INVALID_ARGUMENT, (badHeader as TrackingException).code)
        assertEquals(0, s.requestCount)

        s.shutdown()
        server = null
        val offline = logger.upload(url, emptyMap(), """{"a":1}""")

        assertFalse(offline.success)
        assertEquals(0, offline.status)
        assertTrue(offline.responseText.isNotEmpty())
        assertTrue(offline.uuids.isEmpty())
        assertEquals(emptyList<String>(), cacheLogDir.list().orEmpty().toList())
        assertTrue(logger.read(LogQuery()).contains("Tag: kept\n"))
    }

    @Test
    @Config(sdk = [29, 34, 35])
    fun `prepareEmail builds a chooser for ACTION_SEND with the gzipped log via the FileProvider`() = runTest {
        val logger = newLogger()
        logger.write(LogLevel.INFO, "Tag", "email me", null)

        val chooser = logger.prepareEmail("ops@example.com", "Field log")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/gzip", send.type)
        assertArrayEquals(arrayOf("ops@example.com"), send.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Field log", send.getStringExtra(Intent.EXTRA_SUBJECT))
        val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text, text.contains(Build.MODEL))
        assertTrue(text, text.contains("Plugin version: ${BuildConfig.PLUGIN_VERSION}"))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".locationtracking.logs", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        val file = File(cacheLogDir, "location-tracking-log.txt.gz")
        assertEquals(logFile("2026-09-26").readText(), gunzip(file.readBytes()))
        val viaProvider = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(gunzip(viaProvider).contains("INFO    Tag: email me"))

        val second = logger.prepareEmail("ops@example.com", null)
        val secondSend = IntentCompat.getParcelableExtra(second, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("Location tracking log", secondSend.getStringExtra(Intent.EXTRA_SUBJECT))

        logger.destroy()
        assertFalse(file.exists())
    }

    @Test
    fun `concurrent writers lose nothing below the cap`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val io = executor.asCoroutineDispatcher()
            val logger = FileLogger(context, clock, AppDispatchers(io, io, io), lazy { OkHttpClient() })
            val threads = 8
            val perThread = 500
            val start = CountDownLatch(1)
            val workers = (0 until threads).map { t ->
                thread {
                    start.await()
                    repeat(perThread) { i -> logger.write(LogLevel.INFO, "T$t", "m$i", null) }
                }
            }
            start.countDown()
            workers.forEach { it.join() }

            val log = runBlocking { logger.read(LogQuery()) }

            val lines = entries(log)
            assertEquals(threads * perThread, lines.size)
            for (t in 0 until threads) {
                val own = lines.filter { it.contains(" T$t: ") }.map { it.substringAfter(": ") }
                assertEquals((0 until perThread).map { "m$it" }, own)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `queue drops the oldest entries beyond the cap and records it`() = runTest {
        val logger = newLogger(maxPending = 10)

        repeat(15) { logger.write(LogLevel.INFO, "Tag", "m$it", null) }
        logger.flush()

        val log = logger.read(LogQuery())
        assertEquals(listOf("Dropped 5 log entries: write queue full") + (5 until 15).map { "m$it" }, messages(log))
        assertTrue(entries(log).first().contains(" WARN    LT.FileLogger: "))
    }

    @Test
    fun `write never throws and the Logger facade reaches the file`() = runTest {
        val broken = object : Clock {
            override fun now(): Long = throw IllegalStateException("clock broken")
            override fun elapsedRealtime(): Long = 0
            override fun bootCount(): Int = -1
        }
        FileLogger(context, broken, testDispatchers(testScheduler), lazy { OkHttpClient() })
            .write(LogLevel.ERROR, "Tag", "ignored", null)

        val logger = newLogger()
        Logger.sink = logger
        Logger.i("Tag", "via facade")
        Logger.d("Tag", "filtered out")

        assertEquals(listOf("via facade"), messages(logger.read(LogQuery())))
    }
}
