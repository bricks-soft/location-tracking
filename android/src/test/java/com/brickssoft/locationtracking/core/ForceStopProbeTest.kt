package com.brickssoft.locationtracking.core

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(RobolectricTestRunner::class)
class ForceStopProbeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Adds an exit record; Android and Robolectric list the newest first, so add them oldest first. */
    private fun exit(processName: String, reason: Int, pid: Int) {
        val manager = app.getSystemService(ActivityManager::class.java)
        shadowOf(manager).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(processName)
                .setReason(reason)
                .setPid(pid)
                .setTimestamp(pid.toLong())
                .build(),
        )
    }

    @Test
    fun `a force stop of the main process is found behind a newer WebView renderer exit`() {
        exit(app.packageName, ApplicationExitInfo.REASON_USER_REQUESTED, pid = 100)
        exit(WEBVIEW_RENDERER, ApplicationExitInfo.REASON_OTHER, pid = 101)

        assertTrue(ForceStopProbe.system(app).startedAfterForceStop())
    }

    @Test
    fun `a main process killed by a signal after an older force stop is not a force stop`() {
        exit(app.packageName, ApplicationExitInfo.REASON_USER_REQUESTED, pid = 100)
        exit(app.packageName, ApplicationExitInfo.REASON_SIGNALED, pid = 200)
        exit(WEBVIEW_RENDERER, ApplicationExitInfo.REASON_USER_REQUESTED, pid = 201)

        assertFalse(ForceStopProbe.system(app).startedAfterForceStop())
    }

    @Test
    fun `no exit record of the main process is not a force stop`() {
        exit(WEBVIEW_RENDERER, ApplicationExitInfo.REASON_USER_REQUESTED, pid = 101)

        assertFalse(ForceStopProbe.system(app).startedAfterForceStop())
    }

    @Test
    fun `the answer is read once per process`() {
        val probe = ForceStopProbe.system(app)
        assertFalse(probe.startedAfterForceStop())

        exit(app.packageName, ApplicationExitInfo.REASON_USER_REQUESTED, pid = 100)

        assertFalse(probe.startedAfterForceStop())
    }

    @Test
    @Config(sdk = [29])
    fun `Android 10 has no exit records and never reports a force stop`() {
        assertFalse(ForceStopProbe.system(app).startedAfterForceStop())
    }

    private companion object {
        const val WEBVIEW_RENDERER =
            "com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0"
    }
}
