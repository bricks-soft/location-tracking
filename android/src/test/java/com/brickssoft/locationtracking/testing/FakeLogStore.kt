package com.brickssoft.locationtracking.testing

import android.content.Intent
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.logging.LogQuery
import com.brickssoft.locationtracking.logging.LogStore
import com.brickssoft.locationtracking.model.HttpResult
import java.util.concurrent.CopyOnWriteArrayList

/** Recording [LogStore]. */
class FakeLogStore : LogStore {
    data class Line(val level: LogLevel, val tag: String, val message: String, val error: Throwable?)

    data class Upload(val url: String, val headers: Map<String, String>, val paramsJson: String?)

    val lines = CopyOnWriteArrayList<Line>()
    val configureCalls = CopyOnWriteArrayList<Pair<LogLevel, Int>>()
    val queries = CopyOnWriteArrayList<LogQuery>()
    val uploads = CopyOnWriteArrayList<Upload>()
    val emails = CopyOnWriteArrayList<Pair<String, String?>>()

    @Volatile
    var readResult = ""

    @Volatile
    var uploadResult = HttpResult(success = true, status = 200, responseText = "", uuids = emptyList())

    @Volatile
    var destroyCalls = 0

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        lines += Line(level, tag, message, error)
    }

    override fun configure(level: LogLevel, maxDays: Int) {
        configureCalls += level to maxDays
    }

    override suspend fun read(q: LogQuery): String {
        queries += q
        return readResult
    }

    override suspend fun destroy() {
        destroyCalls++
    }

    override suspend fun upload(url: String, headers: Map<String, String>, paramsJson: String?): HttpResult {
        uploads += Upload(url, headers, paramsJson)
        return uploadResult
    }

    override suspend fun prepareEmail(email: String, subject: String?): Intent {
        emails += email to subject
        return Intent(Intent.ACTION_SEND)
    }
}
