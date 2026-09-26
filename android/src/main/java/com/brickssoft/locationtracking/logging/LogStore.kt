package com.brickssoft.locationtracking.logging

import android.content.Intent
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.LogSink
import com.brickssoft.locationtracking.model.HttpResult

/** JS `LogQuery`; times are epoch ms. */
data class LogQuery(
    val start: Long? = null,
    val end: Long? = null,
    val level: LogLevel? = null,
    val limit: Int? = null,
    val ascending: Boolean = true,
)

/** Persistent log (daily files in `location-tracking-logs`). Set as `Logger.sink` by Components. */
interface LogStore : LogSink {
    fun configure(level: LogLevel, maxDays: Int)

    suspend fun read(q: LogQuery): String

    suspend fun destroy()

    /** Multipart upload; [paramsJson] is JSON object text. */
    suspend fun upload(url: String, headers: Map<String, String>, paramsJson: String?): HttpResult

    /** Builds an `ACTION_SEND` intent with the gzipped log attached via [LogFileProvider]. */
    suspend fun prepareEmail(email: String, subject: String?): Intent
}
