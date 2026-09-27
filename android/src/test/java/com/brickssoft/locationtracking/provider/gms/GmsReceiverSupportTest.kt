package com.brickssoft.locationtracking.provider.gms

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GmsReceiverSupportTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
    private val scope = TestScope(StandardTestDispatcher())

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `finishes after the work completes`() {
        var ran = false

        scope.launchReceiverWork(pending, "LT.Test", "work") { ran = true }
        verify(exactly = 0) { pending.finish() }
        scope.advanceUntilIdle()

        assertTrue(ran)
        verify(exactly = 1) { pending.finish() }
    }

    @Test
    fun `logs a failure and still finishes`() {
        val sink = RecordingLogSink()
        Logger.sink = sink

        scope.launchReceiverWork(pending, "LT.Test", "work") { throw IllegalStateException("sink failed") }
        scope.advanceUntilIdle()

        verify(exactly = 1) { pending.finish() }
        assertEquals("work failed", sink.at(LogLevel.ERROR).single().message)
    }

    @Test
    fun `finishes even if the scope is already cancelled`() {
        var ran = false
        scope.cancel()

        scope.launchReceiverWork(pending, "LT.Test", "work") { ran = true }
        scope.advanceUntilIdle()

        assertTrue(!ran)
        verify(exactly = 1) { pending.finish() }
    }

    @Test
    fun `finishes after the deadline while the work keeps running, and only once`() {
        val gate = CompletableDeferred<Unit>()
        var done = false
        val sink = RecordingLogSink()
        Logger.sink = sink

        scope.launchReceiverWork(pending, "LT.Test", "work") {
            gate.await()
            done = true
        }
        scope.advanceTimeBy(RECEIVER_FINISH_DEADLINE_MS - 1)
        verify(exactly = 0) { pending.finish() }
        scope.advanceTimeBy(2)
        scope.runCurrent()
        verify(exactly = 1) { pending.finish() }
        assertTrue(sink.at(LogLevel.WARN).single().message.contains("still running"))

        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertTrue(done)
        verify(exactly = 1) { pending.finish() }
    }

    @Test
    fun `quick work cancels the deadline`() {
        scope.launchReceiverWork(pending, "LT.Test", "work") {}
        scope.advanceUntilIdle()

        verify(exactly = 1) { pending.finish() }
        // The watchdog was cancelled: nothing was left to advance virtual time to the deadline.
        assertEquals(0L, scope.currentTime)
    }

    @Test
    fun `deliverAsync runs the delivery with the resolved sink`() {
        val delivered = mutableListOf<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Unit
        }

        receiver.deliverAsync(app, { ReceiverTarget(scope, delivered) }, "LT.Test", "work") { it += "payload" }
        scope.advanceUntilIdle()

        assertEquals(listOf("payload"), delivered)
    }

    @Test
    fun `deliverAsync survives a target that cannot be resolved`() {
        val sink = RecordingLogSink()
        Logger.sink = sink
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Unit
        }

        receiver.deliverAsync<Unit>(app, { throw IllegalStateException("no components") }, "LT.Test", "work") {}

        assertEquals("cannot start work", sink.at(LogLevel.ERROR).single().message)
    }

    @Test
    fun `a failing finish and a missing pending result are tolerated`() {
        every { pending.finish() } throws IllegalStateException("already finished")

        scope.launchReceiverWork(pending, "LT.Test", "work") {}
        scope.launchReceiverWork(null, "LT.Test", "work") {}
        scope.advanceUntilIdle()
    }
}
