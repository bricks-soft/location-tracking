// STUB — owned by Unit 17 (Logger). Replace this implementation.
package com.brickssoft.locationtracking.logging

import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.model.HttpResult
import okhttp3.OkHttpClient

/** Daily log files. Stub: discards everything. */
@Suppress("unused")
class FileLogger(
    private val context: Context,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val http: Lazy<OkHttpClient>,
) : LogStore {
    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) = Unit

    override fun configure(level: LogLevel, maxDays: Int) = Unit

    override suspend fun read(q: LogQuery): String = ""

    override suspend fun destroy() = Unit

    override suspend fun upload(url: String, headers: Map<String, String>, paramsJson: String?): HttpResult =
        HttpResult(success = false, status = 0, responseText = "", uuids = emptyList())

    override suspend fun prepareEmail(email: String, subject: String?): Intent = Intent(Intent.ACTION_SEND)
}
