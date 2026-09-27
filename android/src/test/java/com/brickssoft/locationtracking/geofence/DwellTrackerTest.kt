package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.testing.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class DwellTrackerTest {
    private val fired = CopyOnWriteArrayList<Pair<String, Long>>()

    private fun TestScope.tracker(): Pair<DwellTracker, FakeClock> {
        val clock = FakeClock(scheduler = testScheduler)
        return DwellTracker(backgroundScope, clock) { id, token -> fired += id to token } to clock
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `fires once at the deadline with its token`() = runTest {
        val (dwell, clock) = tracker()

        dwell.arm("a", clock.now() + 30_000, token = 7)
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(fired.isEmpty())
        assertTrue(dwell.isArmed("a"))

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a" to 7L), fired)
        assertFalse(dwell.isArmed("a"))
        assertFalse(dwell.hasArmed())

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, fired.size)
    }

    @Test
    fun `cancel prevents firing`() = runTest {
        val (dwell, clock) = tracker()

        dwell.arm("a", clock.now() + 1_000, token = 1)
        dwell.arm("b", clock.now() + 1_000, token = 2)
        dwell.cancel("a")
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf("b" to 2L), fired)
    }

    @Test
    fun `cancelAll prevents every timer`() = runTest {
        val (dwell, clock) = tracker()

        dwell.arm("a", clock.now() + 1_000, token = 1)
        dwell.arm("b", clock.now() + 2_000, token = 2)
        dwell.cancelAll()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(fired.isEmpty())
        assertFalse(dwell.hasArmed())
    }

    @Test
    fun `re-arming replaces the previous timer`() = runTest {
        val (dwell, clock) = tracker()

        dwell.arm("a", clock.now() + 1_000, token = 1)
        dwell.arm("a", clock.now() + 3_000, token = 2)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(fired.isEmpty())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("a" to 2L), fired)
    }

    @Test
    fun `a deadline in the past fires immediately`() = runTest {
        val (dwell, clock) = tracker()

        dwell.arm("a", clock.now() - 10_000, token = 3)
        runCurrent()

        assertEquals(listOf("a" to 3L), fired)
    }

    @Test
    fun `takeDue returns overdue timers exactly once and disarms them`() = runTest {
        val (dwell, clock) = tracker()
        val start = clock.now()
        dwell.arm("a", start + 1_000, token = 1)
        dwell.arm("b", start + 60_000, token = 2)

        // As if the coroutine delay were stretched by deep sleep: the wall clock moved but the timer did not run.
        clock.advance(2_000)
        assertEquals(listOf("a" to 1L), dwell.takeDue())
        assertEquals(emptyList<Pair<String, Long>>(), dwell.takeDue())
        assertTrue(dwell.isArmed("b"))

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("the timer of a was disarmed", fired.isEmpty())
    }
}
