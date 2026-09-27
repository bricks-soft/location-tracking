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

    private fun serviceIntent() = Intent(app, LocationTrackingService::class.java)

    private fun create(): ServiceController<LocationTrackingService> =
        Robolectric.buildService(LocationTrackingService::class.java, serviceIntent()).create()
            .also { controllers += it }

    @Test
    fun `start command enters the foreground with the configured notification`() {
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

        val controller = create().startCommand(0, 1)

        val service = controller.get()
        val shadow = shadowOf(service)
        assertEquals(Constants.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
        val n = shadow.lastForegroundNotification
        assertEquals("trips", n.channelId)
        assertEquals("Trips", notificationManager.getNotificationChannel("trips").name.toString())
        assertEquals("Tracking", n.title)
        assertEquals("On duty", n.text)
        assertEquals(R.drawable.lt_ic_notification, n.smallIcon.resId)
        assertEquals(Color.parseColor("#3366FF"), n.color)
        assertEquals(listOf("Pause", "Stop"), n.actions.map { it.title.toString() })
        assertFalse(shadow.isStoppedBySelf)
        assertTrue(LocationTrackingService.isRunning)
        env.runPending()
        assertTrue("an explicit start does not call the engine", env.engine.calls.isEmpty())
    }

    @Test
    fun `start command returns START_STICKY`() {
        val service = create().get()

        assertEquals(Service.START_STICKY, service.onStartCommand(serviceIntent(), 0, 1))
        assertEquals(Service.START_STICKY, service.onStartCommand(serviceIntent(), 0, 2))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `isRunning is true between create and destroy`() {
        assertFalse(LocationTrackingService.isRunning)

        val controller = create()
        assertTrue(LocationTrackingService.isRunning)

        controller.destroy()
        controllers.remove(controller)
        assertFalse(LocationTrackingService.isRunning)
        assertNull(shadowOf(notificationManager).getNotification(Constants.NOTIFICATION_ID))
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

        val result = service.onStartCommand(null, 0, 3)

        assertEquals(Service.START_NOT_STICKY, result)
        assertNotNull("startForeground is still required", shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(3, shadowOf(service).stopSelfId)
        env.runPending()
        assertTrue(env.engine.calls.isEmpty())
        assertEquals(0, env.engineResolutions.get())
    }

    @Test
    fun `startForeground failure is logged and stops the service`() {
        val service = create().get()
        shadowOf(service).setThrowInStartForeground(SecurityException("missing location permission"))

        val result = service.onStartCommand(serviceIntent(), 0, 5)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(env.logged(LogLevel.ERROR, "startForeground failed"))
    }

    @Test
    fun `task removal forwards to engine onTerminate`() {
        val service = create().startCommand(0, 1).get()

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
    fun `notification follows notification config changes while running`() {
        val controller = create().startCommand(0, 1)
        env.runPending()

        env.configStore.update {
            it.copy(notification = it.notification.copy(title = "Paused", text = "Tap to resume"))
        }
        env.runPending()

        val posted = shadowOf(notificationManager).getNotification(Constants.NOTIFICATION_ID)
        assertEquals("Paused", posted.title)
        assertEquals("Tap to resume", posted.text)

        controller.destroy()
        controllers.remove(controller)
        env.configStore.update { it.copy(notification = it.notification.copy(title = "After destroy")) }
        env.runPending()
        assertNull(shadowOf(notificationManager).getNotification(Constants.NOTIFICATION_ID))
    }

    @Test
    fun `a config change during startForeground is not lost`() {
        val store = env.configStore
        store.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "Old"))
        val racingStore = object : ConfigStore by store {
            private var reads = 0

            // The first read (in onStartCommand) sees the old config; the change lands right after it.
            override val config: StateFlow<TrackingConfig>
                get() {
                    if (reads++ > 0) return store.config
                    val snapshot = MutableStateFlow(store.config.value)
                    store.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "New"))
                    return snapshot
                }
        }
        ServiceDeps.override = { ServiceDeps(racingStore, env.events, env.scope, lazyOf(env.engine)) }

        val service = create().startCommand(0, 1).get()

        assertEquals("Old", shadowOf(service).lastForegroundNotification.title)
        assertEquals("New", shadowOf(notificationManager).getNotification(Constants.NOTIFICATION_ID).title)
    }

    @Test
    fun `unrelated config changes do not re-post the notification`() {
        create().startCommand(0, 1)
        env.runPending()

        env.configStore.update { it.copy(heartbeat = it.heartbeat.copy(minInterval = 240)) }
        env.runPending()

        assertEquals(0, env.logs.lines.count { it.message == "notification updated" })
    }

    @Test
    fun `stop command stops the service without entering the foreground`() {
        val service = create().get()
        val stop = serviceIntent().setAction(LocationTrackingService.ACTION_STOP)

        val result = service.onStartCommand(stop, 0, 4)

        assertEquals(Service.START_NOT_STICKY, result)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(4, shadowOf(service).stopSelfId)
        env.runPending()
        assertTrue(env.engine.calls.isEmpty())
    }

    @Test
    fun `stop then a newer start keeps the service running`() {
        val service = create().get()
        service.onStartCommand(serviceIntent(), 0, 1)

        service.onStartCommand(serviceIntent().setAction(LocationTrackingService.ACTION_STOP), 0, 2)
        val result = service.onStartCommand(serviceIntent(), 0, 3)

        assertEquals(Service.START_STICKY, result)
        // stopSelf(2) is ignored by the system because start id 3 is newer.
        assertEquals(2, shadowOf(service).stopSelfId)
        assertEquals(Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
    }

    @Test
    @Config(sdk = [29])
    fun `enters the foreground with the location type on API 29`() {
        val service = create().startCommand(0, 1).get()

        assertEquals(Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
    }

    @Test
    fun `works with the real components`() {
        env.uninstall()

        val controller = create().startCommand(0, 1)

        val n = shadowOf(controller.get()).lastForegroundNotification
        assertNotNull(n)
        assertEquals(NotificationConfig.DEFAULT_CHANNEL_ID, n.channelId)
        assertEquals(app.getString(R.string.lt_notification_text), n.text)
        assertTrue(LocationTrackingService.isRunning)
        assertTrue(Components.get(app).serviceController.isRunning)
    }
}
