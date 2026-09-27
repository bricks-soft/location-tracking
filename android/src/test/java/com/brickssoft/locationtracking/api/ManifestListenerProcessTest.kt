package com.brickssoft.locationtracking.api

import android.app.Application
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.SharedPrefsConfigStore
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.SystemClockImpl
import com.brickssoft.locationtracking.heartbeat.HeartbeatIntents
import com.brickssoft.locationtracking.heartbeat.HeartbeatTrigger
import com.brickssoft.locationtracking.model.EventJson
import com.brickssoft.locationtracking.testing.Fixtures
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * A process that Android starts only for the heartbeat alarm: no plugin, no bridge, no WebView, and no [Components]
 * until the alarm's receiver creates them. The manifest listener must be created by that receiver's
 * `Components.get()` and receive the heartbeat record the alarm produces (F-09 on a device).
 */
@RunWith(RobolectricTestRunner::class)
class ManifestListenerProcessTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        Components.reset()
        NativeListeners.resetForTests()
        ListenerLog.clear()
        RecordingListener.created.set(0)
    }

    @After
    fun tearDown() {
        NativeListeners.resetForTests()
        Components.reset()
        Logger.sink = null
    }

    @Test
    fun `a process started by the heartbeat alarm delivers the heartbeat record to the manifest listener`() {
        setMetaData(app, mapOf(LocationTrackingNative.LISTENER_META_DATA to RecordingListener::class.java.name))
        // What the dead process left behind: tracking enabled, the last record 10 minutes ago, a known location.
        // (Written through the config store directly: this test process has no Components yet.)
        val now = System.currentTimeMillis()
        val lastFix = Fixtures.location(time = now - 10 * MINUTE, elapsedRealtimeNanos = 0L)
        SharedPrefsConfigStore(app, SystemClockImpl(app)).apply {
            ready(JSONObject("""{"heartbeat":{"enabled":true,"minInterval":180,"maxInterval":300}}"""), true)
            updateRuntime {
                it.copy(
                    enabled = true,
                    trackingStartedAt = now - 30 * MINUTE,
                    lastRecordAt = now - 10 * MINUTE,
                    lastRecordElapsed = null,
                    lastRecordBootCount = null,
                    lastLocation = lastFix,
                )
            }
        }
        assertEquals(0, RecordingListener.created.get())

        // The alarm's explicit broadcast reaches HeartbeatAlarmReceiver, which creates Components.
        app.sendBroadcast(HeartbeatIntents.intent(app, HeartbeatTrigger.BACKUP_ALARM))
        shadowOf(Looper.getMainLooper()).idle()

        // The heartbeat, then the restore (no location permission here, so it ends with tracking_stop).
        waitFor("tracking_stop") { ListenerLog.keys(RecordingListener.NAME).contains("record:tracking_stop") }
        assertTrue(NativeThread.awaitIdle())
        assertEquals(1, RecordingListener.created.get())
        val calls = ListenerLog.of(RecordingListener.NAME)
        val keys = calls.map { "${it.kind}:${it.name}" }
        assertEquals("record:heartbeat", keys.first())
        assertTrue(keys.indexOf("event:${EventJson.HEARTBEAT}") > 0)
        val heartbeat = calls.first().json
        assertEquals(lastFix.latitude, heartbeat.getJSONObject("coords").getDouble("latitude"), 1e-9)
        assertFalse(heartbeat.has("sent_at"))
        val stop = calls.single { it.kind == "record" && it.name == "tracking_stop" }.json
        assertEquals("permission_denied", stop.getString("reason"))
        assertTrue(calls.all { it.thread == LocationTrackingNative.THREAD_NAME })
        val errors = ShadowLog.getLogs().filter { it.type == Log.ERROR && it.tag.startsWith("LT") }
        assertTrue("errors were logged: ${errors.joinToString("\n") { "${it.tag}: ${it.msg} ${it.throwable}" }}", errors.isEmpty())
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("timed out waiting for $what; the listener saw ${ListenerLog.calls}")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
