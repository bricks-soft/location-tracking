package com.brickssoft.locationtracking.position

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleCollectorTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun fix(accuracy: Float, time: Long = Fixtures.FIX_TIME) = Fixtures.location(accuracy = accuracy, time = time)

    @Test
    fun `keeps the most accurate fix and finishes after the wanted count`() {
        val c = SampleCollector(3)
        assertNull(c.best)

        assertFalse(c.add(fix(40f)))
        assertFalse(c.add(fix(15f)))
        assertTrue(c.add(fix(25f)))

        assertEquals(15f, c.best!!.accuracy)
        assertEquals(3, c.count)
    }

    @Test
    fun `finishes early once the best fix is accurate enough`() {
        val c = SampleCollector(5)

        assertFalse(c.add(fix(10.5f)))
        assertTrue(c.add(fix(DefaultPositionService.ACCURATE_ENOUGH_M)))
        assertTrue(c.add(fix(30f)))

        assertEquals(DefaultPositionService.ACCURATE_ENOUGH_M, c.best!!.accuracy)
    }

    @Test
    fun `unknown accuracy ranks last and never ends sampling early`() {
        val c = SampleCollector(4)

        assertFalse(c.add(fix(0f)))
        assertFalse(c.add(fix(Float.NaN)))
        assertEquals(2, c.count)
        assertFalse(c.add(fix(80f)))

        assertEquals(80f, c.best!!.accuracy)
    }

    @Test
    fun `equal accuracy prefers the newer fix`() {
        val c = SampleCollector(3)
        c.add(fix(20f, time = 1_000))
        val newer = fix(20f, time = 2_000)

        c.add(newer)

        assertSame(newer, c.best)
    }

    @Test
    fun `equal accuracy keeps the newer fix even when an older one arrives later`() {
        val c = SampleCollector(3)
        val newer = fix(20f, time = 2_000)
        c.add(newer)

        c.add(fix(20f, time = 1_000))

        assertSame(newer, c.best)
    }

    @Test
    fun `wanted below 1 means a single sample`() {
        assertTrue(SampleCollector(0).add(fix(90f)))
        assertTrue(SampleCollector(-3).add(fix(90f)))
    }

    @Test
    fun `backend errors map to tracking errors`() {
        val own = TrackingException(ErrorCode.NOT_FOUND, "x")

        assertSame(own, DefaultPositionService.backendError(own))
        assertEquals(ErrorCode.PERMISSION_DENIED, DefaultPositionService.backendError(SecurityException("s")).code)
        assertEquals(ErrorCode.UNAVAILABLE, DefaultPositionService.backendError(IllegalStateException("i")).code)
    }
}
