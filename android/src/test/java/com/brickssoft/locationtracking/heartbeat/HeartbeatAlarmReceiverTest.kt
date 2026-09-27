package com.brickssoft.locationtracking.heartbeat

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.engine.TrackingEngine
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeHeartbeatScheduler
import com.brickssoft.locationtracking.testing.FakeServiceController
import com.brickssoft.locationtracking.testing.FakeTrackingEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeartbeatAlarmReceiverTest {
    private val heartbeat = FakeHeartbeatScheduler()
    private val configStore = FakeConfigStore(runtime = RuntimeState(enabled = true))
    private val service = FakeServiceController()
    private val engine = FakeTrackingEngine(configStore)
    private var engineTouched = false
    private val engineLazy: Lazy<TrackingEngine> = lazy {
        engineTouched = true
        engine
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private suspend fun deliver(trigger: HeartbeatTrigger = HeartbeatTrigger.BACKUP_ALARM) =
        HeartbeatAlarmReceiver.deliver(trigger, heartbeat, configStore, lazyOf(service), engineLazy)

    @Test
    fun `runs the heartbeat, then restores tracking when the service is gone`() = runTest {
        deliver(HeartbeatTrigger.EXACT_ALARM)

        assertEquals(listOf(HeartbeatTrigger.EXACT_ALARM), heartbeat.alarms)
        assertEquals(listOf("restore"), engine.restoreReasons)
    }

    @Test
    fun `no restore while the service is running`() = runTest {
        service.isRunning = true

        deliver()

        assertEquals(listOf(HeartbeatTrigger.BACKUP_ALARM), heartbeat.alarms)
        assertFalse(engineTouched)
    }

    @Test
    fun `no restore while tracking is disabled`() = runTest {
        configStore.runtimeFlow.value = RuntimeState(enabled = false)

        deliver()

        assertEquals(1, heartbeat.alarms.size)
        assertFalse(engineTouched)
    }

    @Test
    fun `a failing heartbeat still restores`() = runTest {
        val failing = object : HeartbeatScheduler by heartbeat {
            override suspend fun onAlarm(trigger: HeartbeatTrigger) = throw IllegalStateException("boom")
        }

        HeartbeatAlarmReceiver.deliver(HeartbeatTrigger.BACKUP_ALARM, failing, configStore, lazyOf(service), engineLazy)

        assertEquals(listOf("restore"), engine.restoreReasons)
    }

    @Test
    fun `a failing restore is swallowed`() = runTest {
        engine.failWith = TrackingException(ErrorCode.PERMISSION_DENIED, "no permission")

        deliver()

        assertEquals(listOf("restore"), engine.calls)
    }

    @Test
    fun `trigger comes from the intent extra and defaults to the backup alarm`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for (trigger in HeartbeatTrigger.entries) {
            assertEquals(trigger, HeartbeatIntents.triggerOf(HeartbeatIntents.intent(app, trigger)))
        }
        assertEquals(HeartbeatTrigger.BACKUP_ALARM, HeartbeatIntents.triggerOf(Intent(Constants.ACTION_HEARTBEAT)))
        assertEquals(
            HeartbeatTrigger.BACKUP_ALARM,
            HeartbeatIntents.triggerOf(Intent().putExtra(HeartbeatIntents.EXTRA_TRIGGER, "NOPE")),
        )
        assertEquals(HeartbeatTrigger.BACKUP_ALARM, HeartbeatIntents.triggerOf(null))
    }

    @Test
    fun `the heartbeat intent is explicit`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        val intent = HeartbeatIntents.intent(app, HeartbeatTrigger.EXACT_ALARM)

        assertEquals(Constants.ACTION_HEARTBEAT, intent.action)
        assertEquals(HeartbeatAlarmReceiver::class.java.name, intent.component?.className)
        assertEquals(app.packageName, intent.component?.packageName)
    }

    @Test
    fun `other actions are ignored`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        HeartbeatAlarmReceiver().onReceive(app, Intent("com.example.OTHER"))

        assertTrue(heartbeat.alarms.isEmpty())
    }
}
