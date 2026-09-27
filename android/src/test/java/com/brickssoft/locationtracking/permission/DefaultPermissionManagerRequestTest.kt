package com.brickssoft.locationtracking.permission

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.R
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.permission.PermissionState.GRANTED
import com.brickssoft.locationtracking.permission.PermissionState.PROMPT
import com.brickssoft.locationtracking.permission.PermissionType.ACTIVITY_RECOGNITION
import com.brickssoft.locationtracking.permission.PermissionType.BACKGROUND_LOCATION
import com.brickssoft.locationtracking.permission.PermissionType.LOCATION
import com.brickssoft.locationtracking.permission.PermissionType.NOTIFICATIONS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
class DefaultPermissionManagerRequestTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var activity: Activity
    private lateinit var manager: DefaultPermissionManager
    private val results = mutableListOf<Map<PermissionType, PermissionState>>()
    private val resultThreads = mutableListOf<Thread>()
    private val onDone: (Map<PermissionType, PermissionState>) -> Unit = {
        results += it
        resultThreads += Thread.currentThread()
    }

    /** All four types, deliberately not in request order. */
    private val allTypes = listOf(BACKGROUND_LOCATION, ACTIVITY_RECOGNITION, NOTIFICATIONS, LOCATION)
    private val allGranted = PermissionType.entries.associateWith { GRANTED }

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        manager = DefaultPermissionManager(app)
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun grantingHost(activity: Activity? = this.activity, async: Boolean = true) =
        RecordingPermissionHost(activity, async, RecordingPermissionHost.grant(app))

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    private fun latestDialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()

    private fun AlertDialog.click(which: Int) {
        getButton(which).performClick()
        idle()
    }

    @Test
    @Config(sdk = [34])
    fun `API 34 requests one group at a time in order and background after the rationale`() {
        val host = grantingHost()

        manager.request(host, allTypes, BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("location"), listOf("notifications"), listOf("activityRecognition")), host.requested)
        val dialog = latestDialog()
        assertNotNull(dialog)
        assertTrue(dialog!!.isShowing)
        assertTrue("onDone must wait for the dialog", results.isEmpty())

        dialog.click(AlertDialog.BUTTON_POSITIVE)

        assertEquals(
            listOf(listOf("location"), listOf("notifications"), listOf("activityRecognition"), listOf("backgroundLocation")),
            host.requested,
        )
        assertEquals(listOf(allGranted), results)
        assertSame(Looper.getMainLooper().thread, resultThreads.single())
    }

    @Test
    @Config(sdk = [34])
    fun `negative button skips background location`() {
        val host = grantingHost()

        manager.request(host, allTypes, BackgroundPermissionRationale(), onDone)
        idle()
        latestDialog()!!.click(AlertDialog.BUTTON_NEGATIVE)

        assertFalse(host.requested.contains(listOf("backgroundLocation")))
        assertEquals(1, results.size)
        assertEquals(GRANTED, results.single()[LOCATION])
        assertEquals(PROMPT, results.single()[BACKGROUND_LOCATION])
        assertFalse(latestDialog()!!.isShowing)
    }

    @Test
    @Config(sdk = [34])
    fun `cancelling the rationale dialog skips background location`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val host = grantingHost()

        manager.request(host, listOf(BACKGROUND_LOCATION), BackgroundPermissionRationale(), onDone)
        idle()
        latestDialog()!!.cancel()
        idle()

        assertTrue(host.requested.isEmpty())
        assertEquals(1, results.size)
        assertEquals(PROMPT, results.single()[BACKGROUND_LOCATION])
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 shows the rationale and does not request notifications`() {
        val host = grantingHost()

        manager.request(host, allTypes, BackgroundPermissionRationale(), onDone)
        idle()
        assertEquals(listOf(listOf("location"), listOf("activityRecognition")), host.requested)

        latestDialog()!!.click(AlertDialog.BUTTON_POSITIVE)

        assertEquals(listOf(listOf("location"), listOf("activityRecognition"), listOf("backgroundLocation")), host.requested)
        assertEquals(listOf(allGranted), results)
    }

    @Test
    @Config(sdk = [33])
    fun `API 33 requests notifications`() {
        val host = grantingHost()

        manager.request(host, listOf(NOTIFICATIONS, LOCATION), BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("location"), listOf("notifications")), host.requested)
        assertNull(latestDialog())
        assertEquals(1, results.size)
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 requests background directly after foreground without a dialog`() {
        val host = grantingHost()

        manager.request(host, allTypes, BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("location"), listOf("activityRecognition"), listOf("backgroundLocation")), host.requested)
        assertNull(latestDialog())
        assertEquals(listOf(allGranted), results)
    }

    @Test
    @Config(sdk = [29])
    fun `below API 29 only location is requested`() {
        val manager = DefaultPermissionManager(app, { null }, 28)
        val host = grantingHost()

        manager.request(host, allTypes, BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("location")), host.requested)
        assertNull(latestDialog())
        assertEquals(listOf(allGranted), results)
    }

    @Test
    @Config(sdk = [34])
    fun `granted permissions are skipped`() {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        val host = grantingHost()

        manager.request(host, listOf(LOCATION, NOTIFICATIONS, ACTIVITY_RECOGNITION), BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("activityRecognition")), host.requested)
        assertEquals(1, results.size)
    }

    @Test
    @Config(sdk = [34])
    fun `nothing to request completes with the current status`() {
        grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val host = grantingHost()

        manager.request(host, allTypes, BackgroundPermissionRationale(), onDone)
        manager.request(host, emptyList(), BackgroundPermissionRationale(), onDone)
        idle()

        assertTrue(host.requested.isEmpty())
        assertNull(latestDialog())
        assertEquals(listOf(allGranted, allGranted), results)
    }

    @Test
    @Config(sdk = [34])
    fun `background is never requested while foreground is denied`() {
        val host = RecordingPermissionHost(activity) // the user denies everything

        manager.request(host, allTypes, BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("location"), listOf("notifications"), listOf("activityRecognition")), host.requested)
        assertNull(latestDialog())
        assertEquals(1, results.size)
        assertEquals(PermissionState.DENIED, results.single()[LOCATION])
        assertEquals(PROMPT, results.single()[BACKGROUND_LOCATION])
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 background is not requested while foreground is denied`() {
        val host = RecordingPermissionHost(activity)

        manager.request(host, listOf(LOCATION, BACKGROUND_LOCATION), BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("location")), host.requested)
        assertEquals(1, results.size)
    }

    @Test
    @Config(sdk = [34])
    fun `without an activity background is skipped`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val host = grantingHost(activity = null)

        manager.request(host, listOf(BACKGROUND_LOCATION), BackgroundPermissionRationale(), onDone)
        idle()

        assertTrue(host.requested.isEmpty())
        assertNull(latestDialog())
        assertEquals(1, results.size)
        assertEquals(PROMPT, results.single()[BACKGROUND_LOCATION])
    }

    @Test
    @Config(sdk = [34])
    fun `a finishing activity is not used for the dialog`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        activity.finish()
        val host = grantingHost()

        manager.request(host, listOf(BACKGROUND_LOCATION), BackgroundPermissionRationale(), onDone)
        idle()

        assertNull(latestDialog())
        assertTrue(host.requested.isEmpty())
        assertEquals(1, results.size)
    }

    @Test
    @Config(sdk = [34])
    fun `rationale texts come from the config`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val rationale = BackgroundPermissionRationale(
            title = "Always?",
            message = "We need it",
            positiveAction = "Sure",
            negativeAction = "Nope",
        )

        manager.request(grantingHost(), listOf(BACKGROUND_LOCATION), rationale, onDone)
        idle()

        val dialog = latestDialog()!!
        assertEquals("Always?", shadowOf(dialog).title.toString())
        assertEquals("We need it", shadowOf(dialog).message.toString())
        assertEquals("Sure", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Nope", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
    }

    @Test
    @Config(sdk = [34])
    fun `rationale texts default to the string resources`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val rationale = BackgroundPermissionRationale(title = "  ", message = null)

        manager.request(grantingHost(), listOf(BACKGROUND_LOCATION), rationale, onDone)
        idle()

        val dialog = latestDialog()!!
        assertEquals(app.getString(R.string.lt_bg_rationale_title), shadowOf(dialog).title.toString())
        assertEquals(app.getString(R.string.lt_bg_rationale_message), shadowOf(dialog).message.toString())
        assertEquals(
            app.getString(R.string.lt_bg_rationale_positive),
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
        )
        assertEquals(
            app.getString(R.string.lt_bg_rationale_negative),
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString(),
        )
    }

    @Test
    @Config(sdk = [34])
    fun `a host calling back twice does not advance the flow twice`() {
        val host = grantingHost()
        host.callbacksPerRequest = 2

        manager.request(host, listOf(LOCATION, NOTIFICATIONS, ACTIVITY_RECOGNITION), BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("location"), listOf("notifications"), listOf("activityRecognition")), host.requested)
        assertEquals(1, results.size)
    }

    @Test
    @Config(sdk = [34])
    fun `a synchronous host works`() {
        val host = grantingHost(async = false)

        manager.request(host, allTypes, BackgroundPermissionRationale(), onDone)
        idle()
        latestDialog()!!.click(AlertDialog.BUTTON_POSITIVE)

        assertEquals(4, host.requested.size)
        assertEquals(listOf(allGranted), results)
    }

    @Test
    @Config(sdk = [34])
    fun `a throwing host is skipped and the flow completes`() {
        val host = grantingHost()
        host.throwOnRequest = IllegalStateException("no permission callback")

        manager.request(host, listOf(LOCATION, NOTIFICATIONS), BackgroundPermissionRationale(), onDone)
        idle()

        assertEquals(listOf(listOf("location"), listOf("notifications")), host.requested)
        assertEquals(1, results.size)
        // The user never saw a dialog, so these are still "never asked".
        assertEquals(PROMPT, results.single()[LOCATION])
        assertEquals(PROMPT, results.single()[NOTIFICATIONS])
    }

    @Test
    @Config(sdk = [34])
    fun `overlapping requests run one after another`() {
        val host = grantingHost()
        val first = mutableListOf<Map<PermissionType, PermissionState>>()

        manager.request(host, listOf(LOCATION, NOTIFICATIONS), BackgroundPermissionRationale()) { first += it }
        manager.request(host, listOf(LOCATION, ACTIVITY_RECOGNITION), BackgroundPermissionRationale(), onDone)
        assertEquals(listOf(listOf("location")), host.requested)
        idle()

        // The second flow starts after the first finished and finds location already granted.
        assertEquals(listOf(listOf("location"), listOf("notifications"), listOf("activityRecognition")), host.requested)
        assertEquals(1, first.size)
        assertEquals(1, results.size)
        assertEquals(allGranted + (BACKGROUND_LOCATION to PROMPT), results.single())
    }

    @Test
    @Config(sdk = [34])
    fun `a request queued behind the rationale dialog runs after it`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val host = grantingHost()
        val first = mutableListOf<Map<PermissionType, PermissionState>>()

        manager.request(host, listOf(BACKGROUND_LOCATION), BackgroundPermissionRationale()) { first += it }
        idle()
        manager.request(host, listOf(ACTIVITY_RECOGNITION), BackgroundPermissionRationale(), onDone)
        idle()
        assertTrue(host.requested.isEmpty())

        latestDialog()!!.click(AlertDialog.BUTTON_NEGATIVE)

        assertEquals(listOf(listOf("activityRecognition")), host.requested)
        assertEquals(1, first.size)
        assertEquals(1, results.size)
    }

    @Test
    @Config(sdk = [34])
    fun `activity destroyed while the rationale is showing completes the flow`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val host = grantingHost(activity = controller.get())

        manager.request(host, listOf(BACKGROUND_LOCATION), BackgroundPermissionRationale(), onDone)
        idle()
        assertTrue(latestDialog()!!.isShowing)

        controller.pause().stop().destroy()
        idle()

        assertTrue(host.requested.isEmpty())
        assertEquals(1, results.size)
        assertEquals(PROMPT, results.single()[BACKGROUND_LOCATION])
    }

    @Test
    @Config(sdk = [34])
    fun `background falls back to the tracked activity when the host has none`() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val resumed = Robolectric.buildActivity(Activity::class.java).setup().get() // seen by the tracker
        val host = grantingHost(activity = null)

        manager.request(host, listOf(BACKGROUND_LOCATION), BackgroundPermissionRationale(), onDone)
        idle()
        latestDialog()!!.click(AlertDialog.BUTTON_POSITIVE)

        assertTrue(resumed.isUsable())
        assertEquals(listOf(listOf("backgroundLocation")), host.requested)
        assertEquals(1, results.size)
        assertEquals(GRANTED, results.single()[BACKGROUND_LOCATION])
    }

    @Test
    @Config(sdk = [34])
    fun `request from a background thread completes on the main thread`() {
        val host = grantingHost()

        val worker = Thread { manager.request(host, listOf(LOCATION), BackgroundPermissionRationale(), onDone) }
        worker.start()
        worker.join()
        assertTrue(results.isEmpty())
        idle()

        assertEquals(listOf(listOf("location")), host.requested)
        assertEquals(1, results.size)
        assertSame(Looper.getMainLooper().thread, resultThreads.single())
    }
}
