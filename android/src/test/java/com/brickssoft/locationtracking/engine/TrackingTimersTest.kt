package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.testing.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingTimersTest {
    private val fired = mutableListOf<String>()
    private val guard = Mutex()

    private fun TestScope.timers(clock: FakeClock = FakeClock(scheduler = testScheduler)) =
        TrackingTimers(backgroundScope, clock, guard)

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `timer fires once after its delay`() = runTest {
        val timers = timers()
        timers.schedule(TimerKind.STOP_TIMEOUT, 1_000) { fired += "stop" }
        assertTrue(timers.isArmed(TimerKind.STOP_TIMEOUT))
        assertEquals(1_000L, timers.remainingMs(TimerKind.STOP_TIMEOUT))

        advance(999)
        assertEquals(emptyList<String>(), fired)
        advance(1)
        assertEquals(listOf("stop"), fired)
        assertFalse(timers.isArmed(TimerKind.STOP_TIMEOUT))
        assertNull(timers.remainingMs(TimerKind.STOP_TIMEOUT))

        advance(10_000)
        assertEquals(listOf("stop"), fired)
    }

    @Test
    fun `cancel and reschedule`() = runTest {
        val timers = timers()
        timers.schedule(TimerKind.STOP_TIMEOUT, 1_000) { fired += "first" }
        timers.schedule(TimerKind.MOTION_TRIGGER, 1_000) { fired += "trigger" }
        timers.cancel(TimerKind.MOTION_TRIGGER)
        advance(500)
        timers.schedule(TimerKind.STOP_TIMEOUT, 1_000) { fired += "second" }

        advance(500)
        assertEquals(emptyList<String>(), fired)
        advance(500)
        assertEquals(listOf("second"), fired)
    }

    @Test
    fun `negative delay fires as soon as possible`() = runTest {
        val timers = timers()
        timers.schedule(TimerKind.STOP_AFTER_ELAPSED, -5_000) { fired += "elapsed" }
        runCurrent()
        assertEquals(listOf("elapsed"), fired)
    }

    @Test
    fun `cancelAll cancels every timer`() = runTest {
        val timers = timers()
        TimerKind.entries.forEach { kind -> timers.schedule(kind, 100) { fired += kind.name } }

        timers.cancelAll()
        advance(1_000)

        assertEquals(emptyList<String>(), fired)
        TimerKind.entries.forEach { assertFalse(timers.isArmed(it)) }
    }

    @Test
    fun `fireDue runs overdue timers when the clock moved without delay firing`() = runTest {
        val clock = FakeClock(scheduler = testScheduler)
        val timers = timers(clock)
        timers.schedule(TimerKind.STOP_TIMEOUT, 60_000) { fired += "stop" }
        timers.schedule(TimerKind.MOTION_TRIGGER, 120_000) { fired += "trigger" }

        clock.advance(60_000) // e.g. the CPU slept: elapsed realtime moved, delay() did not
        timers.fireDue()

        assertEquals(listOf("stop"), fired)
        assertTrue(timers.isArmed(TimerKind.MOTION_TRIGGER))
        // the coroutine of the fired timer was cancelled: it does not fire a second time
        advance(60_000)
        assertEquals(listOf("stop"), fired)
    }

    @Test
    fun `action runs under the guard and a cancel made under the guard wins`() = runTest {
        val timers = timers()
        timers.schedule(TimerKind.STOP_TIMEOUT, 1_000) { fired += "stop" }
        guard.lock()

        advance(1_000)
        assertEquals(emptyList<String>(), fired) // waiting for the guard
        timers.cancel(TimerKind.STOP_TIMEOUT)
        guard.unlock()
        runCurrent()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `action may reschedule its own kind`() = runTest {
        val timers = timers()
        timers.schedule(TimerKind.STOP_TIMEOUT, 1_000) {
            fired += "first"
            timers.schedule(TimerKind.STOP_TIMEOUT, 1_000) { fired += "second" }
        }

        advance(1_000)
        assertTrue(timers.isArmed(TimerKind.STOP_TIMEOUT))
        advance(1_000)

        assertEquals(listOf("first", "second"), fired)
    }

    @Test
    fun `action may cancel every timer`() = runTest {
        val timers = timers()
        timers.schedule(TimerKind.STOP_AFTER_ELAPSED, 1_000) {
            timers.cancelAll()
            fired += "elapsed"
        }
        timers.schedule(TimerKind.STOP_TIMEOUT, 2_000) { fired += "stop" }

        advance(5_000)

        assertEquals(listOf("elapsed"), fired)
    }
}
