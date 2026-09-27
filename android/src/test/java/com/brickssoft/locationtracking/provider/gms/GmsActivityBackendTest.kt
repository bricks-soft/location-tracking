package com.brickssoft.locationtracking.provider.gms

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.tasks.Tasks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class GmsActivityBackendTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val client = mockk<ActivityRecognitionClient>()
    private val requested = slot<PendingIntent>()
    private val removed = slot<PendingIntent>()
    private lateinit var backend: GmsActivityBackend

    @Before
    fun setUp() {
        every { client.requestActivityUpdates(any(), capture(requested)) } returns voidTask()
        every { client.removeActivityUpdates(capture(removed)) } returns voidTask()
        backend = GmsActivityBackend(app, client)
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `is a supported GMS backend`() {
        assertEquals(ProviderKind.GMS, backend.kind)
        assertTrue(backend.isSupported)
    }

    @Test
    fun `start requests updates with an explicit mutable broadcast to the receiver`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        assertTrue(backend.start(10_000))

        verify { client.requestActivityUpdates(10_000, any()) }
        val pi = shadowOf(requested.captured)
        assertTrue(pi.isBroadcast)
        assertEquals(Constants.RC_GMS_ACTIVITY, pi.requestCode)
        assertEquals(GmsActivityReceiver::class.java.name, pi.savedIntent.component!!.className)
        assertEquals(Constants.ACTION_ACTIVITY, pi.savedIntent.action)
        assertTrue(pi.flags and PendingIntent.FLAG_MUTABLE != 0)
    }

    @Test
    @Config(sdk = [29])
    fun `start without ACTIVITY_RECOGNITION permission returns false on API 29+`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        assertFalse(backend.start(10_000))

        verify(exactly = 0) { client.requestActivityUpdates(any(), any()) }
    }

    @Test
    fun `start returns false when GMS rejects the call synchronously`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        every { client.requestActivityUpdates(any(), any()) } throws SecurityException("denied")

        assertFalse(backend.start(10_000))
    }

    @Test
    fun `asynchronous failure is logged and start still reports the request as issued`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        every { client.requestActivityUpdates(any(), any()) } returns
            Tasks.forException(ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED)))
        val sink = RecordingLogSink()
        Logger.sink = sink

        assertTrue(backend.start(10_000))

        assertTrue(sink.lines.any { it.message == "requestActivityUpdates failed" })
    }

    @Test
    fun `negative interval is clamped to zero`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        backend.start(-5)

        verify { client.requestActivityUpdates(0, any()) }
    }

    @Test
    fun `stop removes updates for the same PendingIntent`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        backend.start(10_000)

        backend.stop()

        assertSame(requested.captured, removed.captured)
    }

    @Test
    fun `stop without a registered PendingIntent does not call GMS`() {
        backend.stop()

        verify(exactly = 0) { client.removeActivityUpdates(any()) }
    }

    @Test
    fun `stop never throws`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        backend.start(10_000)
        every { client.removeActivityUpdates(any()) } throws IllegalStateException("not connected")

        backend.stop()
    }
}
