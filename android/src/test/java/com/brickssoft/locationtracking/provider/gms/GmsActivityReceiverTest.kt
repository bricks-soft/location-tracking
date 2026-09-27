package com.brickssoft.locationtracking.provider.gms

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeTrackingEngine
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GmsActivityReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `maps every GMS activity type`() {
        val expected = mapOf(
            DetectedActivity.IN_VEHICLE to ActivityType.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE to ActivityType.ON_BICYCLE,
            DetectedActivity.ON_FOOT to ActivityType.ON_FOOT,
            DetectedActivity.WALKING to ActivityType.WALKING,
            DetectedActivity.RUNNING to ActivityType.RUNNING,
            DetectedActivity.STILL to ActivityType.STILL,
            DetectedActivity.TILTING to ActivityType.UNKNOWN,
            DetectedActivity.UNKNOWN to ActivityType.UNKNOWN,
            99 to ActivityType.UNKNOWN,
        )
        for ((gms, type) in expected) {
            assertEquals("GMS type $gms", type, GmsActivityReceiver.mapActivityType(gms))
        }
    }

    @Test
    fun `parses a real ActivityRecognitionResult, most probable first`() {
        val intent = resultIntent(
            DetectedActivity(DetectedActivity.ON_FOOT, 40),
            DetectedActivity(DetectedActivity.IN_VEHICLE, 85),
            DetectedActivity(DetectedActivity.WALKING, 38),
        )

        val samples = GmsActivityReceiver.parseActivitySamples(intent)

        assertEquals(
            listOf(
                ActivitySample(ActivityType.IN_VEHICLE, 85),
                ActivitySample(ActivityType.ON_FOOT, 40),
                ActivitySample(ActivityType.WALKING, 38),
            ),
            samples,
        )
    }

    @Test
    fun `parses a result serialized as bytes`() {
        val result = ActivityRecognitionResult(listOf(DetectedActivity(DetectedActivity.STILL, 100)), 1_000L, 2_000L)
        val intent = Intent().putExtra(EXTRA_ACTIVITY_RESULT, SafeParcelableSerializer.serializeToBytes(result))

        assertEquals(listOf(ActivitySample(ActivityType.STILL, 100)), GmsActivityReceiver.parseActivitySamples(intent))
    }

    @Test
    fun `tilting and unknown collapse into one unknown sample with the highest confidence`() {
        val samples = GmsActivityReceiver.toActivitySamples(
            listOf(
                DetectedActivity(DetectedActivity.TILTING, 30),
                DetectedActivity(DetectedActivity.STILL, 50),
                DetectedActivity(DetectedActivity.UNKNOWN, 20),
            ),
        )

        assertEquals(listOf(ActivitySample(ActivityType.STILL, 50), ActivitySample(ActivityType.UNKNOWN, 30)), samples)
    }

    @Test
    fun `intent without a result parses to nothing`() {
        assertTrue(GmsActivityReceiver.parseActivitySamples(Intent("something")).isEmpty())
        assertTrue(GmsActivityReceiver.parseActivitySamples(null).isEmpty())
    }

    @Test
    fun `onReceive delivers the samples to the activity sink`() {
        val scope = TestScope(StandardTestDispatcher())
        val engine = FakeTrackingEngine(FakeConfigStore())
        val receiver = GmsActivityReceiver { ReceiverTarget(scope, engine) }

        receiver.onReceive(app, resultIntent(DetectedActivity(DetectedActivity.RUNNING, 90)))
        scope.advanceUntilIdle()

        assertEquals(listOf("onActivitySamples"), engine.calls.toList())
        assertEquals(listOf(ActivitySample(ActivityType.RUNNING, 90)), engine.activitySamples.toList())
    }

    @Test
    fun `onReceive ignores intents without a result and does not resolve the target`() {
        var resolved = false
        val receiver = GmsActivityReceiver {
            resolved = true
            error("must not be called")
        }

        receiver.onReceive(app, Intent("unrelated"))

        assertTrue(!resolved)
    }

    @Test
    fun `onReceive survives a failing target`() {
        val receiver = GmsActivityReceiver { throw IllegalStateException("components unavailable") }

        receiver.onReceive(app, resultIntent(DetectedActivity(DetectedActivity.STILL, 90)))
    }

    private fun resultIntent(vararg activities: DetectedActivity): Intent =
        Intent().putExtra(EXTRA_ACTIVITY_RESULT, ActivityRecognitionResult(activities.toList(), 1_000L, 2_000L))

    private companion object {
        /** The extra under which GMS delivers an `ActivityRecognitionResult`. */
        const val EXTRA_ACTIVITY_RESULT = "com.google.android.location.internal.EXTRA_ACTIVITY_RESULT"
    }
}
