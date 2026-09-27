package com.brickssoft.locationtracking.provider.hms

import android.location.Location
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import com.huawei.hmf.tasks.Task
import com.huawei.hmf.tasks.Tasks
import com.huawei.hms.common.ApiException
import com.huawei.hms.location.LocationResult
import com.huawei.hms.support.api.client.Status
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** Test helpers for the HMS backends. */
internal object Hms {
    fun <T> done(value: T): Task<T> = Tasks.fromResult(value)

    fun <T> failed(e: Exception): Task<T> = Tasks.fromException(e)

    fun apiException(statusCode: Int): ApiException = ApiException(Status(statusCode, "status $statusCode"))

    /** A [LocationResult] whose `getLocations()` returns [locations]. */
    fun result(vararg locations: Location): LocationResult = mockk {
        every { this@mockk.locations } returns locations.toList()
    }

    fun location(
        latitude: Double = 24.7136,
        longitude: Double = 46.6753,
        accuracy: Float = 5.2f,
        time: Long = 1_790_417_730_123L,
    ): Location = Location("fused").apply {
        this.latitude = latitude
        this.longitude = longitude
        this.accuracy = accuracy
        this.time = time
    }

    /** Runs [block] and asserts that it throws a [TrackingException] with [code]. */
    suspend fun expectTrackingError(code: ErrorCode, block: suspend () -> Unit): TrackingException {
        try {
            block()
        } catch (e: TrackingException) {
            assertEquals(code, e.code)
            return e
        }
        fail("expected TrackingException($code)")
        throw AssertionError()
    }
}
