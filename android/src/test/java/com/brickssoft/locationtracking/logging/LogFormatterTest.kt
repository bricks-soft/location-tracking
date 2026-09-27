package com.brickssoft.locationtracking.logging

import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class LogFormatterTest {
    private val t = Iso8601.parse("2026-09-26T10:15:30.456Z")!!

    @Test
    fun `formats a header with padded level and tag`() {
        assertEquals(
            "2026-09-26T10:15:30.456Z INFO    Tag: hello\n",
            LogFormatter.format(t, LogLevel.INFO, "Tag", "hello", null),
        )
        assertEquals(
            "2026-09-26T10:15:30.456Z VERBOSE T: x\n",
            LogFormatter.format(t, LogLevel.VERBOSE, "T", "x", null),
        )
        assertEquals("2026-09-26T10:15:30.456Z WARN    T: \n", LogFormatter.format(t, LogLevel.WARN, "T", "", null))
    }

    @Test
    fun `indents continuation lines of multi-line messages and flattens the tag`() {
        val text = LogFormatter.format(t, LogLevel.DEBUG, "a\nb", "one\r\ntwo\rthree\nfour\n\n", null)

        assertEquals(
            "2026-09-26T10:15:30.456Z DEBUG   a b: one\n    two\n    three\n    four\n",
            text,
        )
    }

    @Test
    fun `appends the indented stack trace including causes`() {
        val error = IOException("boom", IllegalStateException("root cause"))

        val lines = LogFormatter.format(t, LogLevel.ERROR, "Tag", "failed", error).trimEnd('\n').lines()

        assertEquals("2026-09-26T10:15:30.456Z ERROR   Tag: failed", lines[0])
        assertEquals("    java.io.IOException: boom", lines[1])
        assertTrue(lines.drop(1).all { it.startsWith(LogFormatter.INDENT) })
        assertTrue(lines.any { it.startsWith("    \tat ") })
        assertTrue(lines.any { it == "    Caused by: java.lang.IllegalStateException: root cause" })
        assertTrue(lines.drop(1).all { LogFormatter.parseHeader(it) == null })
    }

    @Test
    fun `a throwable whose toString throws still formats`() {
        val evil = object : RuntimeException("x") {
            override fun toString(): String = throw IllegalStateException("nope")
        }

        val text = LogFormatter.format(t, LogLevel.ERROR, "Tag", "m", evil)

        assertTrue(text.startsWith("2026-09-26T10:15:30.456Z ERROR   Tag: m\n    "))
    }

    @Test
    fun `parses headers it formatted`() {
        for (level in LogLevel.entries.filter { it != LogLevel.OFF }) {
            val line = LogFormatter.format(t, level, "Tag", "msg", null).trimEnd('\n')
            assertEquals(LogFormatter.Header(t, level), LogFormatter.parseHeader(line))
        }
    }

    @Test
    fun `rejects lines that are not headers`() {
        listOf(
            "",
            "    at foo",
            "2026-09-26T10:15:30.456Z",
            "2026-09-26T10:15:30.456Z OFF     Tag: x",
            "2026-09-26T10:15:30.456Z INFOX   Tag: x",
            "2026-09-26T10:15:30.456Z  INFO   Tag: x",
            "2026-09-26 10:15:30.456Z INFO    Tag: x",
            "2026-13-26T10:15:30.456Z INFO    Tag: x",
            "2026-02-30T10:15:30.456Z INFO    Tag: x",
            "2026-09-26T24:15:30.456Z INFO    Tag: x",
            "2026-09-26T10:15:3a.456Z INFO    Tag: x",
        ).forEach { assertNull(it, LogFormatter.parseHeader(it)) }
    }

    @Test
    fun `fast timestamp parser agrees with Iso8601`() {
        val random = Random(7)
        repeat(2_000) {
            val ms = random.nextLong(0L, 4_102_444_800_000L) // 1970..2100
            val text = Iso8601.format(ms)
            assertEquals(text, ms, LogFormatter.parseTimestamp(text))
        }
        assertEquals(Iso8601.parse("2024-02-29T23:59:59.999Z"), LogFormatter.parseTimestamp("2024-02-29T23:59:59.999Z"))
        assertEquals(0L, LogFormatter.parseTimestamp("1970-01-01T00:00:00.000Z"))
    }

    @Test
    fun `daily file names use the UTC date`() {
        assertEquals("lt-2026-09-26.log", LogFormatter.fileName(t))
        assertEquals("lt-2026-09-26.log", LogFormatter.fileName(Iso8601.parse("2026-09-26T23:59:59.999Z")!!))
        assertEquals("lt-2026-09-27.log", LogFormatter.fileName(Iso8601.parse("2026-09-27T00:00:00.000Z")!!))
    }

    @Test
    fun `day numbers of file names and times agree`() {
        val day = LogFormatter.epochDay(t)

        assertEquals(day, LogFormatter.epochDayOfFileName("lt-2026-09-26.log"))
        assertEquals(day - 1, LogFormatter.epochDayOfFileName("lt-2026-09-25.log"))
        assertEquals(0L, LogFormatter.epochDayOfFileName("lt-1970-01-01.log"))
        assertEquals(-1L, LogFormatter.epochDay(-1L))
        listOf("lt-2026-09-26.txt", "xx-2026-09-26.log", "lt-2026-9-26.log", "lt-2026-02-30.log", "lt-garbage.log")
            .forEach { assertNull(it, LogFormatter.epochDayOfFileName(it)) }
    }
}
