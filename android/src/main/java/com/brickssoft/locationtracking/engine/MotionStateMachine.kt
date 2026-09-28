package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.TrackedLocation
import kotlin.math.max

/** Motion state of the LOCATION tracking mode. */
internal enum class MotionState {
    STATIONARY,
    MOVING,
}

/**
 * The config values the [MotionStateMachine] needs.
 *
 * @property stopTimeoutMs time without evidence of motion after which MOVING becomes STATIONARY (at least 1 min).
 * @property trackingAccuracyThreshold `geolocation.filter.trackingAccuracyThreshold` (m): a STATIONARY fix with a
 *   worse accuracy never leaves STATIONARY by itself; 0 or less turns this check off.
 */
internal data class MotionSettings(
    val stationaryRadius: Double = 25.0,
    val minimumConfidence: Int = 75,
    val motionTriggerDelayMs: Long = 0,
    val stopTimeoutMs: Long = 5 * MINUTE_MS,
    val disableStopDetection: Boolean = false,
    val trackingAccuracyThreshold: Double = 100.0,
) {
    companion object {
        private const val MINUTE_MS = 60_000L

        fun from(config: Config): MotionSettings = MotionSettings(
            stationaryRadius = config.geolocation.stationaryRadius.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0,
            minimumConfidence = config.activity.minimumActivityRecognitionConfidence,
            motionTriggerDelayMs = config.activity.motionTriggerDelay.coerceAtLeast(0),
            // A zero timeout would flip back to STATIONARY right after every fix; one minute is the floor.
            stopTimeoutMs = config.geolocation.stopTimeout.coerceAtLeast(1) * MINUTE_MS,
            disableStopDetection = config.activity.disableStopDetection,
            trackingAccuracyThreshold =
                config.geolocation.filter.trackingAccuracyThreshold.takeIf { it.isFinite() } ?: 0.0,
        )
    }
}

/** What the engine must do after feeding an input to the [MotionStateMachine]. Actions are listed in order. */
internal sealed interface MotionAction {
    /** The state became MOVING; [location] is the fix that triggered it, or null (activity, changePace). */
    data class EnterMoving(val location: TrackedLocation?) : MotionAction

    /** The state became STATIONARY at [location]; [automatic] is true for stop detection (not changePace). */
    data class EnterStationary(val location: TrackedLocation?, val automatic: Boolean) : MotionAction

    /** (Re)starts the stop-timeout timer; it must call [MotionStateMachine.onStopTimeout] when it fires. */
    data class StartStopTimer(val delayMs: Long) : MotionAction

    data object CancelStopTimer : MotionAction

    /** Starts the motion-trigger timer; it must call [MotionStateMachine.onMotionTriggerElapsed] when it fires. */
    data class StartMotionTrigger(val delayMs: Long) : MotionAction

    data object CancelMotionTrigger : MotionAction
}

/**
 * STATIONARY / MOVING state machine. Pure: no Android, no clock, no timers of its own; the engine runs the
 * timers it asks for and reports back when they fire.
 *
 * - **STATIONARY** has an anchor (the first fix, or the location where the device stopped). It becomes MOVING
 *   when a fix is certainly outside the stationary radius (`distance(anchor, fix) - fix accuracy >
 *   stationaryRadius`) and its accuracy is at most `trackingAccuracyThreshold`, or when a moving activity
 *   (walking, running, on_foot, on_bicycle, in_vehicle) at or above the confidence threshold lasts for
 *   `motionTriggerDelay` (a confident `still` cancels the pending trigger). A fix that is certainly outside but
 *   coarser than the threshold changes nothing. A fix that is not certainly outside and is more accurate than the
 *   anchor replaces it. (The engine also leaves STATIONARY on the EXIT of its OS stationary region, through
 *   [force].)
 * - **MOVING**: stop detection (unless disabled) keeps the stop-timeout timer running. Every piece of evidence
 *   of motion restarts it: a fix more than `max(stationaryRadius, accuracy)` from the last such fix, or a
 *   confident moving activity. A confident `still` or a fix without displacement only makes sure it is armed,
 *   so the device becomes STATIONARY `stopTimeout` after the last evidence of motion, even when no more
 *   fixes arrive.
 */
