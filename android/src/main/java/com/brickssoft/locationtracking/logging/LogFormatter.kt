package com.brickssoft.locationtracking.logging

import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.LogLevel

/**
 * Line format and file naming of the log files.
 *
 * An entry is one header line, `<ISO-8601 ms UTC> <LEVEL padded to 7> <tag>: <first message line>`, followed by the
 * remaining message lines and the stack trace lines, each indented by [INDENT]. Only header lines start with a digit,
 * so an entry is its header plus every following line up to the next header.
 *
 * Daily files are named `lt-YYYY-MM-DD.log` after the UTC date of the entries they hold.
 */
internal object LogFormatter {
    /** Prefix of every line of an entry after its header line. */
    const val INDENT = "    "

    const val DAY_MS = 86_400_000L

    /** Width of the level column: the length of `VERBOSE`. */
    private const val LEVEL_WIDTH = 7

    /** Length of `yyyy-MM-ddTHH:mm:ss.SSSZ`. */
    private const val TIMESTAMP_LENGTH = 24

    private const val FILE_PREFIX = "lt-"
    private const val FILE_SUFFIX = ".log"
    private const val DATE_LENGTH = 10

    private val levels = LogLevel.entries.filter { it != LogLevel.OFF }

    /** Level and time of a header line. */
    data class Header(val timeMs: Long, val level: LogLevel)

    /** Formats one entry. The result always ends with `\n`. */
    fun format(timeMs: Long, level: LogLevel, tag: String, message: String, error: Throwable?): String =
        formatEntry(timeMs, level, tag, message, error?.let { stackTrace(it) })

    /** Like [format], with the stack trace already rendered by [stackTrace]. */
    fun formatEntry(timeMs: Long, level: LogLevel, tag: String, message: String, stackTrace: String?): String {
        val sb = StringBuilder(64 + tag.length + message.length + (stackTrace?.length ?: 0))
        sb.append(Iso8601.format(timeMs)).append(' ')
            .append(level.name.padEnd(LEVEL_WIDTH)).append(' ')
            .append(singleLine(tag)).append(": ")
        val lines = message.trimEnd('\r', '\n').lines()
        sb.append(lines.first()).append('\n')
        for (i in 1 until lines.size) sb.append(INDENT).append(lines[i]).append('\n')
        stackTrace?.lines()?.forEach { line ->
            if (line.isNotBlank()) sb.append(INDENT).append(line.trimEnd()).append('\n')
        }
        return sb.toString()
    }

    /** The stack trace text of [error], causes included; never throws, even if the throwable's own methods do. */
    fun stackTrace(error: Throwable): String = try {
        error.stackTraceToString()
    } catch (_: Throwable) {
        error.javaClass.name
    }

    /** Parses a header line, or returns null for continuation lines and garbage. */
    fun parseHeader(line: String): Header? {
        if (line.length < TIMESTAMP_LENGTH + 2 || line[TIMESTAMP_LENGTH] != ' ') return null
        val time = parseTimestamp(line) ?: return null
        val levelStart = TIMESTAMP_LENGTH + 1
        val levelEnd = line.indexOf(' ', levelStart).let { if (it < 0) line.length else it }
        val length = levelEnd - levelStart
        val level = levels.firstOrNull { it.name.length == length && line.startsWith(it.name, levelStart) }
            ?: return null
        return Header(time, level)
    }

    /**
     * Parses `yyyy-MM-ddTHH:mm:ss.SSSZ` at the start of [text] to epoch ms, or returns null.
     * Much faster than `SimpleDateFormat`, which matters when reading days of logs.
     */
    fun parseTimestamp(text: CharSequence): Long? {
        if (text.length < TIMESTAMP_LENGTH) return null
        if (text[4] != '-' || text[7] != '-' || text[10] != 'T' || text[13] != ':' || text[16] != ':' ||
            text[19] != '.' || text[23] != 'Z'
        ) {
            return null
        }
        val day = parseDate(text, 0) ?: return null
        val hour = digits(text, 11, 2)
        val minute = digits(text, 14, 2)
        val second = digits(text, 17, 2)
        val millis = digits(text, 20, 3)
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59 || millis < 0) return null
        return day * DAY_MS + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
    }

    /** UTC day number (days since 1970-01-01) of [timeMs]. */
    fun epochDay(timeMs: Long): Long = Math.floorDiv(timeMs, DAY_MS)

    /** `lt-YYYY-MM-DD.log` for the UTC date of [timeMs]. */
    fun fileName(timeMs: Long): String = FILE_PREFIX + Iso8601.format(timeMs).substring(0, DATE_LENGTH) + FILE_SUFFIX

    /** UTC day number of a daily log file name, or null if [name] is not one. */
    fun epochDayOfFileName(name: String): Long? {
        if (name.length != FILE_PREFIX.length + DATE_LENGTH + FILE_SUFFIX.length) return null
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val start = FILE_PREFIX.length
        if (name[start + 4] != '-' || name[start + 7] != '-') return null
        return parseDate(name, start)
    }

    /** Parses `yyyy-MM-dd` at [start] (separators already checked) to a UTC day number. */
    private fun parseDate(text: CharSequence, start: Int): Long? {
        val year = digits(text, start, 4)
        val month = digits(text, start + 5, 2)
        val day = digits(text, start + 8, 2)
        if (year < 0 || month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
        return daysFromCivil(year, month, day)
    }

    /** Non-negative integer of [count] ASCII digits at [start], or -1. */
    private fun digits(text: CharSequence, start: Int, count: Int): Int {
        var value = 0
        for (i in start until start + count) {
            val c = text[i]
            if (c !in '0'..'9') return -1
            value = value * 10 + (c - '0')
        }
        return value
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's `days_from_civil`). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val monthIndex = (month + 9) % 12
        val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    private fun singleLine(text: String): String =
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) text else text.replace('\r', ' ').replace('\n', ' ')
}
