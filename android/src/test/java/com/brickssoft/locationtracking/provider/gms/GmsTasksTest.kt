package com.brickssoft.locationtracking.provider.gms

import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
class GmsTasksTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `completed successful task returns its result`() = runTest {
        assertEquals("fix", Tasks.forResult("fix").await())
        assertNull(Tasks.forResult<String?>(null).await())
    }

    @Test
    fun `completed failed task rethrows the same exception`() = runTest {
        val error = IOException("boom")
        try {
            Tasks.forException<String>(error).await()
            fail("expected exception")
        } catch (e: IOException) {
            assertSame(error, e)
        }
    }

    @Test
    fun `completed cancelled task throws CancellationException`() = runTest {
        val result = runCatching { Tasks.forCanceled<String>().await() }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `pending task resumes when it succeeds later`() = runTest {
        val source = TaskCompletionSource<String>()
        val deferred = async { source.task.await() }
        runCurrent()
        assertFalse(deferred.isCompleted)

        source.setResult("later")

        assertEquals("later", deferred.await())
    }

    @Test
    fun `pending task rethrows when it fails later`() = runTest {
        val source = TaskCompletionSource<String>()
        val error = SecurityException("no permission")
        val deferred = async { runCatching { source.task.await() } }
        runCurrent()

        source.setException(error)

        // Coroutine stack-trace recovery may hand back a copy; type and message are preserved.
        val thrown = deferred.await().exceptionOrNull()
        assertTrue(thrown is SecurityException)
        assertEquals("no permission", thrown!!.message)
    }

    @Test
    fun `cancelling the coroutine cancels the token source`() = runTest {
        val cancellation = CancellationTokenSource()
        val source = TaskCompletionSource<String>()
        val deferred = async { source.task.await(cancellation) }
        runCurrent()
        assertFalse(cancellation.token.isCancellationRequested)

        deferred.cancel()
        advanceUntilIdle()

        assertTrue(cancellation.token.isCancellationRequested)
        assertTrue(deferred.isCancelled)
        // A late completion after cancellation is ignored.
        source.setResult("too late")
    }

    @Test
    fun `timeout around await returns null and cancels the token`() = runTest {
        val cancellation = CancellationTokenSource()
        val never = TaskCompletionSource<String>().task

        val result = withTimeoutOrNull(5_000) { never.await(cancellation) }

        assertNull(result)
        assertTrue(cancellation.token.isCancellationRequested)
    }

    @Test
    fun `logFailure logs a failed fire-and-forget task`() {
        val sink = RecordingLogSink()
        Logger.sink = sink

        Tasks.forException<Void>(IllegalStateException("gms down")).logFailure("LT.Test", "request")
        voidTask().logFailure("LT.Test", "ok")
        null.logFailure("LT.Test", "null task")

        val warnings = sink.at(LogLevel.WARN)
        assertEquals(1, warnings.size)
        assertEquals("request failed", warnings.single().message)
    }
}
