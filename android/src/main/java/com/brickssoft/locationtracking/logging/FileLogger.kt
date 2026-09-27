package com.brickssoft.locationtracking.logging

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

/**
 * [LogStore] that keeps the log in daily UTC files `filesDir/location-tracking-logs/lt-YYYY-MM-DD.log`, in the format
 * of [LogFormatter].
 *
 * [write] never blocks and never throws: it stamps the entry with [Clock.now] and appends it to an in-memory queue
 * (beyond [maxPending] pending entries the oldest are dropped, and a warning records how many). One writer at a time
 * drains the queue on [AppDispatchers.io]. [read], [destroy], [upload] and [prepareEmail] drain the queue first, so
 * they see every entry written before they were called.
 *
 * Files are purged by [configure] and on each UTC day rollover: a file is deleted when its whole day lies more than
 * `maxDays` days back, i.e. the files of today and of the previous `maxDays` days are kept.
 *
 * [read] returns whole entries (a header line plus its indented continuation lines). `start`/`end` are inclusive
 * bounds on the entry time; `level` keeps entries at that severity or above, as [LogLevel.allows] (so `OFF` matches
 * nothing); a missing or non-positive `limit` means no limit.
 *
 * The logger's own I/O errors go to `android.util.Log` as a last resort: reporting them through [Logger] would recurse
 * into this sink.
 *
 * @param maxPending entries kept in memory while the writer is behind; the oldest are dropped beyond it.
 */
