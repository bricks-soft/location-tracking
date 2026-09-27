package com.brickssoft.locationtracking.provider.hms

import android.content.BroadcastReceiver
import android.content.Intent
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.testing.FakeTrackingEngine
import com.huawei.hms.location.ActivityIdentificationData
import com.huawei.hms.location.ActivityIdentificationResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HmsActivityReceiverTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `every HMS activity type maps to an activity type`() {
        val expected = mapOf(
            ActivityIdentificationData.VEHICLE to ActivityType.IN_VEHICLE,
            ActivityIdentificationData.BIKE to ActivityType.ON_BICYCLE,
            ActivityIdentificationData.FOOT to ActivityType.ON_FOOT,
            ActivityIdentificationData.WALKING to ActivityType.WALKING,
            ActivityIdentificationData.RUNNING to ActivityType.RUNNING,
            ActivityIdentificationData.STILL to ActivityType.STILL,
            ActivityIdentificationData.OTHERS to ActivityType.UNKNOWN,
            999 to ActivityType.UNKNOWN,
        )
        for ((hms, type) in expected) assertEquals("type $hms", type, HmsActivityReceiver.activityType(hms))
    }

    @Test
    fun `parses a real HMS result, most probable first`() {
        val response = ActivityIdentificationResponse(
            listOf(
                ActivityIdentificationData(ActivityIdentificationData.STILL, 20),
                ActivityIdentificationData(ActivityIdentificationData.VEHICLE, 75),
                ActivityIdentificationData(ActivityIdentificationData.WALKING, 5),
            ),
            1_790_417_730_000L,
            86_400_000L,
        )
        val intent = Intent().putExtra(EXTRA_ACTIVITY_RESULT, response)

        val samples = HmsActivityReceiver.parseActivitySamples(intent)

        assertEquals(
            listOf(
                ActivitySample(ActivityType.IN_VEHICLE, 75),
                ActivitySample(ActivityType.STILL, 20),
                ActivitySample(ActivityType.WALKING, 5),
            ),
            samples,
        )
    }

    @Test
    fun `confidence is clamped to 0-100`() {
        val tooHigh = mockk<ActivityIdentificationData> {
            every { identificationActivity } returns ActivityIdentificationData.RUNNING
            every { possibility } returns 150
        }
        val negative = mockk<ActivityIdentificationData> {
            every { identificationActivity } returns ActivityIdentificationData.BIKE
            every { possibility } returns -3
        }

        assertEquals(ActivitySample(ActivityType.RUNNING, 100), HmsActivityReceiver.toActivitySample(tooHigh))
        assertEquals(ActivitySample(ActivityType.ON_BICYCLE, 0), HmsActivityReceiver.toActivitySample(negative))
    }

    @Test
    fun `intents without an HMS result yield nothing`() {
        assertTrue(HmsActivityReceiver.parseActivitySamples(null).isEmpty())
        assertTrue(HmsActivityReceiver.parseActivitySamples(Intent()).isEmpty())
        assertTrue(HmsActivityReceiver.parseActivitySamples(Intent().putExtra(EXTRA_ACTIVITY_RESULT, "junk")).isEmpty())
    }

    @Test
    fun `delivery hands samples to the engine and finishes the broadcast`() = runTest {
        val engine = FakeTrackingEngine()
        val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        val samples = listOf(ActivitySample(ActivityType.WALKING, 80))

        deliverAsync(pending, this, "test", "samples") { engine.onActivitySamples(samples) }
        advanceUntilIdle()

        assertEquals(samples, engine.activitySamples)
        verify(exactly = 1) { pending.finish() }
    }

    @Test
    fun `delivery finishes the broadcast when the engine fails`() = runTest {
        val engine = FakeTrackingEngine().apply { failWith = TrackingException(ErrorCode.INTERNAL, "boom") }
        val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)

        deliverAsync(pending, this, "test", "samples") { engine.onActivitySamples(emptyList()) }
        advanceUntilIdle()

        verify(exactly = 1) { pending.finish() }
    }

    private companion object {
        /** Extra key read by `ActivityIdentificationResponse.getDataFromIntent` (HMS 6.12). */
        const val EXTRA_ACTIVITY_RESULT = "com.huawei.hms.location.internal.EXTRA_ACTIVITY_RESULT"
    }
}
