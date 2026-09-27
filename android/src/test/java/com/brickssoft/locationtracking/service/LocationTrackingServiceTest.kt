package com.brickssoft.locationtracking.service

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.R
import com.brickssoft.locationtracking.config.Config as TrackingConfig
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.NotificationActionButton
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.config.NotificationPriority
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.TrackingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LocationTrackingServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = app.getSystemService(NotificationManager::class.java)
    private val env = ServiceTestEnv()
    private val controllers = mutableListOf<ServiceController<LocationTrackingService>>()

    @Before
    fun setUp() {
        env.install()
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        env.uninstall()
        Components.reset()
    }

    /** A created service; its intent is plain (not recorded in [ServiceCommands]) unless [intent] is given. */
    private fun create(intent: Intent = Intent(app, LocationTrackingService::class.java)) =
        Robolectric.buildService(LocationTrackingService::class.java, intent).create().also { controllers += it }

    /** A created service that handled one start command sent by this process (start id 1). */
    private fun started(): ServiceController<LocationTrackingService> = create(ownStart(app)).startCommand(0, 1)

    private fun posted() = shadowOf(notificationManager).getNotification(Constants.NOTIFICATION_ID)

    @Test
    fun `start command enters the foreground at once, then shows the configured notification`() {
        env.configStore.configFlow.value = TrackingConfig(
            notification = NotificationConfig(
                title = "Tracking",
                text = "On duty",
                smallIcon = "drawable/lt_ic_notification",
                color = "#3366FF",
                channelId = "trips",
                channelName = "Trips",
                actions = listOf(NotificationActionButton("pause", "Pause"), NotificationActionButton("stop", "Stop")),
            ),
        )

        val controller = started()

        // Before any background step: foreground with the initial notification, nothing loaded.
        val service = controller.get()
        val shadow = shadowOf(service)
        assertEquals(Constants.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
        val initial = shadow.lastForegroundNotification
        assertEquals(NotificationConfig.DEFAULT_CHANNEL_ID, initial.channelId)
        assertEquals(app.getString(R.string.lt_notification_text), initial.text)
        assertTrue(LocationTrackingService.isRunning)
        assertEquals(0, env.depsLookups.get())

        env.runPending()

        val n = posted()
        assertEquals("trips", n.channelId)
        assertEquals("Trips", notificationManager.getNotificationChannel("trips").name.toString())
        assertEquals("Tracking", n.title)
        assertEquals("On duty", n.text)
        assertEquals(R.drawable.lt_ic_notification, n.smallIcon.resId)
        assertEquals(Color.parseColor("#3366FF"), n.color)
        assertEquals(listOf("Pause", "Stop"), n.actions.map { it.title.toString() })
        assertFalse(shadow.isStoppedBySelf)
        assertTrue("a start sent by this process does not call the engine", env.engine.calls.isEmpty())
    }

    @Test
    fun `the first notification uses the channel and texts carried by the start command`() {
        val configured = NotificationConfig(
            title = "Field Force",
            text = "Shift tracking is on",
            priority = NotificationPriority.HIGH,
            channelId = "shift",
            channelName = "Shift",
        )
        env.configStore.configFlow.value = TrackingConfig(notification = configured)
        val start = ServiceCommands.putNotification(ownStart(app), configured)
        val service = create(start).get()

        service.onStartCommand(start, 0, 1)

        val first = shadowOf(service).lastForegroundNotification
        assertEquals("shift", first.channelId)
        assertEquals("Field Force", first.title)
        assertEquals("Shift tracking is on", first.text)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, notificationManager.getNotificationChannel("shift").importance)
        assertNull("no default channel", notificationManager.getNotificationChannel(NotificationConfig.DEFAULT_CHANNEL_ID))
        assertEquals(0, env.depsLookups.get())

        env.runPending()
        assertEquals("the same content is not posted again", 0, env.logs.lines.count { it.message == "notification updated" })
    }

    @Test
    fun `startForeground runs before any dependency is loaded`() {
        env.configStore.runtimeFlow.value = RuntimeState(enabled = true)
        var foregroundAtLookup: Boolean? = null
        env.onDepsLookup = { if (foregroundAtLookup == null) foregroundAtLookup = LocationTrackingService.isRunning }
        val service = create().get()

        // Null intent: the cold START_STICKY restart, the path where the config was loaded first in round 1.
        service.onStartCommand(null, Service.START_FLAG_RETRY, 1)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals("nothing is loaded on the main thread", 0, env.depsLookups.get())

        env.runPending()

        assertEquals(true, foregroundAtLookup)
        assertEquals(listOf("restore"), env.engine.restoreReasons)
    }

    @Test
    fun `start commands return START_STICKY`() {
        val service = create().get()

        assertEquals(Service.START_STICKY, service.onStartCommand(ownStart(app), 0, 1))
        assertEquals(Service.START_STICKY, service.onStartCommand(ownStart(app), 0, 2))
        env.runPending()
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a second start passes the notification already shown`() {
        env.configStore.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "Shift"))
        val service = started().get()
        env.runPending()
        assertEquals("Shift", posted().title)

        service.onStartCommand(ownStart(app), 0, 2)

        assertEquals("no flash of the default notification", "Shift", shadowOf(service).lastForegroundNotification.title)
    }

    @Test
    fun `isRunning is true from startForeground until destroy`() {
        assertFalse(LocationTrackingService.isRunning)

        val controller = create(ownStart(app))
        assertFalse("onCreate alone is not the foreground", LocationTrackingService.isRunning)

        controller.startCommand(0, 1)
        assertTrue(LocationTrackingService.isRunning)

        controller.destroy()
        controllers.remove(controller)
        assertFalse(LocationTrackingService.isRunning)
        assertNull(posted())
    }

    @Test
    fun `system restart while enabled restores tracking`() {
        env.configStore.runtimeFlow.value = RuntimeState(enabled = true)
        val service = create().get()

        val result = service.onStartCommand(null, Service.START_FLAG_RETRY, 1)

        assertEquals(Service.START_STICKY, result)
        assertNotNull("foreground first", shadowOf(service).lastForegroundNotification)
        env.runPending()
        assertEquals(listOf("restore"), env.engine.restoreReasons)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `system restart while disabled stops without touching the engine`() {
        env.configStore.runtimeFlow.value = RuntimeState(enabled = false)
        val service = create().get()

        service.onStartCommand(null, 0, 3)

        assertNotNull("startForeground is still required", shadowOf(service).lastForegroundNotification)
        env.runPending()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(3, shadowOf(service).stopSelfId)
        assertTrue(env.engine.calls.isEmpty())
        assertEquals(0, env.engineResolutions.get())
    }

    @Test
    fun `a start command from an earlier process restores tracking once`() {
        env.configStore.runtimeFlow.value = RuntimeState(enabled = true)
        val service = create().get()

        val result = service.onStartCommand(earlierProcessStart(app), 0, 1)

        assertEquals(Service.START_STICKY, result)
        assertNotNull("foreground first", shadowOf(service).lastForegroundNotification)
        assertTrue("restored off the main thread", env.engine.calls.isEmpty())
        env.runPending()
        assertEquals(listOf("restore"), env.engine.restoreReasons)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // The engine's own start in this process does not restore again.
        service.onStartCommand(ownStart(app), 0, 2)
        env.runPending()
        assertEquals(listOf("restore"), env.engine.restoreReasons)
    }

    @Test
    fun `a start command from an earlier process stops the service when tracking is disabled`() {
        env.configStore.runtimeFlow.value = RuntimeState(enabled = false)
        val service = create().get()

        service.onStartCommand(earlierProcessStart(app), 0, 4)
        env.runPending()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(4, shadowOf(service).stopSelfId)
        assertEquals(0, env.engineResolutions.get())
    }

    @Test
    fun `startForeground failure is logged, clears isRunning and stops the service`() {
        val service = create().get()
        shadowOf(service).setThrowInStartForeground(SecurityException("missing location permission"))

        val result = service.onStartCommand(ownStart(app), 0, 5)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(LocationTrackingService.isRunning)
        assertFalse("the failed start is no longer pending", ServiceCommands.isStartPending)
        assertTrue(env.logged(LogLevel.ERROR, "startForeground failed"))

        // The engine had been told the start succeeded; it must learn that it did not.
        env.runPending()
        assertEquals(listOf("onServiceStartFailed"), env.engine.calls)
        assertTrue(env.engine.serviceStartFailures.single().startsWith("SecurityException"))
    }

    @Test
    fun `startForeground failure after an earlier success clears isRunning`() {
        val service = started().get()
        assertTrue(LocationTrackingService.isRunning)
        shadowOf(service).setThrowInStartForeground(SecurityException("denied"))

        service.onStartCommand(ownStart(app), 0, 2)

        assertFalse(LocationTrackingService.isRunning)
    }

    @Test
    fun `task removal forwards to engine onTerminate`() {
        val service = started().get()

        service.onTaskRemoved(Intent())
        env.runPending()

        assertEquals(listOf("onTerminate"), env.engine.calls)
    }

    @Test
    fun `engine failures are logged, not thrown`() {
        env.engine.failWith = TrackingException(ErrorCode.INTERNAL, "boom")
        env.configStore.runtimeFlow.value = RuntimeState(enabled = true)
        val service = create().get()

        service.onStartCommand(null, 0, 1)
        service.onTaskRemoved(null)
        env.runPending()

        assertEquals(listOf("restore", "onTerminate"), env.engine.calls)
        assertTrue(env.logged(LogLevel.ERROR, "restore failed"))
        assertTrue(env.logged(LogLevel.ERROR, "onTerminate failed"))
    }

    @Test
    fun `components that cannot be loaded stop the service`() {
        ServiceDeps.override = { error("database locked") }
        val service = create().get()

        service.onStartCommand(ownStart(app), 0, 1)
        assertFalse(shadowOf(service).isStoppedBySelf)
        env.runPending()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(env.logged(LogLevel.ERROR, "components unavailable"))
    }

    @Test
    fun `notification follows notification config changes while running`() {
        val controller = started()
        env.runPending()

        env.configStore.update {
            it.copy(notification = it.notification.copy(title = "Paused", text = "Tap to resume"))
        }
        env.runPending()

        assertEquals("Paused", posted().title)
        assertEquals("Tap to resume", posted().text)

        controller.destroy()
        controllers.remove(controller)
        env.configStore.update { it.copy(notification = it.notification.copy(title = "After destroy")) }
        env.runPending()
        assertNull(posted())
    }

    @Test
    fun `a config change while the notification is built is not lost`() {
        val store = env.configStore
        store.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "Old"))
        val racingStore = object : ConfigStore by store {
            private var reads = 0

            // The first read (the first refresh) sees the old config; the change lands right after it.
            override val config: StateFlow<TrackingConfig>
                get() {
                    if (reads++ > 0) return store.config
                    val snapshot = MutableStateFlow(store.config.value)
                    store.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "New"))
                    return snapshot
                }
        }
        ServiceDeps.override = { ServiceDeps(racingStore, env.events, env.scope, lazyOf(env.engine), env.clock) }

        started()
        env.runPending()

        assertEquals("New", posted().title)
    }

    @Test
    fun `a config change between startForeground and loading the dependencies is shown`() {
        val service = started().get()
        env.configStore.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "Later"))

        env.runPending()

        assertEquals("Later", posted().title)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `unrelated config changes do not re-post the notification`() {
        started()
        env.runPending()

        env.configStore.update { it.copy(heartbeat = it.heartbeat.copy(minInterval = 240)) }
        env.runPending()

        assertEquals(0, env.logs.lines.count { it.message == "notification updated" })
    }

    @Test
    fun `stop command stops the service without entering the foreground`() {
        val service = create().get()

        val result = service.onStartCommand(ownStop(app), 0, 4)

        assertEquals(Service.START_NOT_STICKY, result)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(4, shadowOf(service).stopSelfId)
        env.runPending()
        assertEquals(0, env.depsLookups.get())
        assertTrue(env.engine.calls.isEmpty())
    }

    @Test
    fun `a stop command sent with startForegroundService enters the foreground before it stops`() {
        val service = create().get()

        val result = service.onStartCommand(ownStop(app, foregroundRequired = true), 0, 2)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(2, shadowOf(service).stopSelfId)
    }

    @Test
    fun `a stop requested while the start was pending stops the service after it entered the foreground`() {
        val start = ownStart(app)
        // stop() was requested but Android refused the stop command: only the recorded request exists.
        ServiceCommands.stopRequested(ServiceCommands.nextSeq())
        val service = create(start).get()

        val result = service.onStartCommand(start, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals("startForeground first", Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, shadowOf(service).stopSelfId)
        env.runPending()
        assertEquals("nothing else is loaded", 0, env.depsLookups.get())
    }

    @Test
    fun `start, stop, start - the stop is ignored and the service keeps running`() {
        val start1 = ownStart(app)
        val stop2 = ownStop(app)
        val start3 = ownStart(app)
        val service = create().get()

        assertEquals(Service.START_STICKY, service.onStartCommand(start1, 0, 1))
        assertEquals(Service.START_STICKY, service.onStartCommand(stop2, 0, 2))
        assertEquals(Service.START_STICKY, service.onStartCommand(start3, 0, 3))
        env.runPending()

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(LocationTrackingService.isRunning)
        assertTrue(env.logged(LogLevel.DEBUG, "stop ignored"))
    }

    @Test
    fun `start, stop - the start enters the foreground, then the queued stop stops the service`() {
        val start1 = ownStart(app)
        val stop2 = ownStop(app)
        val service = create().get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start1, 0, 1))
        assertEquals(Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop2, 0, 2))

        assertEquals(2, shadowOf(service).stopSelfId)
    }

    @Test
    @Config(sdk = [29])
    fun `enters the foreground with the location type on API 29`() {
        val service = started().get()

        assertEquals(Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
    }

    @Test
    fun `works with the real components`() {
        ServiceDeps.override = null

        val controller = started()

        val n = shadowOf(controller.get()).lastForegroundNotification
        assertNotNull(n)
        assertEquals(NotificationConfig.DEFAULT_CHANNEL_ID, n.channelId)
        assertEquals(app.getString(R.string.lt_notification_text), n.text)
        assertTrue(LocationTrackingService.isRunning)
        env.runPending()
        assertTrue(Components.get(app).serviceController.isRunning)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }
}
