package com.brickssoft.locationtracking.service

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.Config as TrackingConfig
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.LogLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DefaultServiceControllerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = app.getSystemService(NotificationManager::class.java)
    private val serviceComponent = ComponentName(app, LocationTrackingService::class.java)
    private val env = ServiceTestEnv()

    @Before
    fun setUp() {
        env.install()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @After
    fun tearDown() {
        env.uninstall()
    }

    private fun controller(context: Context = app) = DefaultServiceController(context, env.configStore, env.events)

    /** A context whose service calls throw [error]; `applicationContext` returns itself so the wrapper is kept. */
    private fun throwingContext(error: Exception, onStartService: Boolean = true) = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName = throw error

        override fun startService(service: Intent): ComponentName? =
            if (onStartService) throw error else super.startService(service)
    }

    @Test
    fun `start asks the OS to start the foreground service`() {
        assertTrue(controller().start())

        val intent = shadowOf(app).nextStartedService
        assertEquals(serviceComponent, intent.component)
        assertNull(intent.action)
    }

    @Test
    fun `start returns false when the OS refuses a background start`() {
        val controller = controller(throwingContext(ForegroundServiceStartNotAllowedException("app in background")))

        assertFalse(controller.start())

        assertTrue(env.logged(LogLevel.WARN, "refused"))
    }

    @Test
    fun `start returns false on any other failure`() {
        assertFalse(controller(throwingContext(SecurityException("denied"))).start())

        assertTrue(env.logged(LogLevel.ERROR, "could not start"))
    }

    @Test
    @Config(sdk = [30])
    fun `start returns false on a failure below API 31`() {
        assertFalse(controller(throwingContext(IllegalStateException("Not allowed to start service"))).start())

        assertTrue(env.logged(LogLevel.ERROR, "could not start"))
    }

    @Test
    fun `only ForegroundServiceStartNotAllowedException counts as a refused background start`() {
        assertTrue(DefaultServiceController.isForegroundStartNotAllowed(ForegroundServiceStartNotAllowedException("x")))
        assertFalse(DefaultServiceController.isForegroundStartNotAllowed(IllegalStateException("x")))
        assertFalse(DefaultServiceController.isForegroundStartNotAllowed(SecurityException("x")))
    }

    @Test
    fun `start without location permission is refused on API 34`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertFalse(controller().start())

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(env.logged(LogLevel.WARN, "no location permission"))
    }

    @Test
    fun `coarse location is enough on API 34`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertTrue(controller().start())
    }

    @Test
    @Config(sdk = [33])
    fun `start does not require location permission below API 34`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertTrue(controller().start())

        assertEquals(serviceComponent, shadowOf(app).nextStartedService.component)
    }

    @Test
    fun `stop sends a stop command to the service`() {
        controller().stop()

        val intent = shadowOf(app).nextStartedService
        assertEquals(serviceComponent, intent.component)
        assertEquals(LocationTrackingService.ACTION_STOP, intent.action)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun `stop falls back to stopService when the OS refuses the command`() {
        controller(throwingContext(IllegalStateException("app is in background"))).stop()

        assertEquals(serviceComponent, shadowOf(app).nextStoppedService.component)
    }

    @Test
    fun `start then stop - the service enters the foreground before it stops`() {
        val controller = controller()
        controller.start()
        controller.stop()
        val start = shadowOf(app).nextStartedService
        val stop = shadowOf(app).nextStartedService
        val serviceController = Robolectric.buildService(LocationTrackingService::class.java).create()
        val service = serviceController.get()

        assertEquals(Service.START_STICKY, service.onStartCommand(start, 0, 1))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 2))

        assertEquals(Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals("stops only if no newer start is queued", 2, shadowOf(service).stopSelfId)
        serviceController.destroy()
    }

    @Test
    fun `isRunning follows the service lifecycle`() {
        val controller = controller()
        assertFalse(controller.isRunning)

        val service = Robolectric.buildService(LocationTrackingService::class.java).create()
        assertTrue(controller.isRunning)

        service.destroy()
        assertFalse(controller.isRunning)
    }

    @Test
    fun `refreshNotification re-posts from the current config only while running`() {
        val controller = controller()
        env.configStore.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "Before start"))
        controller.refreshNotification()
        assertNull(shadowOf(notificationManager).getNotification(Constants.NOTIFICATION_ID))

        val service = Robolectric.buildService(LocationTrackingService::class.java).create().startCommand(0, 1)
        // Without env.runPending() the service's own config watcher does not run: only the controller refreshes.
        env.configStore.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "Refreshed"))
        controller.refreshNotification()
        assertEquals("Refreshed", shadowOf(notificationManager).getNotification(Constants.NOTIFICATION_ID).title)

        service.destroy()
        controller.refreshNotification()
        assertNull(shadowOf(notificationManager).getNotification(Constants.NOTIFICATION_ID))
    }

    @Test
    fun `a config change is posted once by the controller and the service together`() {
        val controller = controller()
        val service = Robolectric.buildService(LocationTrackingService::class.java).create().startCommand(0, 1)
        env.runPending()

        env.configStore.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "Once"))
        controller.refreshNotification()
        env.runPending()
        controller.refreshNotification()

        assertEquals(1, env.logs.lines.count { it.message == "notification updated" })
        service.destroy()
    }
}
