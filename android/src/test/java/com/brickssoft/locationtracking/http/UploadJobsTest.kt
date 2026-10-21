package com.brickssoft.locationtracking.http

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The upload job (recommendation 10): JobScheduler parameters, expedited fallback and deferral while it runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadJobsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val jobScheduler = context.getSystemService(JobScheduler::class.java)
    private val id = JobUploadScheduler.UPLOAD_JOB_ID

    @Before
    fun setUp() = JobUploadScheduler.resetForTest()

    @After
    fun tearDown() = JobUploadScheduler.resetForTest()

    private fun pending(): JobInfo? = jobScheduler.getPendingJob(id)

    @Test
    fun `a job without delay is expedited, requires any network and is persisted`() {
        JobUploadScheduler(context).schedule(0L)

        val job = assertNotNull(pending())
        assertTrue(job.isExpedited)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertTrue(job.isPersisted)
        assertEquals(0L, job.minLatencyMillis)
        assertEquals(UploadJobService::class.java.name, job.service.className)
    }

    @Test
    fun `when Android refuses the expedited job a regular one is scheduled`() {
        shadowOf(jobScheduler).failExpeditedJob(true)

        JobUploadScheduler(context).schedule(0L)

        val job = assertNotNull(pending())
        assertFalse(job.isExpedited)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
    }

    @Test
    fun `a job with a delay is a regular job with that minimum latency`() {
        JobUploadScheduler(context).schedule(120_000L)

        val job = assertNotNull(pending())
        assertFalse(job.isExpedited)
        assertEquals(120_000L, job.minLatencyMillis)
    }

    @Test
    fun `before Android 12 a job without delay is a regular job`() {
        JobUploadScheduler(context, sdkInt = Build.VERSION_CODES.R).schedule(0L)

        assertFalse(assertNotNull(pending()).isExpedited)
    }

    @Test
    fun `unmetered jobs require an unmetered network, and each schedule replaces the waiting job`() {
        val scheduler = JobUploadScheduler(context)
        scheduler.schedule(60_000L)
        scheduler.schedule(0L, unmetered = true)

        assertEquals(1, jobScheduler.allPendingJobs.size)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, assertNotNull(pending()).networkType)
    }

    @Test
    fun `cancel removes the waiting job`() {
        val scheduler = JobUploadScheduler(context)
        scheduler.schedule(60_000L)
        scheduler.cancel()

        assertNull(pending())
    }

    @Test
    fun `requests made while the job runs are applied after it finished`() {
        val scheduler = JobUploadScheduler(context)
        JobUploadScheduler.markRunning()

        scheduler.schedule(30_000L)
        assertNull(pending())

        scheduler.applyDeferred()
        assertEquals(30_000L, assertNotNull(pending()).minLatencyMillis)
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
