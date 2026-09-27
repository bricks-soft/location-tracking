package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.ActivityConfig
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.engine.MotionAction.CancelMotionTrigger
import com.brickssoft.locationtracking.engine.MotionAction.CancelStopTimer
import com.brickssoft.locationtracking.engine.MotionAction.EnterMoving
import com.brickssoft.locationtracking.engine.MotionAction.EnterStationary
import com.brickssoft.locationtracking.engine.MotionAction.StartMotionTrigger
import com.brickssoft.locationtracking.engine.MotionAction.StartStopTimer
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStateMachineTest {
    private val settings = MotionSettings(
        stationaryRadius = 25.0,
        minimumConfidence = 75,
        motionTriggerDelayMs = 0,
        stopTimeoutMs = STOP_TIMEOUT,
        disableStopDetection = false,
    )
    private val sm = MotionStateMachine(settings)
    private val origin = Fixtures.location(accuracy = 5f)

    @Test
    fun `starts stationary and the first fix becomes the anchor`() {
        assertEquals(MotionState.STATIONARY, sm.state)
        assertNull(sm.anchor)

        assertEquals(emptyList<MotionAction>(), sm.onLocation(origin))
        assertSame(origin, sm.anchor)
        assertFalse(sm.isMoving)
    }

    @Test
    fun `fix inside the stationary radius keeps the state`() {
        sm.onLocation(origin)

        assertEquals(emptyList<MotionAction>(), sm.onLocation(Fixtures.moved(origin, 20.0)))
        assertEquals(MotionState.STATIONARY, sm.state)
        assertSame(origin, sm.anchor)
    }

    @Test
    fun `fix beyond the stationary radius enters moving and arms the stop timer`() {
        sm.onLocation(origin)
        val far = Fixtures.moved(origin, 40.0)

        assertEquals(listOf(EnterMoving(far), StartStopTimer(STOP_TIMEOUT)), sm.onLocation(far))
        assertEquals(MotionState.MOVING, sm.state)
        assertTrue(sm.isStopTimerArmed)
        assertNull(sm.anchor)
        assertSame(far, sm.motionAnchor)
    }

    @Test
    fun `exit radius grows with the fix accuracy`() {
        sm.onLocation(origin)

        assertEquals(emptyList<MotionAction>(), sm.onLocation(Fixtures.moved(origin, 60.0).copy(accuracy = 80f)))
        assertEquals(MotionState.STATIONARY, sm.state)
        val precise = Fixtures.moved(origin, 60.0).copy(accuracy = 5f)
        assertEquals(EnterMoving(precise), sm.onLocation(precise).first())
    }

    @Test
    fun `the anchor accuracy does not widen the exit radius`() {
        val coarse = origin.copy(accuracy = 1_500f)
        sm.onLocation(coarse)
        val far = Fixtures.moved(coarse, 800.0).copy(accuracy = 10f)

        assertEquals(EnterMoving(far), sm.onLocation(far).first())
    }

    @Test
    fun `a more accurate fix inside the radius replaces the anchor`() {
        val coarse = origin.copy(accuracy = 20f)
        sm.onLocation(coarse)
        val better = Fixtures.moved(coarse, 20.0).copy(accuracy = 4f)
        val worse = Fixtures.moved(better, -10.0).copy(accuracy = 15f)

        assertEquals(emptyList<MotionAction>(), sm.onLocation(better))
        assertSame(better, sm.anchor)
        assertEquals(emptyList<MotionAction>(), sm.onLocation(worse))
        assertSame(better, sm.anchor)
        // 26 m from the new anchor, 46 m from the old one
        assertEquals(EnterMoving::class, sm.onLocation(Fixtures.moved(better, 26.0).copy(accuracy = 4f)).first()::class)
    }

    @Test
    fun `changing the motion trigger delay cancels a pending trigger`() {
        sm.updateSettings(settings.copy(motionTriggerDelayMs = 600_000))
        sm.onActivity(ActivitySample(ActivityType.WALKING, 90))
        assertTrue(sm.isMotionTriggerArmed)

        assertEquals(emptyList<MotionAction>(), sm.updateSettings(settings.copy(motionTriggerDelayMs = 600_000, stopTimeoutMs = 1)))
        assertEquals(listOf(CancelMotionTrigger), sm.updateSettings(settings))
        assertFalse(sm.isMotionTriggerArmed)
        assertEquals(EnterMoving(null), sm.onActivity(ActivitySample(ActivityType.WALKING, 90)).first())
    }

    @Test
    fun `confident moving activity enters moving immediately without trigger delay`() {
        sm.onLocation(origin)

        val actions = sm.onActivity(ActivitySample(ActivityType.IN_VEHICLE, 80))

        assertEquals(listOf(EnterMoving(null), StartStopTimer(STOP_TIMEOUT)), actions)
        assertTrue(sm.isMoving)
    }

    @Test
    fun `activities below the confidence threshold and unknown are ignored`() {
        assertEquals(emptyList<MotionAction>(), sm.onActivity(ActivitySample(ActivityType.WALKING, 74)))
        assertEquals(emptyList<MotionAction>(), sm.onActivity(ActivitySample(ActivityType.UNKNOWN, 100)))
        assertEquals(emptyList<MotionAction>(), sm.onActivity(ActivitySample(ActivityType.STILL, 100)))
        assertFalse(sm.isMoving)
    }

    @Test
    fun `every moving activity type counts as motion`() {
        for (type in listOf(
            ActivityType.WALKING,
            ActivityType.RUNNING,
            ActivityType.ON_FOOT,
            ActivityType.ON_BICYCLE,
            ActivityType.IN_VEHICLE,
        )) {
            val machine = MotionStateMachine(settings)
            assertEquals(type.wire, EnterMoving(null), machine.onActivity(ActivitySample(type, 90)).first())
        }
    }

    @Test
    fun `motion trigger delay must be sustained and still cancels it`() {
        sm.updateSettings(settings.copy(motionTriggerDelayMs = 30_000))

        assertEquals(listOf(StartMotionTrigger(30_000)), sm.onActivity(ActivitySample(ActivityType.WALKING, 90)))
        assertTrue(sm.isMotionTriggerArmed)
        assertEquals(emptyList<MotionAction>(), sm.onActivity(ActivitySample(ActivityType.RUNNING, 90)))
        assertEquals(listOf(CancelMotionTrigger), sm.onActivity(ActivitySample(ActivityType.STILL, 90)))
        assertFalse(sm.isMotionTriggerArmed)
        // a stale timer firing after the cancel does nothing
        assertEquals(emptyList<MotionAction>(), sm.onMotionTriggerElapsed())
        assertFalse(sm.isMoving)

        sm.onActivity(ActivitySample(ActivityType.WALKING, 90))
        assertEquals(listOf(EnterMoving(null), StartStopTimer(STOP_TIMEOUT)), sm.onMotionTriggerElapsed())
        assertTrue(sm.isMoving)
    }

    @Test
    fun `leaving stationary by distance cancels a pending motion trigger`() {
        sm.updateSettings(settings.copy(motionTriggerDelayMs = 30_000))
        sm.onLocation(origin)
        sm.onActivity(ActivitySample(ActivityType.WALKING, 90))
        val far = Fixtures.moved(origin, 100.0)

        assertEquals(listOf(CancelMotionTrigger, EnterMoving(far), StartStopTimer(STOP_TIMEOUT)), sm.onLocation(far))
    }

    @Test
    fun `while moving displacement and moving activity restart the stop timer`() {
        val start = enterMoving()

        val next = Fixtures.moved(start, 30.0)
        assertEquals(listOf(StartStopTimer(STOP_TIMEOUT)), sm.onLocation(next))
        assertSame(next, sm.motionAnchor)
        // no displacement beyond the radius: the timer keeps running
        assertEquals(emptyList<MotionAction>(), sm.onLocation(Fixtures.moved(next, 10.0)))
        assertSame(next, sm.motionAnchor)
        assertEquals(listOf(StartStopTimer(STOP_TIMEOUT)), sm.onActivity(ActivitySample(ActivityType.ON_FOOT, 80)))
        // still only makes sure the timer is armed
        assertEquals(emptyList<MotionAction>(), sm.onActivity(ActivitySample(ActivityType.STILL, 99)))
    }

    @Test
    fun `stop timeout enters stationary at the given location`() {
        enterMoving()
        val here = Fixtures.location(latitude = 1.0)

        assertEquals(listOf(EnterStationary(here, automatic = true)), sm.onStopTimeout(here))
        assertEquals(MotionState.STATIONARY, sm.state)
        assertSame(here, sm.anchor)
        assertFalse(sm.isStopTimerArmed)
        // a second (stale) timeout does nothing
        assertEquals(emptyList<MotionAction>(), sm.onStopTimeout(here))
    }

    @Test
    fun `moving without a known location takes the first fix as the motion reference`() {
        assertEquals(listOf(EnterMoving(null), StartStopTimer(STOP_TIMEOUT)), sm.force(true, null))
        assertNull(sm.motionAnchor)

        // the timer is already armed, so the first fix only becomes the reference
        assertEquals(emptyList<MotionAction>(), sm.onLocation(origin))
        assertSame(origin, sm.motionAnchor)
        assertTrue(sm.isStopTimerArmed)
    }

    @Test
    fun `disableStopDetection never arms the stop timer`() {
        sm.updateSettings(settings.copy(disableStopDetection = true))
        sm.onLocation(origin)

        assertEquals(listOf(EnterMoving(null)), sm.onActivity(ActivitySample(ActivityType.WALKING, 90)))
        assertEquals(emptyList<MotionAction>(), sm.onLocation(Fixtures.moved(origin, 100.0)))
        assertEquals(emptyList<MotionAction>(), sm.onActivity(ActivitySample(ActivityType.STILL, 90)))
        assertFalse(sm.isStopTimerArmed)
        assertTrue(sm.isMoving)
    }

    @Test
    fun `updateSettings arms, cancels and restarts the stop timer while moving`() {
        enterMoving()

        assertEquals(listOf(CancelStopTimer), sm.updateSettings(settings.copy(disableStopDetection = true)))
        assertEquals(listOf(StartStopTimer(STOP_TIMEOUT)), sm.updateSettings(settings))
        assertEquals(emptyList<MotionAction>(), sm.updateSettings(settings.copy(stationaryRadius = 50.0)))
        assertEquals(listOf(StartStopTimer(60_000)), sm.updateSettings(settings.copy(stopTimeoutMs = 60_000)))
    }

    @Test
    fun `force changes the state`() {
        sm.updateSettings(settings.copy(motionTriggerDelayMs = 30_000))
        sm.onActivity(ActivitySample(ActivityType.WALKING, 90))

        assertEquals(
            listOf(CancelMotionTrigger, EnterMoving(origin), StartStopTimer(STOP_TIMEOUT)),
            sm.force(true, origin),
        )
        assertEquals(listOf(StartStopTimer(STOP_TIMEOUT)), sm.force(true, origin))
        assertEquals(listOf(CancelStopTimer, EnterStationary(origin, automatic = false)), sm.force(false, origin))
        assertEquals(emptyList<MotionAction>(), sm.force(false, origin))
        assertSame(origin, sm.anchor)
    }

    @Test
    fun `reset returns to a clean state`() {
        enterMoving()

        sm.reset()

        assertEquals(MotionState.STATIONARY, sm.state)
        assertNull(sm.anchor)
        assertNull(sm.motionAnchor)
        assertFalse(sm.isStopTimerArmed)
        sm.offerAnchor(origin)
        assertSame(origin, sm.anchor)
        sm.offerAnchor(Fixtures.moved(origin, 5.0))
        assertSame(origin, sm.anchor)
    }

    @Test
    fun `settings come from config`() {
        val config = Config(
            geolocation = GeolocationConfig(stationaryRadius = 40.0, stopTimeout = 3),
            activity = ActivityConfig(
                minimumActivityRecognitionConfidence = 60,
                motionTriggerDelay = 15_000,
                disableStopDetection = true,
            ),
        )

        assertEquals(MotionSettings(40.0, 60, 15_000, 180_000, true), MotionSettings.from(config))
        // stopTimeout 0 is floored to one minute
        val zero = Config(geolocation = GeolocationConfig(stopTimeout = 0))
        assertEquals(60_000L, MotionSettings.from(zero).stopTimeoutMs)
    }

    private fun enterMoving(): com.brickssoft.locationtracking.model.TrackedLocation {
        sm.onLocation(origin)
        val far = Fixtures.moved(origin, 100.0)
        sm.onLocation(far)
        assertTrue(sm.isMoving)
        return far
    }

    private companion object {
        const val STOP_TIMEOUT = 300_000L
    }
}
