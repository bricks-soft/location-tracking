package com.brickssoft.locationtracking.provider.hms

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.huawei.hms.location.ActivityIdentificationService
import io.mockk.every
import io.mockk.mockk
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class HmsActivityBackendTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val service = mockk<ActivityIdentificationService>()
    private val created = CopyOnWriteArrayList<Pair<Long, PendingIntent>>()
    private val deleted = CopyOnWriteArrayList<PendingIntent>()
    private val backend = HmsActivityBackend(app) { service }

    @Before
    fun setUp() {
        every { service.createActivityIdentificationUpdates(any(), any()) } answers {
            created += firstArg<Long>() to secondArg<PendingIntent>()
            Hms.done(null)
        }
        every { service.deleteActivityIdentificationUpdates(any()) } answers {
            deleted += firstArg<PendingIntent>()
            Hms.done(null)
        }
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `is a supported HMS backend`() {
        assertEquals(ProviderKind.HMS, backend.kind)
        assertTrue(backend.isSupported)
    }

    @Test
    fun `start requests updates with the activity PendingIntent`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        assertTrue(backend.start(10_000))

        val (interval, pendingIntent) = created.single()
        assertEquals(10_000L, interval)
        assertActivityPendingIntent(pendingIntent)
    }

    @Test
    @Config(sdk = [30])
    fun `PendingIntent is not flagged mutable below API 31`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        assertTrue(backend.start(5_000))

        assertActivityPendingIntent(created.single().second)
    }

    @Test
    fun `negative interval is clamped`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        backend.start(-1)

        assertEquals(0L, created.single().first)
    }

    @Test
    fun `start without ACTIVITY_RECOGNITION returns false and requests nothing`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        assertFalse(backend.start(10_000))

        assertTrue(created.isEmpty())
    }

    @Test
    fun `start returns false when HMS throws`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        every { service.createActivityIdentificationUpdates(any(), any()) } throws SecurityException("denied")

        assertFalse(backend.start(10_000))
    }

    @Test
    fun `an asynchronous HMS failure is only logged`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        every { service.createActivityIdentificationUpdates(any(), any()) } returns
            Hms.failed(Hms.apiException(HmsStatusCodes.PERMISSION_DENIED))

        assertTrue(backend.start(10_000))
    }

    @Test
    fun `stop deletes updates for the same PendingIntent`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        backend.start(10_000)

        backend.stop()

        assertSame(created.single().second, deleted.single())
    }

    @Test
    fun `stop never throws`() {
        every { service.deleteActivityIdentificationUpdates(any()) } throws IllegalStateException("HMS gone")

        backend.stop()

        verify(exactly = 1) { service.deleteActivityIdentificationUpdates(any()) }
    }

    @Test
    fun `service is created lazily`() {
        var creations = 0
        val lazyBackend = HmsActivityBackend(app) {
            creations++
            service
        }
        assertEquals(0, creations)
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        lazyBackend.start(1_000)
        lazyBackend.stop()

        assertEquals(1, creations)
    }

    private fun assertActivityPendingIntent(pendingIntent: PendingIntent) {
        val shadow = shadowOf(pendingIntent)
        assertTrue(shadow.isBroadcast)
        assertEquals(Constants.RC_HMS_ACTIVITY, shadow.requestCode)
        assertEquals(HmsActivityReceiver::class.java.name, shadow.savedIntent.component?.className)
        assertEquals(Constants.ACTION_ACTIVITY, shadow.savedIntent.action)
        val mutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        assertEquals(mutable, shadow.flags and PendingIntent.FLAG_MUTABLE != 0)
        assertTrue(shadow.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }
}