class FileLogger(
    context: Context,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val http: Lazy<OkHttpClient>,
    maxPending: Int = DEFAULT_MAX_PENDING,
) : LogStore {
    /** A queued entry; the stack trace is rendered at write time so the throwable is not retained. */
    private class Entry(
        val timeMs: Long,
        val level: LogLevel,
        val tag: String,
        val message: String,
        val stackTrace: String?,
    )

    private val appContext: Context = context.applicationContext ?: context
    private val maxPending = maxPending.coerceAtLeast(1)

    // Resolved on first use, on the writer's thread: Context.getFilesDir() may touch the disk.
    private val logDir: File by lazy { File(appContext.filesDir, Constants.LOG_DIR) }
    private val cacheLogDir: File by lazy { File(appContext.cacheDir, Constants.LOG_DIR) }

    private val queueLock = Any()
    private val pending = ArrayDeque<Entry>() // guarded by queueLock
    private var dropped = 0 // guarded by queueLock
    private val drainScheduled = AtomicBoolean(false)

    /** Serializes all file access; whoever holds it is the single writer. */
    private val fileLock = Mutex()
    private var lastWrittenDay = Long.MIN_VALUE // guarded by fileLock
    private var newlineCheckedFile: String? = null // guarded by fileLock

    @Volatile
    private var level: LogLevel = LogLevel.INFO

    @Volatile
    private var maxDays: Int = DEFAULT_MAX_DAYS

    private val scope = CoroutineScope(
        SupervisorJob() + dispatchers.io + CoroutineExceptionHandler { _, t -> fallbackLog("log writer failed", t) },
    )

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        try {
            if (!this.level.allows(level)) return
            val stackTrace = error?.let { LogFormatter.stackTrace(it) }
            synchronized(queueLock) {
                if (pending.size >= maxPending) {
                    pending.removeFirst()
                    dropped++
                }
                // Stamped inside the lock so that queue (= file) order is timestamp order.
                pending.addLast(Entry(clock.now(), level, tag, message, stackTrace))
            }
            scheduleDrain()
        } catch (_: Throwable) {
            // A logger must never break its caller.
        }
    }

    override fun configure(level: LogLevel, maxDays: Int) {
        this.level = level
        this.maxDays = maxDays
        launchSafely { fileLock.withLock { purgeLocked(LogFormatter.epochDay(clock.now())) } }
    }

    /** Writes every pending entry to disk. Used by tests and by every operation that reads the files. */
    internal suspend fun flush() {
        withContext(dispatchers.io) { fileLock.withLock { drainLocked() } }
    }

    override suspend fun read(q: LogQuery): String = withContext(dispatchers.io) {
        fileLock.withLock {
            drainLocked()
            try {
                readLocked(q)
            } catch (e: IOException) {
                throw TrackingException(ErrorCode.IO_ERROR, "Could not read the log: ${e.message}", e)
            }
        }
    }

    /** Deletes the daily files and every gzipped copy (email attachment, leftover upload files). */
    override suspend fun destroy() {
        withContext(dispatchers.io) {
            fileLock.withLock {
                drainLocked()
                logFiles().forEach { it.second.delete() }
                cacheLogDir.listFiles()?.forEach { it.delete() }
                newlineCheckedFile = null
            }
        }
    }

    /**
     * Posts the gzipped log as multipart/form-data. HTTP and I/O failures come back as an unsuccessful [HttpResult]
     * (status 0 when there was no response); an invalid [url] or header throws `INVALID_ARGUMENT`.
     */
    override suspend fun upload(url: String, headers: Map<String, String>, paramsJson: String?): HttpResult {
        val httpUrl = url.toHttpUrlOrNull()
            ?: throw TrackingException(ErrorCode.INVALID_ARGUMENT, "uploadLog: invalid URL: $url")
        val request = Request.Builder().url(httpUrl)
        try {
            headers.forEach { (name, value) -> request.header(name, value) }
        } catch (e: IllegalArgumentException) {
            throw TrackingException(ErrorCode.INVALID_ARGUMENT, "uploadLog: ${e.message}", e)
        }
        return withContext(dispatchers.io) {
            var gzip: File? = null
            try {
                val file = fileLock.withLock {
                    drainLocked()
                    cacheLogDir.mkdirs()
                    File.createTempFile(UPLOAD_TEMP_PREFIX, GZIP_SUFFIX, cacheLogDir).also {
                        gzip = it
                        writeGzipLocked(it)
                    }
                }
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(UPLOAD_FIELD, UPLOAD_FILE_NAME, file.asRequestBody(GZIP_MEDIA_TYPE.toMediaType()))
                parseParams(paramsJson).forEach { (name, value) -> body.addFormDataPart(name, value) }
                http.value.newCall(request.post(body.build()).build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) Logger.w(TAG, "uploadLog: HTTP ${response.code}")
                    HttpResult(response.isSuccessful, response.code, text, emptyList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // IOException (network, or gzip/disk), or a runtime failure inside OkHttp.
                Logger.w(TAG, "uploadLog failed", e)
                HttpResult(false, 0, e.message ?: e.javaClass.simpleName, emptyList())
            } finally {
                gzip?.delete()
            }
        }
    }

    override suspend fun prepareEmail(email: String, subject: String?): Intent = withContext(dispatchers.io) {
        val file = fileLock.withLock {
            drainLocked()
            val temp = File(cacheLogDir, "$EMAIL_FILE_NAME.tmp")
            try {
                cacheLogDir.mkdirs()
                writeGzipLocked(temp)
                val target = File(cacheLogDir, EMAIL_FILE_NAME)
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("Could not rename $temp to $target")
                }
                target
            } catch (e: IOException) {
                temp.delete()
                throw TrackingException(ErrorCode.IO_ERROR, "Could not prepare the log: ${e.message}", e)
            }
        }
        val uri = try {
            FileProvider.getUriForFile(appContext, appContext.packageName + Constants.LOG_FILE_PROVIDER_SUFFIX, file)
        } catch (e: IllegalArgumentException) {
            throw TrackingException(ErrorCode.INTERNAL, "LogFileProvider is not configured: ${e.message}", e)
        }
        emailIntent(email, subject?.takeIf { it.isNotBlank() } ?: DEFAULT_EMAIL_SUBJECT, uri)
    }

    private fun emailIntent(email: String, subject: String, uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = GZIP_MEDIA_TYPE
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, emailSummary())
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(EMAIL_FILE_NAME, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, subject).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun emailSummary(): String = buildString {
        append("Location tracking log\n")
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("Plugin version: ").append(BuildConfig.PLUGIN_VERSION).append('\n')
        append("App: ").append(appContext.packageName).append('\n')
        append("Created: ").append(Iso8601.format(clock.now())).append('\n')
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        launchSafely {
            // Cleared before draining: an entry queued from now on is either drained below or schedules a new drain.
            drainScheduled.set(false)
            fileLock.withLock { drainLocked() }
        }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        try {
            scope.launch { block() }
        } catch (t: Throwable) {
            drainScheduled.set(false)
            fallbackLog("could not start the log writer", t)
        }
    }

    /** Writes queued entries until the queue is empty. Caller holds [fileLock]. */
    private fun drainLocked() {
        while (true) {
            val batch: List<Entry>
            val droppedCount: Int
            synchronized(queueLock) {
                if (pending.isEmpty() && dropped == 0) return
                batch = ArrayList(pending)
                pending.clear()
                droppedCount = dropped
                dropped = 0
            }
            writeBatchLocked(withDropNotice(batch, droppedCount))
        }
    }

    private fun withDropNotice(batch: List<Entry>, droppedCount: Int): List<Entry> {
        if (droppedCount == 0 || !level.allows(LogLevel.WARN)) return batch
        val time = batch.firstOrNull()?.timeMs ?: clock.now()
        val notice = Entry(time, LogLevel.WARN, TAG, "Dropped $droppedCount log entries: write queue full", null)
        return listOf(notice) + batch
    }

    /** Appends [batch] to the daily files, one file open per run of same-day entries. Caller holds [fileLock]. */
    private fun writeBatchLocked(batch: List<Entry>) {
        var start = 0
        while (start < batch.size) {
            val day = LogFormatter.epochDay(batch[start].timeMs)
            var end = start + 1
            while (end < batch.size && LogFormatter.epochDay(batch[end].timeMs) == day) end++
            // Tracks the last day actually written (not the maximum), so purging resumes after a wrong clock is fixed.
            if (lastWrittenDay != Long.MIN_VALUE && day > lastWrittenDay) purgeLocked(day)
            lastWrittenDay = day
            appendLocked(batch, start, end)
            start = end
        }
    }

    private fun appendLocked(batch: List<Entry>, start: Int, end: Int) {
        val file = File(logDir, LogFormatter.fileName(batch[start].timeMs))
        val text = StringBuilder()
        try {
            if (!logDir.isDirectory) logDir.mkdirs()
            // A write cut short (process killed, disk full) leaves a line without '\n'; never glue a header onto it.
            if (file.name != newlineCheckedFile && endsWithoutNewline(file)) text.append('\n')
            for (i in start until end) {
                val e = batch[i]
                text.append(LogFormatter.formatEntry(e.timeMs, e.level, e.tag, e.message, e.stackTrace))
            }
            FileOutputStream(file, true).use { it.write(text.toString().toByteArray(Charsets.UTF_8)) }
            newlineCheckedFile = file.name
        } catch (e: IOException) {
            newlineCheckedFile = null
            fallbackLog("could not write $file", e)
        }
    }

    private fun endsWithoutNewline(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read() != '\n'.code
        }
    }

    /** Deletes daily files whose day is more than [maxDays] days before [today]. Caller holds [fileLock]. */
    private fun purgeLocked(today: Long) {
        val keepFrom = today - maxDays.coerceAtLeast(0)
        try {
            logFiles().forEach { (day, file) -> if (day < keepFrom) file.delete() }
        } catch (e: RuntimeException) {
            fallbackLog("could not purge old log files", e)
        }
    }

    /** Daily log files with their day numbers, oldest first. */
    private fun logFiles(): List<Pair<Long, File>> =
        logDir.listFiles().orEmpty()
            .mapNotNull { file -> LogFormatter.epochDayOfFileName(file.name)?.let { it to file } }
            .filter { it.second.isFile }
            .sortedBy { it.first }

    /** Caller holds [fileLock]. */
    private fun readLocked(q: LogQuery): String {
        val limit = q.limit?.takeIf { it > 0 }
        val files = logFiles().filter { (day, _) ->
            (q.start == null || (day + 1) * LogFormatter.DAY_MS > q.start) &&
                (q.end == null || day * LogFormatter.DAY_MS <= q.end)
        }
        val out = StringBuilder()
        var count = 0
        if (q.ascending) {
            for ((_, file) in files) {
                forEachEntry(file, q) { entry ->
                    out.append(entry)
                    count++
                    limit == null || count < limit
                }
                if (limit != null && count >= limit) break
            }
        } else {
            // Newest file first; of each file keep only the newest entries that are still needed.
            for ((_, file) in files.asReversed()) {
                val needed = limit?.minus(count)
                val newest = ArrayDeque<String>()
                forEachEntry(file, q) { entry ->
                    newest.addLast(entry)
                    if (needed != null && newest.size > needed) newest.removeFirst()
                    true
                }
                for (i in newest.indices.reversed()) out.append(newest[i])
                count += newest.size
                if (limit != null && count >= limit) break
            }
        }
        return out.toString()
    }

    /** Calls [onEntry] with each entry of [file] that matches [q], in file order, while it returns true. */
    private inline fun forEachEntry(file: File, q: LogQuery, onEntry: (String) -> Boolean) {
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            // The entry being read, or null while skipping one that does not match. Entries never span files.
            var current: StringBuilder? = null
            while (true) {
                val line = reader.readLine() ?: break
                val header = LogFormatter.parseHeader(line)
                if (header == null) {
                    current?.append(line)?.append('\n')
                    continue
                }
                if (current != null && !onEntry(current.toString())) return
                current = if (matches(header, q)) StringBuilder(line).append('\n') else null
            }
            if (current != null) onEntry(current.toString())
        }
    }

    private fun matches(header: LogFormatter.Header, q: LogQuery): Boolean =
        (q.start == null || header.timeMs >= q.start) &&
            (q.end == null || header.timeMs <= q.end) &&
            (q.level == null || q.level.allows(header.level))

    /** Writes all daily files, oldest first, gzipped into [target]. Caller holds [fileLock]. */
    private fun writeGzipLocked(target: File) {
        FileOutputStream(target).use { raw ->
            GZIPOutputStream(raw.buffered()).use { out ->
                for ((_, file) in logFiles()) file.inputStream().use { it.copyTo(out) }
            }
        }
    }

    private fun parseParams(paramsJson: String?): List<Pair<String, String>> {
        if (paramsJson.isNullOrBlank()) return emptyList()
        val json = try {
            JSONObject(paramsJson)
        } catch (e: JSONException) {
            Logger.w(TAG, "uploadLog: ignoring params that are not a JSON object", e)
            return emptyList()
        }
        return json.keys().asSequence().map { key ->
            val value = json.opt(key)
            key to if (value == null || value == JSONObject.NULL) "" else value.toString()
        }.toList()
    }

    /** Last resort for the logger's own failures; see the class comment. */
    private fun fallbackLog(message: String, t: Throwable?) {
        try {
            Log.w(TAG, message, t)
        } catch (_: Throwable) {
            // logcat unavailable (plain JVM)
        }
    }

    companion object {
        const val DEFAULT_MAX_PENDING = 5_000
        const val DEFAULT_MAX_DAYS = 3

        /** Name of the gzipped log attached by [upload] and [prepareEmail]. */
        const val UPLOAD_FILE_NAME = "location-tracking-log.txt.gz"
        const val EMAIL_FILE_NAME = UPLOAD_FILE_NAME
        const val UPLOAD_FIELD = "log"
        const val DEFAULT_EMAIL_SUBJECT = "Location tracking log"
        const val GZIP_MEDIA_TYPE = "application/gzip"

        private const val TAG = "LT.FileLogger"
        private const val UPLOAD_TEMP_PREFIX = "upload-"
        private const val GZIP_SUFFIX = ".txt.gz"
    }
}
