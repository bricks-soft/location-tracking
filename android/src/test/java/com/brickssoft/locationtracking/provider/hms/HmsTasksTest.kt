package com.brickssoft.locationtracking.provider.hms

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.huawei.hmf.tasks.CancellationTokenSource
import com.huawei.hmf.tasks.TaskCompletionSource
import com.huawei.hmf.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HmsTasksTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `await returns the result of a task completed later`() = runTest {
        val source = TaskCompletionSource<String>()
        val result = async { source.task.await() }
        runCurrent()
        assertFalse(result.isCompleted)

        source.setResult("fix")

        assertEquals("fix", result.await())
    }

    @Test
    fun `await returns the result of an already completed task, including null`() = runTest {
        assertEquals(42, Tasks.fromResult(42).await())
        assertNull(Tasks.fromResult<String?>(null).await())
    }

    @Test
    fun `await rethrows the failure of the task unchanged`() = runTest {
        val error = Hms.apiException(10803)
        val source = TaskCompletionSource<String>()
        val result = async { runCatching { source.task.await() } }
        runCurrent()

        source.setException(error)

        assertSame(error, result.await().exceptionOrNull())
    }

    @Test
    fun `a task canceled by HMS fails without cancelling the caller`() = runTest {
        val tokens = CancellationTokenSource()
        val source = TaskCompletionSource<String>(tokens.token)
        val result = async { runCatching { source.task.await() } }
        runCurrent()

        tokens.cancel()

        val error = result.await().exceptionOrNull()
        assertTrue(error is HmsTaskCanceledException)
        assertFalse(error is CancellationException)
        assertFalse(result.isCancelled)
        Hms.expectTrackingError(ErrorCode.UNAVAILABLE) { hmsCall("op") { Tasks.fromCanceled<String>().await() } }
    }

    @Test
    fun `cancelling the caller stops waiting and ignores a late result`() = runTest {
        val source = TaskCompletionSource<String>()
        val result = async { source.task.await() }
        runCurrent()

        result.cancel()
        runCurrent()
        source.setResult("late")

        assertTrue(result.isCancelled)
    }

    @Test
    fun `hmsCall maps failures but lets cancellation through`() = runTest {
        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { hmsCall("op") { throw SecurityException("no") } }
        Hms.expectTrackingError(ErrorCode.UNAVAILABLE) { hmsCall("op") { throw IOException("down") } }
        val original = TrackingException(ErrorCode.TIMEOUT, "t")
        assertSame(original, Hms.expectTrackingError(ErrorCode.TIMEOUT) { hmsCall("op") { throw original } })
        try {
            hmsCall("op") { throw CancellationException("stop") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
        assertEquals("ok", hmsCall("op") { "ok" })
    }
}
