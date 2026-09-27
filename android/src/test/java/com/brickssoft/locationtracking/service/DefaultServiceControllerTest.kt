package com.brickssoft.locationtracking.service

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.Config as TrackingConfig
import com.brickssoft.locationtracking.config.NotificationActionButton
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.config.NotificationPriority
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.permission.CurrentActivityTracker
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
class DefaultServiceControllerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = app.getSystemService(NotificationManager::class.java)
    private val activityManager = app.getSystemService(ActivityManager::class.java)
    private val serviceComponent = ComponentName(app, LocationTrackingService::class.java)
    private val env = ServiceTestEnv()
    private val services = mutableListOf<ServiceController<LocationTrackingService>>()

    @Before
    fun setUp() {
        env.install()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.destroy() } }
        env.uninstall()
    }

    private fun controller(context: Context = app) =
        DefaultServiceController(context, env.configStore, env.events, env.clock)

    /** A context whose service calls throw [error]; `applicationContext` returns itself so the wrapper is kept. */
    private fun throwingContext(
        error: Exception,
        onStartForegroundService: Boolean = true,
        onStartService: Boolean = true,
    ) = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName? =
            if (onStartForegroundService) throw error else super.startForegroundService(service)

        override fun startService(service: Intent): ComponentName? =
            if (onStartService) throw error else super.startService(service)
    }

    private fun service(): LocationTrackingService =
        Robolectric.buildService(LocationTrackingService::class.java).create().also { services += it }.get()

    /** The app process has no visible activity (Android reports it as cached). */
    private fun appInBackground() {
        val info = ActivityManager.RunningAppProcessInfo().apply {
            pid = Process.myPid()
            processName = app.packageName
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }
        shadowOf(activityManager).setProcesses(listOf(info))
    }

    @Test
    fun `start asks the OS to start the foreground service with a stamped start command`() {
        assertTrue(controller().start())

        val intent = shadowOf(app).nextStartedService
        assertEquals(serviceComponent, intent.component)
        assertNull(intent.action)
        assertEquals(ServiceCommands.processToken, intent.getStringExtra(ServiceCommands.EXTRA_ORIGIN))
        assertTrue(intent.getBooleanExtra(ServiceCommands.EXTRA_FOREGROUND_REQUIRED, false))
        assertTrue(intent.getLongExtra(ServiceCommands.EXTRA_SEQ, 0L) > 0L)
        assertTrue(ServiceCommands.isStartPending)
    }

    @Test
    fun `the start command carries the configured notification fields`() {
        val configured = NotificationConfig(
            title = "Field Force",
            text = "Shift tracking is on",
            color = "#3366FF",
            priority = NotificationPriority.LOW,
            channelId = "shift",
            channelName = "Shift",
            actions = listOf(NotificationActionButton("pause", "Pause")),
        )
        env.configStore.configFlow.value = TrackingConfig(notification = configured)

        controller().start()

        val command = ServiceCommand.parse(shadowOf(app).nextStartedService)
        assertEquals("action buttons are not carried", configured.copy(actions = emptyList()), command.notification)
    }

    @Test
    fun `an accepted start records the boot count for the boot receiver`() {
        assertNull(BootCountStore(app).lastServiceStart())

        controller().start()

        assertEquals(ServiceTestEnv.BOOT_COUNT, BootCountStore(app).lastServiceStart())
    }

    @Test
    fun `a refused start records no boot count`() {
        controller(throwingContext(SecurityException("denied"))).start()

        assertNull(BootCountStore(app).lastServiceStart())
    }

    @Test
    fun `start returns false when the OS refuses a background start`() {
        val controller = controller(throwingContext(ForegroundServiceStartNotAllowedException("app in background")))

        assertFalse(controller.start())

        assertTrue(env.logged(LogLevel.WARN, "refused"))
        assertFalse("a refused start is not pending", ServiceCommands.isStartPending)
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
    fun `start returns false when the service is not found`() {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun startForegroundService(service: Intent): ComponentName? = null
        }

        assertFalse(controller(context).start())

        assertFalse("a start that was never delivered is not pending", ServiceCommands.isStartPending)
        assertTrue(env.logged(LogLevel.ERROR, "not found"))
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

    // ---- Android 14+ while-in-use pre-check

    @Test
    fun `API 34 - background start with only while-in-use location is not attempted`() {
        appInBackground()

        assertFalse(controller().start())

        assertNull("startForegroundService is not called", shadowOf(app).nextStartedService)
        assertFalse(ServiceCommands.isStartPending)
        assertTrue(env.logged(LogLevel.WARN, "no visible activity and no background location permission"))
    }

    @Test
    fun `API 34 - background start with background location is attempted`() {
        appInBackground()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        assertTrue(controller().start())

        assertEquals(serviceComponent, shadowOf(app).nextStartedService.component)
    }

    @Test
    fun `API 34 - start with a visible activity is attempted without background location`() {
        // Robolectric reports the app process as foreground by default.
        assertTrue(controller().start())

        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `API 34 - start within the grace period after an activity stopped is attempted`() {
        val controller = controller()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.pause().stop()
        appInBackground()

        assertTrue(controller.start())

        env.clock.advance(WhileInUseStartCheck.GRACE_MS)
        ServiceCommands.reset()
        assertFalse("after the grace period the start is not attempted", controller.start())
        activity.destroy()
    }

    @Test
    fun `API 34 - start while an activity is started is attempted even if the importance says background`() {
        val controller = controller()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        appInBackground()
        env.clock.advance(WhileInUseStartCheck.GRACE_MS * 2)

        assertTrue(controller.start())
        activity.destroy()
    }

    @Test
    fun `API 34 - the started activity the plugin remembered counts as visible`() {
        // Started before the controller existed, so the controller's own lifecycle callbacks never saw it start.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        CurrentActivityTracker.attach(app).remember(activity.get())
        val controller = controller()
        appInBackground()

        assertTrue(controller.start())

        ServiceCommands.reset()
        activity.pause().stop()
        env.clock.advance(WhileInUseStartCheck.GRACE_MS)
        assertFalse("stopped and outside the grace period", controller.start())
        activity.destroy()
    }

    @Test
    fun `API 34 - a start while the service is in the foreground is attempted`() {
        val service = service()
        service.onStartCommand(ownStart(app), 0, 1)
        ServiceCommands.reset()
        appInBackground()

        assertTrue(controller().start())
    }

    @Test
    @Config(sdk = [33])
    fun `API 33 - no while-in-use pre-check`() {
        appInBackground()

        assertTrue(controller().start())
    }

    // ---- stop

    @Test
    fun `stop sends a stamped stop command to the service`() {
        controller().stop()

        val intent = shadowOf(app).nextStartedService
        assertEquals(serviceComponent, intent.component)
        assertEquals(LocationTrackingService.ACTION_STOP, intent.action)
        assertEquals(ServiceCommands.processToken, intent.getStringExtra(ServiceCommands.EXTRA_ORIGIN))
        assertFalse(intent.getBooleanExtra(ServiceCommands.EXTRA_FOREGROUND_REQUIRED, true))
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun `stop falls back to stopService when the OS refuses the command and no start is pending`() {
        controller(throwingContext(IllegalStateException("app is in background"))).stop()

        assertEquals(serviceComponent, shadowOf(app).nextStoppedService.component)
    }

    @Test
    fun `stop never calls stopService while a start is pending`() {
        val context = throwingContext(IllegalStateException("app is in background"), onStartForegroundService = false)
        val controller = controller(context)
        assertTrue(controller.start())
        val start = shadowOf(app).nextStartedService

        controller.stop()

        assertNull("no stopService racing the pending start", shadowOf(app).nextStoppedService)
        assertNull("the stop command was refused", shadowOf(app).nextStartedService)
        assertTrue(env.logged(LogLevel.INFO, "stops itself after it has entered the foreground"))

        // The pending start arrives: foreground first, then the service stops itself.
        val service = service()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start, 0, 1))
        assertEquals(Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, shadowOf(service).stopSelfId)
    }

    @Test
    fun `start then stop - the service enters the foreground before it stops`() {
        val controller = controller()
        controller.start()
        controller.stop()
        val start = shadowOf(app).nextStartedService
        val stop = shadowOf(app).nextStartedService
        assertEquals(LocationTrackingService.ACTION_STOP, stop.action)
        assertNull(shadowOf(app).nextStoppedService)
        val service = service()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start, 0, 1))
        assertEquals(Constants.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 2))

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals("stops only if no newer command was sent", 2, shadowOf(service).stopSelfId)
    }

    @Test
    fun `start, stop, start in one turn ends with the service running`() {
        val controller = controller()
        assertTrue(controller.start())
        controller.stop()
        assertTrue(controller.start())
        val commands = generateSequence { shadowOf(app).nextStartedService }.toList()
        assertEquals(listOf(null, LocationTrackingService.ACTION_STOP, null), commands.map { it.action })
        val service = service()

        commands.forEachIndexed { index, intent -> service.onStartCommand(intent, 0, index + 1) }
        env.runPending()

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(LocationTrackingService.isRunning)
        assertTrue(controller.isRunning)
    }

    @Test
    fun `a second start while one is pending sends nothing`() {
        val controller = controller()

        assertTrue(controller.start())
        assertTrue(controller.start())

        assertNotNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(env.logged(LogLevel.DEBUG, "already pending"))
    }

    @Test
    fun `a start after the pending start was handled is sent again`() {
        val controller = controller()
        controller.start()
        val service = service()
        service.onStartCommand(shadowOf(app).nextStartedService, 0, 1)

        assertTrue(controller.start())

        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `isRunning is true from startForeground until destroy`() {
        val controller = controller()
        assertFalse(controller.isRunning)

        val service = Robolectric.buildService(LocationTrackingService::class.java, ownStart(app)).create()
        assertFalse("created, not yet in the foreground", controller.isRunning)
        service.startCommand(0, 1)
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

        val service = Robolectric.buildService(LocationTrackingService::class.java, ownStart(app)).create()
            .startCommand(0, 1)
        // Without env.runPending() the service's background steps do not run: only the controller refreshes.
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
        val service = Robolectric.buildService(LocationTrackingService::class.java, ownStart(app)).create()
            .startCommand(0, 1)
        env.runPending()

        env.configStore.configFlow.value = TrackingConfig(notification = NotificationConfig(title = "Once"))
        controller.refreshNotification()
        env.runPending()
        controller.refreshNotification()

        assertEquals(1, env.logs.lines.count { it.message == "notification updated" })
        service.destroy()
    }
}
