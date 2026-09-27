package com.brickssoft.locationtracking.provider.hms

import android.app.Application
import android.content.BroadcastReceiver
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HmsIntentsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `PendingIntents are stable and distinct per use`() {
        val activity = HmsPendingIntents.activity(app)
        val geofence = HmsPendingIntents.geofence(app)

        assertEquals(activity, HmsPendingIntents.activity(app))
        assertEquals(geofence, HmsPendingIntents.geofence(app))
        assertNotSame(activity, geofence)
        assertEquals(Constants.RC_HMS_ACTIVITY, shadowOf(activity).requestCode)
        assertEquals(Constants.RC_HMS_GEOFENCE, shadowOf(geofence).requestCode)
    }

    @Test
    fun `broadcast is finished once the delivery completes`() = runTest {
        val gate = CompletableDeferred<Unit>()

        deliverAsync(pending, this, "test", "work") { gate.await() }
        runCurrent()
        verify(exactly = 0) { pending.finish() }

        gate.complete(Unit)
        advanceUntilIdle()

        verify(exactly = 1) { pending.finish() }
    }

    @Test
    fun `a slow delivery releases the broadcast at the deadline and keeps running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var done = false

        val job = deliverAsync(pending, this, "test", "work", finishAfterMs = 9_000) {
            gate.await()
            done = true
        }
        advanceTimeBy(8_999)
        runCurrent()
        verify(exactly = 0) { pending.finish() }

        advanceTimeBy(2)
        runCurrent()
        verify(exactly = 1) { pending.finish() }
        assertTrue(job.isActive)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(done)
        verify(exactly = 1) { pending.finish() }
    }

    @Test
    fun `the default deadline stays below the 10 s receiver limit`() {
        assertTrue(BROADCAST_FINISH_DEADLINE_MS in 1..9_999)
    }

    @Test
    fun `a failing delivery is logged and finishes the broadcast`() = runTest {
        deliverAsync(pending, this, "test", "work") { throw IllegalStateException("boom") }
        advanceUntilIdle()

        verify(exactly = 1) { pending.finish() }
    }

    @Test
    fun `a cancelled delivery finishes the broadcast`() = runTest {
        val job = deliverAsync(pending, this, "test", "work") { awaitCancellation() }
        runCurrent()

        job.cancel()
        advanceUntilIdle()

        verify(exactly = 1) { pending.finish() }
    }

    @Test
    fun `delivery on an already cancelled scope still finishes the broadcast`() {
        val scope = TestScope()
        var ran = false
        scope.cancel()

        deliverAsync(pending, scope, "test", "work") { ran = true }
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 1) { pending.finish() }
        assertFalse(ran)
    }

    @Test
    fun `a throwing finish is swallowed`() = runTest {
        every { pending.finish() } throws IllegalStateException("already finished")

        deliverAsync(pending, this, "test", "work") {}
        advanceUntilIdle()

        verify(exactly = 1) { pending.finish() }
    }
}
