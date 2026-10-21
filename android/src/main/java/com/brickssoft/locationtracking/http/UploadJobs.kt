package com.brickssoft.locationtracking.http

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Schedules the upload job (recommendation 10): one upload pass in a process that may have no tracking session, so
 * queued records reach the server whether tracking is on or off.
 */
interface UploadScheduler {
    /**
     * Replaces any waiting upload job with one that runs once a network is available and [delayMs] have passed.
     * [unmetered]: only on an unmetered network (normal records with `disableAutoSyncOnCellular` on cellular).
     */
    fun schedule(delayMs: Long, unmetered: Boolean = false)

    /** Cancels the waiting upload job (the queue is empty). */
    fun cancel()

    companion object {
        /** Schedules nothing (tests, and callers without a Context). */
        val NONE: UploadScheduler = object : UploadScheduler {
            override fun schedule(delayMs: Long, unmetered: Boolean) = Unit

            override fun cancel() = Unit
        }
    }
}

/**
 * [UploadScheduler] over the platform `JobScheduler` (no WorkManager dependency). One job with the fixed id
 * [UPLOAD_JOB_ID]; each [schedule] replaces the waiting one.
 *
 * - The job requires a network (any network, or an unmetered one), is persisted across reboots
 *   (`RECEIVE_BOOT_COMPLETED` is declared by the plugin), and starts no earlier than the given delay.
 * - Android 12+: a job without a delay is scheduled as expedited, so it runs as soon as possible even while the app is
 *   in the background; when the expedited quota is used up (Android refuses it), it is scheduled as a regular job.
 *   A job with a delay is always a regular job: Android does not allow a start delay on an expedited job.
 * - A [schedule] or [cancel] made while the job runs (by the pass it runs) is applied when the job has finished:
 *   scheduling the same id would stop the running job.
 * - The same request as the last one is not sent again within this process (the pass runs on every new record).
 */
class JobUploadScheduler(context: Context, private val sdkInt: Int = Build.VERSION.SDK_INT) : UploadScheduler {
    private val appContext: Context = context.applicationContext ?: context
    private val jobScheduler: JobScheduler? by lazy { appContext.getSystemService(JobScheduler::class.java) }

    override fun schedule(delayMs: Long, unmetered: Boolean) {
        val request = Request.Schedule(delayMs.coerceAtLeast(0L), unmetered)
        if (deferWhileRunning(request)) return
        apply(request)
    }

    override fun cancel() {
        if (deferWhileRunning(Request.Cancel)) return
        apply(Request.Cancel)
    }

    /** Applies the request the job's own pass made, after the job finished. */
    internal fun applyDeferred() {
        val request = synchronized(lock) {
            running = false
            deferred.also { deferred = null }
        } ?: return
        apply(request)
    }

    private fun deferWhileRunning(request: Request): Boolean = synchronized(lock) {
        if (!running) return false
        deferred = request
        true
    }

    private fun apply(request: Request) {
        synchronized(lock) {
            if (request == lastApplied) return
            lastApplied = request
        }
        val scheduler = jobScheduler ?: return Logger.w(TAG, "JobScheduler is unavailable; no upload job")
        try {
            when (request) {
                Request.Cancel -> {
                    scheduler.cancel(UPLOAD_JOB_ID)
                    Logger.d(TAG, "upload job cancelled: the queue is empty")
                }
                is Request.Schedule -> scheduleJob(scheduler, request)
            }
        } catch (e: Exception) {
            synchronized(lock) { lastApplied = null }
            Logger.w(TAG, "could not update the upload job", e)
        }
    }

    private fun scheduleJob(scheduler: JobScheduler, request: Request.Schedule) {
        val network = if (request.unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        if (request.delayMs == 0L && sdkInt >= Build.VERSION_CODES.S) {
            val expedited = try {
                scheduler.schedule(builder(network).setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS
            } catch (e: Exception) {
                Logger.d(TAG, "expedited upload job refused (${e.javaClass.simpleName}: ${e.message})")
                false
            }
            if (expedited) {
                Logger.d(TAG, "upload job scheduled (expedited, network ${networkName(network)})")
                return
            }
            Logger.d(TAG, "expedited upload job refused (quota used up?); scheduling a regular job")
        }
        val builder = builder(network)
        if (request.delayMs > 0) builder.setMinimumLatency(request.delayMs)
        val result = scheduler.schedule(builder.build())
        if (result == JobScheduler.RESULT_SUCCESS) {
            Logger.d(TAG, "upload job scheduled in ${request.delayMs / 1_000} s (network ${networkName(network)})")
        } else {
            synchronized(lock) { lastApplied = null }
            Logger.w(TAG, "JobScheduler refused the upload job")
        }
    }

    private fun builder(network: Int): JobInfo.Builder =
        JobInfo.Builder(UPLOAD_JOB_ID, ComponentName(appContext, UploadJobService::class.java))
            .setRequiredNetworkType(network)
            .setPersisted(true)

    private fun networkName(network: Int): String = if (network == JobInfo.NETWORK_TYPE_UNMETERED) "unmetered" else "any"

    private sealed interface Request {
        data class Schedule(val delayMs: Long, val unmetered: Boolean) : Request

        data object Cancel : Request
    }

    internal companion object {
        private const val TAG = "LT.UploadJob"

        /**
         * JobScheduler ids are shared with the host app's own jobs (and WorkManager's, which counts up from 0), so the
         * plugin uses one large fixed id ("LT" in its high bytes).
         */
        const val UPLOAD_JOB_ID = 0x4C54_0001

        private val lock = Any()

        /** True while [UploadJobService] runs a pass; guarded by [lock]. */
        private var running = false
        private var deferred: Request? = null
        private var lastApplied: Request? = null

        /** Marks the job as running, so the scheduling its pass does waits for [applyDeferred]. */
        internal fun markRunning() = synchronized(lock) {
            running = true
            // The system dropped the job it runs now: the next request must be sent even if it equals the last one.
            lastApplied = null
        }

        /** Test hook: forgets the in-process state. */
        internal fun resetForTest() = synchronized(lock) {
            running = false
            deferred = null
            lastApplied = null
        }
    }
}

/**
 * Runs one upload job: builds `Components` (the process may have been started for this job) and lets the uploader run
 * its scheduled passes ([HttpSyncer.runScheduledPass]). What the passes want next (another job, or none) is applied
 * after the job has finished.
 */
class UploadJobService : JobService() {
    @Volatile
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val components = try {
            Components.get(applicationContext)
        } catch (e: Exception) {
            Logger.e(TAG, "components unavailable; upload job not run", e)
            return false
        }
        JobUploadScheduler.markRunning()
        Logger.i(TAG, "upload job started")
        work = components.scope.launch {
            try {
                components.syncer.runScheduledPass()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "upload job failed", e)
            } finally {
                jobFinished(params, false)
                (components.uploadScheduler as? JobUploadScheduler)?.applyDeferred()
                Logger.d(TAG, "upload job finished")
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Logger.i(TAG, "upload job stopped by the system; it will run again")
        work?.cancel()
        return true
    }

    private companion object {
        const val TAG = "LT.UploadJob"
    }
}
