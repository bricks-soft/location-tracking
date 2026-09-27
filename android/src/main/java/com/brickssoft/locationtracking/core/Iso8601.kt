package com.brickssoft.locationtracking.core

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 formatting and parsing for API 24+ (no `java.time`).
 * The output format is always `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC.
 */
object Iso8601 {
    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    private val formatter = threadLocalFormat(PATTERN)

    /** Accepted input patterns, tried in order (UTC 'Z' with/without millis, numeric offsets). */
    private val parsers = listOf(
        threadLocalFormat(PATTERN),
        threadLocalFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        threadLocalFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
        threadLocalFormat("yyyy-MM-dd'T'HH:mm:ssXXX"),
    )

    private fun threadLocalFormat(pattern: String): ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
        }

    /** Formats epoch ms as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. */
    fun format(epochMs: Long): String = formatter.get()!!.format(Date(epochMs))

    /** [format], or null for null input. */
    fun formatOrNull(epochMs: Long?): String? = epochMs?.let { format(it) }

    /** Parses an ISO-8601 timestamp to epoch ms, or returns null if it is not valid. */
    fun parse(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val trimmed = normalizeFraction(text.trim())
        for (parser in parsers) {
            val position = ParsePosition(0)
            val date = parser.get()!!.parse(trimmed, position)
            if (date != null && position.index == trimmed.length) return date.time
        }
        return null
    }

    /** Pads or truncates the fractional seconds to exactly 3 digits so SimpleDateFormat parses them as ms. */
    private fun normalizeFraction(text: String): String {
        val dot = text.indexOf('.', startIndex = 19)
        if (dot != 19) return text
        var end = dot + 1
        while (end < text.length && text[end].isDigit()) end++
        val digits = text.substring(dot + 1, end)
        if (digits.isEmpty()) return text
        val ms = digits.padEnd(3, '0').substring(0, 3)
        return text.substring(0, dot + 1) + ms + text.substring(end)
    }
}