internal class MotionStateMachine(settings: MotionSettings = MotionSettings()) {
    var settings: MotionSettings = settings
        private set

    var state: MotionState = MotionState.STATIONARY
        private set

    val isMoving: Boolean get() = state == MotionState.MOVING

    /** STATIONARY: the reference point of the stationary radius (null until the first fix). */
    var anchor: TrackedLocation? = null
        private set

    /** MOVING: the last fix that showed displacement. */
    var motionAnchor: TrackedLocation? = null
        private set

    var isStopTimerArmed: Boolean = false
        private set

    var isMotionTriggerArmed: Boolean = false
        private set

    /** Forgets everything and starts over in [state]. The caller cancels its timers. */
    fun reset(state: MotionState = MotionState.STATIONARY, anchor: TrackedLocation? = null) {
        this.state = state
        this.anchor = if (state == MotionState.STATIONARY) anchor else null
        motionAnchor = if (state == MotionState.MOVING) anchor else null
        isStopTimerArmed = false
        isMotionTriggerArmed = false
    }

    /** STATIONARY without an anchor: [location] becomes the anchor. */
    fun offerAnchor(location: TrackedLocation?) {
        if (state == MotionState.STATIONARY && anchor == null && location != null) anchor = location
    }

    /** STATIONARY: [location] becomes the anchor, replacing any earlier one (the fresh initial fix after start). */
    fun setAnchor(location: TrackedLocation) {
        if (state == MotionState.STATIONARY) anchor = location
    }

    /**
     * Applies new settings. MOVING: may arm, cancel or restart the stop timer. STATIONARY: a pending motion
     * trigger is cancelled when its delay changed, so the next moving activity starts over with the new delay.
     */
    fun updateSettings(newSettings: MotionSettings): List<MotionAction> {
        val old = settings
        settings = newSettings
        if (state != MotionState.MOVING) {
            if (!isMotionTriggerArmed || newSettings.motionTriggerDelayMs == old.motionTriggerDelayMs) return emptyList()
            isMotionTriggerArmed = false
            return listOf(MotionAction.CancelMotionTrigger)
        }
        return when {
            newSettings.disableStopDetection -> cancelStopTimer()
            !isStopTimerArmed || newSettings.stopTimeoutMs != old.stopTimeoutMs -> restartStopTimer()
            else -> emptyList()
        }
    }

    /** An accepted fix. */
    fun onLocation(fix: TrackedLocation): List<MotionAction> = when (state) {
        MotionState.STATIONARY -> {
            val current = anchor
            when {
                current == null -> {
                    anchor = fix
                    emptyList()
                }
                isCertainlyOutside(current, fix) -> if (isAccurateEnough(fix)) enterMoving(fix) else emptyList()
                else -> {
                    if (fix.accuracyMeters < current.accuracyMeters) anchor = fix
                    emptyList()
                }
            }
        }
        MotionState.MOVING -> {
            val reference = motionAnchor
            when {
                reference == null -> {
                    motionAnchor = fix
                    ensureStopTimer()
                }
                distanceMeters(reference, fix) > max(settings.stationaryRadius, fix.accuracyMeters) -> {
                    motionAnchor = fix
                    restartStopTimer()
                }
                else -> ensureStopTimer()
            }
        }
    }

    /** An activity sample; samples below the confidence threshold are ignored. */
    fun onActivity(sample: ActivitySample): List<MotionAction> {
        if (sample.confidence < settings.minimumConfidence) return emptyList()
        val moving = sample.type in MOVING_ACTIVITIES
        val still = sample.type == ActivityType.STILL
        return when (state) {
            MotionState.STATIONARY -> when {
                moving && settings.motionTriggerDelayMs <= 0 -> enterMoving(null)
                moving && !isMotionTriggerArmed -> {
                    isMotionTriggerArmed = true
                    listOf(MotionAction.StartMotionTrigger(settings.motionTriggerDelayMs))
                }
                still && isMotionTriggerArmed -> {
                    isMotionTriggerArmed = false
                    listOf(MotionAction.CancelMotionTrigger)
                }
                else -> emptyList()
            }
            MotionState.MOVING -> when {
                moving -> restartStopTimer()
                still -> ensureStopTimer()
                else -> emptyList()
            }
        }
    }

