package com.brickssoft.locationtracking.example.e2e

import com.brickssoft.locationtracking.example.e2e.E2eProtocol.Outcome
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One request of the debug receiver, from the command start to the single `LT-E2E` line.
 *
 * - [complete] takes the first outcome only: the command callback or the timeout, whichever comes first. Later
 *   calls do nothing, so a callback that arrives after a TIMEOUT line never logs a second line.
 * - The response is built, written (large results) and logged on the `LT-E2E` thread, never on the main thread and
 *   never on the plugin's `LT-native` thread (which also serves the plugin's native listeners).
 * - [finish] (the broadcast's `PendingResult.finish()`) runs exactly once, after the line, also when building or
 *   writing the response failed.
 *
 * No Android API is used here: logging and finishing are passed in, so the class can be tested without a device.
 */
class E2eExchange(
    private val filesDir: File,
    private val id: String?,
    private val cmd: String?,
    private val log: (String) -> Unit,
    private val finish: () -> Unit,
    private val executor: ScheduledExecutorService = E2eExecutor.instance,
) {
    private val done = AtomicBoolean(false)

    @Volatile
    private var timeout: ScheduledFuture<*>? = null

    /** Answers TIMEOUT if no outcome arrives within [timeoutMs]. Call it before the command starts. */
    fun armTimeout(timeoutMs: Long = E2eProtocol.TIMEOUT_MS) {
        timeout = executor.schedule(
            { complete(Outcome.Failure(E2eProtocol.TIMEOUT, "no result within $timeoutMs ms")) },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Delivers [outcome] if no outcome was delivered before; safe to call from any thread. */
    fun complete(outcome: Outcome) {
        if (!done.compareAndSet(false, true)) return
        timeout?.cancel(false)
        try {
            executor.execute { respond(outcome) }
        } catch (e: Exception) {
            // The executor never shuts down; if it refuses anyway, answer on this thread.
            respond(outcome)
        }
    }

    private fun respond(outcome: Outcome) {
        var afterLog: (() -> Unit)? = null
        try {
            val line = try {
                val output = E2eProtocol.shape(id, cmd, outcome)
                output.file?.let { writeResultFile(it) }
                if (outcome is Outcome.Success && output.ok) afterLog = outcome.afterLog
                output.line
            } catch (e: Throwable) {
                E2eProtocol.failureLine(id, cmd, E2eProtocol.INTERNAL, "response failed: ${e.javaClass.name}: ${e.message}")
            }
            log(line)
        } catch (e: Throwable) {
            // `log` itself failed; nothing else can be reported.
        } finally {
            try {
                finish()
            } catch (e: Throwable) {
                // finish() throws only if the broadcast was already finished; nothing to do.
            }
        }
        afterLog?.let {
            try {
                it()
            } catch (e: Throwable) {
                // The response line is already logged; a failed side effect has no second line.
            }
        }
    }

    /** Writes `<filesDir>/e2e/<name>` through a temporary file and a rename, so a reader never sees half a file. */
    private fun writeResultFile(file: E2eProtocol.ResultFile) {
        val dir = File(filesDir, E2eProtocol.RESULT_DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw java.io.IOException("cannot create $dir")
        }
        val target = File(dir, file.name)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(file.content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("cannot rename $tmp to $target")
            }
        }
    }
}

/** The single daemon thread `LT-E2E` that builds and logs responses and runs the timeouts. */
object E2eExecutor {
    const val THREAD_NAME = "LT-E2E"

    val instance: ScheduledExecutorService by lazy {
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }.apply {
            // A cancelled 25 s timeout leaves the queue at once instead of waiting for its delay.
            removeOnCancelPolicy = true
        }
    }
}
