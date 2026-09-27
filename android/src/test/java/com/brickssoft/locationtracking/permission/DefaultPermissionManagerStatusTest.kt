package com.brickssoft.locationtracking.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.permission.PermissionState.DENIED
import com.brickssoft.locationtracking.permission.PermissionState.GRANTED
import com.brickssoft.locationtracking.permission.PermissionState.PROMPT
import com.brickssoft.locationtracking.permission.PermissionState.PROMPT_WITH_RATIONALE
import com.brickssoft.locationtracking.permission.PermissionType.ACTIVITY_RECOGNITION
import com.brickssoft.locationtracking.permission.PermissionType.BACKGROUND_LOCATION
import com.brickssoft.locationtracking.permission.PermissionType.LOCATION
import com.brickssoft.locationtracking.permission.PermissionType.NOTIFICATIONS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DefaultPermissionManagerStatusTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    private fun statusOf(
        location: PermissionState,
        background: PermissionState,
        activity: PermissionState,
        notifications: PermissionState,
    ) = mapOf(
        LOCATION to location,
        BACKGROUND_LOCATION to background,
        ACTIVITY_RECOGNITION to activity,
        NOTIFICATIONS to notifications,
    )

    @Test
    @Config(sdk = [34])
    fun `API 34 nothing granted and never asked is PROMPT everywhere`() {
        val manager = DefaultPermissionManager(app)

        assertEquals(statusOf(PROMPT, PROMPT, PROMPT, PROMPT), manager.status())
        assertEquals(PermissionType.entries.toList(), manager.status().keys.toList())
        assertFalse(manager.hasForegroundLocation())
        assertFalse(manager.hasBackgroundLocation())
        assertFalse(manager.hasActivityRecognition())
        assertFalse(manager.hasNotifications())
    }

    @Test
    @Config(sdk = [34])
    fun `API 34 everything granted`() {
        grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val manager = DefaultPermissionManager(app)

        assertEquals(statusOf(GRANTED, GRANTED, GRANTED, GRANTED), manager.status())
        assertTrue(manager.hasForegroundLocation())
        assertTrue(manager.hasBackgroundLocation())
        assertTrue(manager.hasActivityRecognition())
        assertTrue(manager.hasNotifications())
    }

    @Test
    @Config(sdk = [34])
    fun `coarse alone counts as foreground location`() {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = DefaultPermissionManager(app)

        assertEquals(GRANTED, manager.status()[LOCATION])
        assertTrue(manager.hasForegroundLocation())
        assertEquals(PROMPT, manager.status()[BACKGROUND_LOCATION])
        assertFalse(manager.hasBackgroundLocation())
    }

    @Test
    @Config(sdk = [34])
    fun `fine alone counts as foreground location`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)

        assertTrue(DefaultPermissionManager(app).hasForegroundLocation())
    }

    @Test
    @Config(sdk = [33])
    fun `API 33 notifications is a runtime permission`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        val manager = DefaultPermissionManager(app)

        assertEquals(statusOf(GRANTED, PROMPT, GRANTED, PROMPT), manager.status())
        assertFalse(manager.hasNotifications())

        grant(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(GRANTED, manager.status()[NOTIFICATIONS])
        assertTrue(manager.hasNotifications())
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 notifications are implicitly granted`() {
        val manager = DefaultPermissionManager(app)

        assertEquals(statusOf(PROMPT, PROMPT, PROMPT, GRANTED), manager.status())
        assertTrue(manager.hasNotifications())
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 background and activity recognition are runtime, notifications granted`() {
        val manager = DefaultPermissionManager(app)
        assertEquals(statusOf(PROMPT, PROMPT, PROMPT, GRANTED), manager.status())

        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(statusOf(GRANTED, GRANTED, PROMPT, GRANTED), manager.status())
        assertTrue(manager.hasBackgroundLocation())
        assertFalse(manager.hasActivityRecognition())
    }

    @Test
    @Config(sdk = [29])
    fun `background permission without foreground is not background location`() {
        grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val manager = DefaultPermissionManager(app)

        assertFalse(manager.hasBackgroundLocation())
        assertEquals(PROMPT, manager.status()[BACKGROUND_LOCATION])
    }

    @Test
    @Config(sdk = [29])
    fun `below API 29 background equals foreground and activity recognition is granted`() {
        val manager = DefaultPermissionManager(app, { null }, 28)
        assertEquals(statusOf(PROMPT, PROMPT, GRANTED, GRANTED), manager.status())
        assertTrue(manager.hasActivityRecognition())
        assertTrue(manager.hasNotifications())

        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(statusOf(GRANTED, GRANTED, GRANTED, GRANTED), manager.status())
        assertTrue(manager.hasBackgroundLocation())
    }

    @Test
    @Config(sdk = [29])
    fun `below API 29 a denied foreground makes background denied too`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val manager = DefaultPermissionManager(app, { activity }, 28)
        val host = RecordingPermissionHost(activity)

        manager.request(host, listOf(LOCATION), BackgroundPermissionRationale()) {}
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(DENIED, manager.status()[LOCATION])
        assertEquals(DENIED, manager.status()[BACKGROUND_LOCATION])
    }

    @Test
    @Config(sdk = [34])
    fun `asked and not granted is DENIED without rationale and PROMPT_WITH_RATIONALE with it`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val manager = DefaultPermissionManager(app) { activity }
        val host = RecordingPermissionHost(activity)

        manager.request(host, listOf(LOCATION, ACTIVITY_RECOGNITION), BackgroundPermissionRationale()) {}
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(DENIED, manager.status()[LOCATION])
        assertEquals(DENIED, manager.status()[ACTIVITY_RECOGNITION])
        assertEquals(PROMPT, manager.status()[NOTIFICATIONS])

        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, true)
        assertEquals(PROMPT_WITH_RATIONALE, manager.status()[LOCATION])
        assertEquals(DENIED, manager.status()[ACTIVITY_RECOGNITION])
    }

    @Test
    @Config(sdk = [34])
    fun `rationale wins even if the plugin never asked`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION, true)

        assertEquals(PROMPT_WITH_RATIONALE, DefaultPermissionManager(app) { activity }.status()[ACTIVITY_RECOGNITION])
        assertEquals(PROMPT, DefaultPermissionManager(app).status()[ACTIVITY_RECOGNITION])
    }

    @Test
    @Config(sdk = [34])
    fun `without an activity the rationale recorded after the last request is used`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val pm = shadowOf(app.packageManager)
        pm.setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, true)
        val manager = DefaultPermissionManager(app)
        val host = RecordingPermissionHost(controller.get())
        manager.request(host, listOf(LOCATION, ACTIVITY_RECOGNITION), BackgroundPermissionRationale()) {}
        shadowOf(Looper.getMainLooper()).idle()

        // No activity any more (e.g. status() from a headless restart).
        controller.pause().stop().destroy()
        host.activity = null

        assertEquals(PROMPT_WITH_RATIONALE, manager.status()[LOCATION])
        assertEquals(DENIED, manager.status()[ACTIVITY_RECOGNITION])
        assertEquals(PROMPT, manager.status()[NOTIFICATIONS])
    }

    @Test
    @Config(sdk = [34])
    fun `flags are forgotten once granted so a later revocation starts from PROMPT`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val manager = DefaultPermissionManager(app)
        manager.request(RecordingPermissionHost(activity), listOf(ACTIVITY_RECOGNITION), BackgroundPermissionRationale()) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DENIED, manager.status()[ACTIVITY_RECOGNITION])

        grant(Manifest.permission.ACTIVITY_RECOGNITION)
        assertEquals(GRANTED, manager.status()[ACTIVITY_RECOGNITION])
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION) // e.g. Android's auto-reset

        assertEquals(PROMPT, manager.status()[ACTIVITY_RECOGNITION])
    }

    @Test
    @Config(sdk = [34])
    fun `asked flags restored from another install are ignored`() {
        app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("perm_asked_location", 123_456L)
            .commit()

        assertEquals(PROMPT, DefaultPermissionManager(app).status()[LOCATION])
    }

    @Test
    @Config(sdk = [34])
    fun `asked flags are persisted in the plugin prefs under perm_ keys only`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val manager = DefaultPermissionManager(app)
        manager.request(RecordingPermissionHost(activity), PermissionType.entries, BackgroundPermissionRationale()) {}
        shadowOf(Looper.getMainLooper()).idle()

        val keys = app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).all.keys
        assertTrue(keys.isNotEmpty())
        assertTrue("unexpected keys $keys", keys.all { it.startsWith("perm_") })
        // A fresh manager (new process) still knows location was asked.
        assertEquals(DENIED, DefaultPermissionManager(app).status()[LOCATION])
    }

    @Test
    @Config(sdk = [34])
    fun `activity tracker follows the app's activities`() {
        val tracker = CurrentActivityTracker.attach(app)
        assertNull(tracker.current())

        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertSame(controller.get(), tracker.current())

        controller.pause().stop().destroy()
        assertNull(tracker.current())
    }

    @Test
    @Config(sdk = [34])
    fun `status uses an activity started after the manager was created`() {
        val manager = DefaultPermissionManager(app)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION, true)
        assertEquals(PROMPT, manager.status()[LOCATION])

        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals(PROMPT_WITH_RATIONALE, manager.status()[LOCATION])
    }
}
