package com.brickssoft.locationtracking.service

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.NotificationActionButton
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.testing.RecordingEventBus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class NotificationActionReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val env = ServiceTestEnv()

    @Before
    fun setUp() {
        env.install()
    }

    @After
    fun tearDown() {
        env.uninstall()
        Components.reset()
    }

    private fun actionIntent(id: String?) = Intent(app, NotificationActionReceiver::class.java)
        .setAction(Constants.ACTION_NOTIFICATION_ACTION)
        .apply { if (id != null) putExtra(Constants.EXTRA_ACTION_ID, id) }

    @Test
    fun `valid action intent emits the id`() {
        val events = RecordingEventBus()

        assertTrue(NotificationActionReceiver.dispatch(actionIntent("pause"), events))

        assertEquals(listOf(TrackingEvent.NotificationAction("pause")), events.events)
    }

    @Test
    fun `invalid intents emit nothing`() {
        val events = RecordingEventBus()

        assertFalse(NotificationActionReceiver.dispatch(actionIntent(null), events))
        assertFalse(NotificationActionReceiver.dispatch(actionIntent(""), events))
        assertFalse(NotificationActionReceiver.dispatch(actionIntent("pause").setAction("other"), events))

        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `onReceive emits on the components event bus`() {
        NotificationActionReceiver().onReceive(app, actionIntent("stop"))

        assertEquals(listOf(TrackingEvent.NotificationAction("stop")), env.events.events)
    }

    @Test
    fun `tapping a notification button delivers its id to the event bus`() {
        val buttons = listOf(NotificationActionButton("pause", "Pause"), NotificationActionButton("sos", "SOS"))
        val notification = NotificationFactory(app).build(NotificationConfig(actions = buttons))

        notification.actions[1].actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(TrackingEvent.NotificationAction("sos")), env.events.events)
    }

    @Test
    fun `works with the real components`() {
        env.uninstall()
        val received = CopyOnWriteArrayList<TrackingEvent>()
        Components.get(app).events.subscribe { received += it }

        NotificationActionReceiver().onReceive(app, actionIntent("pause"))

        assertEquals(listOf(TrackingEvent.NotificationAction("pause")), received)
    }
}
