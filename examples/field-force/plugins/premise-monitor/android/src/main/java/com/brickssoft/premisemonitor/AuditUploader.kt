package com.brickssoft.premisemonitor

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uploads pending audit entries, oldest first, to the audit URL as `POST {device_id, entries}`
 * (`application/json; charset=utf-8`), in batches of at most [batchSize], on its own single thread `PM-upload` (so a
 * slow endpoint never delays audit writes or `status()` calls on `PM-native`).
 *
 * - [kick] after every new entry: a drain is queued unless one is already queued. A drain posts batches until nothing
 *   is pending; entries are marked uploaded only after a 2xx answer.
 * - A failure (non-2xx, I/O error, timeout) leaves the batch pending and stops the drain. It is retried with the next
 *   [kick] (the next entry) or after [retryDelayMs], whichever comes first. The order never changes: a drain always
 *   starts with the oldest pending entry.
 * - Nothing is sent while no audit URL is set; the entries stay pending.
 */
internal class AuditUploader(
    private val store: AuditStore,
    private val auditUrl: () -> String?,
    private val deviceId: () -> String,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val batchSize: Int = BATCH_SIZE,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    private val drainQueued = AtomicBoolean(false)

    // Only touched on the PM-upload thread.
    private var retry: ScheduledFuture<*>? = null

    /** Number of POST requests attempted (tests and logs). */
    @Volatile
    var attempts: Int = 0
        private set

    fun kick() {
        if (!drainQueued.compareAndSet(false, true)) return
        try {
            executor.execute {
                drainQueued.set(false)
                drain()
            }
        } catch (e: Exception) {
            drainQueued.set(false)
            PmLog.w(TAG, "upload not scheduled", e)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** Test support: waits until the upload thread has run everything queued so far (not the delayed retry). */
    internal fun awaitIdle(timeoutMs: Long = 10_000) {
        executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        val url = auditUrl() ?: return
        while (true) {
            val batch = try {
                store.pending(batchSize)
            } catch (e: Exception) {
                PmLog.e(TAG, "cannot read pending entries", e)
                scheduleRetry()
                return
            }
            if (batch.isEmpty()) {
                cancelRetry()
                return
            }
            if (!post(url, body(batch))) {
                scheduleRetry()
                return
            }
            try {
                store.markUploaded(batch.map { it.seq })
            } catch (e: Exception) {
                PmLog.e(TAG, "cannot mark ${batch.size} entries uploaded", e)
                scheduleRetry()
                return
            }
        }
    }

    /** `{"device_id": …, "entries": [ … ]}`; the stored JSON texts are copied as they are. */
    private fun body(batch: List<StoredEntry>): ByteArray {
        val builder = StringBuilder(batch.sumOf { it.json.length } + batch.size + 64)
        builder.append("{\"device_id\":").append(JSONObject.quote(deviceId())).append(",\"entries\":[")
        batch.forEachIndexed { index, entry ->
            if (index > 0) builder.append(',')
            builder.append(entry.json)
        }
        builder.append("]}")
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    private fun post(url: String, body: ByteArray): Boolean {
        attempts++
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.size)
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            // Read the body so the connection can be reused.
            try {
                (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            } catch (_: IOException) {
                // the status is what matters
            }
            if (status in 200..299) {
                true
            } else {
                PmLog.w(TAG, "audit upload answered HTTP $status; retrying later")
                false
            }
        } catch (e: Exception) {
            PmLog.w(TAG, "audit upload failed (${e.javaClass.simpleName}: ${e.message}); retrying later")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun scheduleRetry() {
        if (retry?.isDone == false) return
        retry = try {
            executor.schedule({ kick() }, retryDelayMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            PmLog.w(TAG, "retry not scheduled", e)
            null
        }
    }

    private fun cancelRetry() {
        retry?.cancel(false)
        retry = null
    }

    internal companion object {
        const val THREAD_NAME = "PM-upload"
        const val RETRY_DELAY_MS = 30_000L
        const val BATCH_SIZE = 100
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val TAG = "PM.Upload"
    }
}