    /** The motion-trigger timer fired. */
    fun onMotionTriggerElapsed(): List<MotionAction> {
        val wasArmed = isMotionTriggerArmed
        isMotionTriggerArmed = false
        return if (wasArmed && state == MotionState.STATIONARY) enterMoving(null) else emptyList()
    }

    /** The stop-timeout timer fired; [location] (the best known fix) becomes the stationary anchor. */
    fun onStopTimeout(location: TrackedLocation?): List<MotionAction> {
        val wasArmed = isStopTimerArmed
        isStopTimerArmed = false
        return if (wasArmed && state == MotionState.MOVING) enterStationary(location, automatic = true) else emptyList()
    }

    /** `changePace`: forces a transition; [location] is the best known fix. */
    fun force(isMoving: Boolean, location: TrackedLocation?): List<MotionAction> = when {
        isMoving && state == MotionState.MOVING -> restartStopTimer()
        isMoving -> enterMoving(location)
        state == MotionState.MOVING -> enterStationary(location, automatic = false)
        else -> emptyList()
    }

    /** True if [fix] lies outside the stationary radius around [anchor] even at the far edge of its accuracy. */
    private fun isCertainlyOutside(anchor: TrackedLocation, fix: TrackedLocation): Boolean =
        distanceMeters(anchor, fix) - fix.accuracyMeters > settings.stationaryRadius

    /**
     * False for a fix coarser than `trackingAccuracyThreshold`: such a fix never leaves STATIONARY by itself. (The
     * engine's location processor already rejects such fixes; this keeps the rule of architecture round 2, §3 true
     * for any processor.)
     */
    fun isAccurateEnough(fix: TrackedLocation): Boolean {
        val threshold = settings.trackingAccuracyThreshold
        return threshold <= 0.0 || fix.accuracyMeters <= threshold
    }

    private fun enterMoving(location: TrackedLocation?): List<MotionAction> {
        val actions = ArrayList<MotionAction>(3)
        if (isMotionTriggerArmed) {
            isMotionTriggerArmed = false
            actions += MotionAction.CancelMotionTrigger
        }
        state = MotionState.MOVING
        anchor = null
        motionAnchor = location
        actions += MotionAction.EnterMoving(location)
        actions += restartStopTimer()
        return actions
    }

    private fun enterStationary(location: TrackedLocation?, automatic: Boolean): List<MotionAction> {
        val actions = ArrayList<MotionAction>(3)
        actions += cancelStopTimer()
        if (isMotionTriggerArmed) {
            isMotionTriggerArmed = false
            actions += MotionAction.CancelMotionTrigger
        }
        state = MotionState.STATIONARY
        motionAnchor = null
        anchor = location
        actions += MotionAction.EnterStationary(location, automatic)
        return actions
    }

    private fun restartStopTimer(): List<MotionAction> {
        if (settings.disableStopDetection) return emptyList()
        isStopTimerArmed = true
        return listOf(MotionAction.StartStopTimer(settings.stopTimeoutMs))
    }

    private fun ensureStopTimer(): List<MotionAction> =
        if (isStopTimerArmed) emptyList() else restartStopTimer()

    private fun cancelStopTimer(): List<MotionAction> {
        if (!isStopTimerArmed) return emptyList()
        isStopTimerArmed = false
        return listOf(MotionAction.CancelStopTimer)
    }

    companion object {
        /** Activities that count as motion. */
        val MOVING_ACTIVITIES: Set<ActivityType> = setOf(
            ActivityType.WALKING,
            ActivityType.RUNNING,
            ActivityType.ON_FOOT,
            ActivityType.ON_BICYCLE,
            ActivityType.IN_VEHICLE,
        )
    }
}
