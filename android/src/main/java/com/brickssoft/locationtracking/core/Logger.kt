package com.brickssoft.locationtracking.core

import android.util.Log

/** Log levels, ordered from least to most verbose. [wire] is the JS `LogLevel` value. */
enum class LogLevel {
    OFF,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    VERBOSE,
    ;

    val wire: String get() = name.lowercase()

    /** True if a message at [level] passes a filter set to this level. */
    fun allows(level: LogLevel): Boolean = level != OFF && this != OFF && level.ordinal <= ordinal

    companion object {
        fun fromWire(value: String?): LogLevel? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** Destination for log lines besides logcat (the file logger). */
interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, error: Throwable?)
}

/**
 * Global logger. Writes every message to logcat and to [sink] (set by `Components.bootstrap()`).
 * Never throws: failures of the sink or of logcat are swallowed.
 */
object Logger {
    @Volatile
    var sink: LogSink? = null

    fun e(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, msg, t)

    fun w(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN, tag, msg, t)

    fun i(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.INFO, tag, msg, t)

    fun d(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.DEBUG, tag, msg, t)

    fun v(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.VERBOSE, tag, msg, t)

    fun log(level: LogLevel, tag: String, msg: String, t: Throwable? = null) {
        if (level == LogLevel.OFF) return
        try {
            when (level) {
                LogLevel.ERROR -> Log.e(tag, msg, t)
                LogLevel.WARN -> Log.w(tag, msg, t)
                LogLevel.INFO -> Log.i(tag, msg, t)
                LogLevel.DEBUG -> Log.d(tag, msg, t)
                LogLevel.VERBOSE -> Log.v(tag, msg, t)
                LogLevel.OFF -> Unit
            }
        } catch (_: Throwable) {
            // logcat unavailable (plain JVM); ignore
        }
        try {
            sink?.write(level, tag, msg, t)
        } catch (_: Throwable) {
            // a broken sink must never break the caller
        }
    }
}
