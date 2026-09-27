package com.brickssoft.locationtracking.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.R
import com.brickssoft.locationtracking.config.NotificationActionButton
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.config.NotificationPriority
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.testing.FakeLogStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class NotificationFactoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = app.getSystemService(NotificationManager::class.java)
    private val factory = NotificationFactory(app)
    private val logs = FakeLogStore()

    @Before
    fun setUp() {
        Logger.sink = logs
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `default config builds the plugin notification`() {
        val n = factory.build(NotificationConfig())

        assertEquals(NotificationConfig.DEFAULT_CHANNEL_ID, n.channelId)
        val channel = notificationManager.getNotificationChannel(NotificationConfig.DEFAULT_CHANNEL_ID)
        assertEquals(app.getString(R.string.lt_notification_channel_name), channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(app.applicationInfo.loadLabel(app.packageManager).toString(), n.title)
        assertEquals(app.getString(R.string.lt_notification_text), n.text)
        assertEquals(R.drawable.lt_ic_notification, n.smallIcon.resId)
        assertNull(n.getLargeIcon())
        assertEquals(Notification.COLOR_DEFAULT, n.color)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(
            Notification.FOREGROUND_SERVICE_IMMEDIATE,
            ReflectionHelpers.getField<Int>(n, "mFgsDeferBehavior"),
        )
        assertEquals(Notification.CATEGORY_SERVICE, n.category)
        assertTrue(n.actions.isNullOrEmpty())
        assertNull("no launcher activity in the library test app", n.contentIntent)
    }

    @Test
    fun `configured values are applied`() {
        val config = NotificationConfig(
            title = "Driver app",
            text = "On duty",
            smallIcon = "lt_ic_notification",
            largeIcon = "drawable/lt_ic_notification",
            color = "#FF5722",
            priority = NotificationPriority.HIGH,
            channelId = "trips",
            channelName = "Trips",
        )

        val n = factory.build(config)

        assertEquals("trips", n.channelId)
        val channel = notificationManager.getNotificationChannel("trips")
        assertEquals("Trips", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("Driver app", n.title)
        assertEquals("On duty", n.text)
        assertEquals(R.drawable.lt_ic_notification, n.smallIcon.resId)
        assertNotNull(n.getLargeIcon())
        assertEquals(Color.parseColor("#FF5722"), n.color)
        @Suppress("DEPRECATION")
        assertEquals(NotificationCompat.PRIORITY_HIGH, n.priority)
    }

    @Test
    fun `blank title and text fall back to the app label and the string resource`() {
        val n = factory.build(NotificationConfig(title = " ", text = ""))

        assertEquals(app.applicationInfo.loadLabel(app.packageManager).toString(), n.title)
        assertEquals(app.getString(R.string.lt_notification_text), n.text)
    }

    @Test
    fun `priority maps to channel importance and notification priority`() {
        val expected = mapOf(
            NotificationPriority.MIN to (NotificationManager.IMPORTANCE_MIN to NotificationCompat.PRIORITY_MIN),
            NotificationPriority.LOW to (NotificationManager.IMPORTANCE_LOW to NotificationCompat.PRIORITY_LOW),
            NotificationPriority.DEFAULT to
                (NotificationManager.IMPORTANCE_DEFAULT to NotificationCompat.PRIORITY_DEFAULT),
            NotificationPriority.HIGH to (NotificationManager.IMPORTANCE_HIGH to NotificationCompat.PRIORITY_HIGH),
            NotificationPriority.MAX to (NotificationManager.IMPORTANCE_HIGH to NotificationCompat.PRIORITY_MAX),
        )
        assertEquals(NotificationPriority.entries.toSet(), expected.keys)

        for ((priority, pair) in expected) {
            val (importance, compatPriority) = pair
            assertEquals(priority.name, importance, NotificationFactory.importanceFor(priority))
            assertEquals(priority.name, compatPriority, NotificationFactory.compatPriorityFor(priority))

            val channelId = "channel_${priority.wire}"
            factory.build(NotificationConfig(priority = priority, channelId = channelId))
            assertEquals(priority.name, importance, notificationManager.getNotificationChannel(channelId).importance)
        }
    }

    @Test
    fun `image names resolve from drawable and mipmap specs`() {
        val icon = R.drawable.lt_ic_notification
        assertEquals(icon, factory.resolveImage("drawable/lt_ic_notification"))
        assertEquals(icon, factory.resolveImage("@drawable/lt_ic_notification"))
        assertEquals(icon, factory.resolveImage(" lt_ic_notification "))

        assertEquals(0, factory.resolveImage("mipmap/lt_ic_notification"))
        assertEquals(0, factory.resolveImage("string/lt_notification_text"))
        assertEquals(0, factory.resolveImage("drawable/"))
        assertEquals(0, factory.resolveImage("does_not_exist"))
        assertEquals(0, factory.resolveImage(""))
        assertEquals(0, factory.resolveImage(null))
    }

    @Test
    fun `unknown small icon falls back to the plugin icon`() {
        val n = factory.build(NotificationConfig(smallIcon = "mipmap/does_not_exist", largeIcon = "nope"))

        assertEquals(R.drawable.lt_ic_notification, n.smallIcon.resId)
        assertNull(n.getLargeIcon())
        assertTrue(logs.lines.any { it.level == LogLevel.WARN && it.message.contains("mipmap/does_not_exist") })
    }

    @Test
    fun `adaptive launcher icons are rejected as small icons`() {
        val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.WHITE))

        assertTrue(NotificationFactory.Api26.isAdaptive(adaptive))
        assertFalse(NotificationFactory.Api26.isAdaptive(ColorDrawable(Color.RED)))
        assertFalse(NotificationFactory.Api26.isAdaptive(null))
    }

    @Test
    fun `other resolvable drawables are used as small icons`() {
        // A vector drawable merged from appcompat: resolvable and not adaptive.
        val id = factory.resolveImage("drawable/abc_ic_ab_back_material")
        assertTrue(id != 0)

        assertEquals(id, factory.resolveSmallIcon("drawable/abc_ic_ab_back_material"))
    }

    @Test
    fun `colors are parsed and invalid ones ignored`() {
        assertEquals(Color.RED, NotificationFactory.parseColor("#FF0000"))
        assertEquals(0x80112233.toInt(), NotificationFactory.parseColor("#80112233"))
        assertNull(NotificationFactory.parseColor("not-a-color"))
        assertNull(NotificationFactory.parseColor("#12"))
        assertNull(NotificationFactory.parseColor(""))
        assertNull(NotificationFactory.parseColor(null))

        val n = factory.build(NotificationConfig(color = "#zzzzzz"))

        assertEquals(Notification.COLOR_DEFAULT, n.color)
    }

    @Test
    fun `at most three action buttons broadcast their id to the receiver`() {
        val buttons = listOf(
            NotificationActionButton("pause", "Pause"),
            NotificationActionButton("stop", "Stop"),
            NotificationActionButton("sos", "SOS"),
            NotificationActionButton("extra", "Extra"),
        )

        val n = factory.build(NotificationConfig(actions = buttons))

        assertEquals(Constants.MAX_NOTIFICATION_ACTIONS, n.actions.size)
        n.actions.forEachIndexed { index, action ->
            assertEquals(buttons[index].label, action.title.toString())
            val pi = shadowOf(action.actionIntent)
            assertTrue(pi.isBroadcast)
            assertTrue(pi.isImmutable)
            assertEquals(Constants.RC_NOTIFICATION_ACTION_BASE + index, pi.requestCode)
            val intent = pi.savedIntent
            assertEquals(ComponentName(app, NotificationActionReceiver::class.java), intent.component)
            assertEquals(Constants.ACTION_NOTIFICATION_ACTION, intent.action)
            assertEquals(buttons[index].id, intent.getStringExtra(Constants.EXTRA_ACTION_ID))
        }
    }

    @Test
    fun `tapping the notification opens the app like the launcher`() {
        val launcher = ComponentName(app, "com.example.MainActivity")
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(launcher)
        packageManager.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )

        val n = factory.build(NotificationConfig())

        val pi = shadowOf(n.contentIntent)
        assertTrue(pi.isActivity)
        assertTrue(pi.isImmutable)
        assertEquals(Constants.RC_NOTIFICATION_CONTENT, pi.requestCode)
        assertTrue(pi.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = pi.savedIntent
        assertEquals(launcher, intent.component)
        assertNull(intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
    }

    @Test
    fun `channel name updates keep the channel id`() {
        factory.ensureChannel(NotificationConfig(channelName = "Old"))
        factory.ensureChannel(NotificationConfig(channelName = "New"))

        val channels = notificationManager.notificationChannels.filter {
            it.id == NotificationConfig.DEFAULT_CHANNEL_ID
        }
        assertEquals(1, channels.size)
        assertEquals("New", channels.single().name.toString())
    }

    @Test
    @Config(sdk = [29])
    fun `builds on API 29`() {
        val n = factory.build(NotificationConfig(title = "T", actions = listOf(NotificationActionButton("a", "A"))))

        assertEquals("T", n.title)
        assertEquals(1, n.actions.size)
        assertTrue(shadowOf(n.actions[0].actionIntent).isImmutable)
    }

    @Test
    fun `initial notification has the default content and creates the default channel`() {
        val initial = factory.buildInitial()
        val n = initial.notification

        assertEquals(NotificationConfig(), initial.config)
        assertEquals(NotificationConfig.DEFAULT_CHANNEL_ID, n.channelId)
        val channel = notificationManager.getNotificationChannel(NotificationConfig.DEFAULT_CHANNEL_ID)
        assertEquals(app.getString(R.string.lt_notification_channel_name), channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(app.applicationInfo.loadLabel(app.packageManager).toString(), n.title)
        assertEquals(app.getString(R.string.lt_notification_text), n.text)
        assertEquals(R.drawable.lt_ic_notification, n.smallIcon.resId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(
            Notification.FOREGROUND_SERVICE_IMMEDIATE,
            ReflectionHelpers.getField<Int>(n, "mFgsDeferBehavior"),
        )
        assertTrue(n.actions.isNullOrEmpty())
    }

    @Test
    fun `initial notification does not rename an existing default channel`() {
        factory.build(NotificationConfig(channelName = "Shift tracking"))

        factory.buildInitial()

        val channel = notificationManager.getNotificationChannel(NotificationConfig.DEFAULT_CHANNEL_ID)
        assertEquals("Shift tracking", channel.name.toString())
    }

    @Test
    fun `initial notification from command fields creates the channel with the configured importance`() {
        val spec = NotificationConfig(
            title = "Field Force",
            text = "Shift tracking is on",
            color = "#3366FF",
            priority = NotificationPriority.HIGH,
            channelId = NotificationConfig.DEFAULT_CHANNEL_ID,
        )

        val initial = factory.buildInitial(spec)

        assertEquals(spec, initial.config)
        val channel = notificationManager.getNotificationChannel(NotificationConfig.DEFAULT_CHANNEL_ID)
        assertEquals("the first creation fixes the importance", NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("Field Force", initial.notification.title)
        assertEquals("Shift tracking is on", initial.notification.text)
        assertEquals(Color.parseColor("#3366FF"), initial.notification.color)
    }

    @Test
    fun `initial notification from command fields uses a custom channel and creates no default channel`() {
        val initial = factory.buildInitial(NotificationConfig(channelId = "shift", channelName = "Shift"))

        assertEquals("shift", initial.notification.channelId)
        assertEquals("Shift", notificationManager.getNotificationChannel("shift").name.toString())
        assertNull(notificationManager.getNotificationChannel(NotificationConfig.DEFAULT_CHANNEL_ID))
    }

    @Test
    fun `initial notification from command fields leaves an existing channel unchanged`() {
        factory.build(NotificationConfig(channelId = "shift", channelName = "Shift", priority = NotificationPriority.LOW))

        factory.buildInitial(NotificationConfig(channelId = "shift", channelName = "Renamed"))

        val channel = notificationManager.getNotificationChannel("shift")
        assertEquals("Shift", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `initial notification with a blank channel id in the command fields uses the default channel`() {
        val initial = factory.buildInitial(NotificationConfig(channelId = ""))

        assertEquals(NotificationConfig.DEFAULT_CHANNEL_ID, initial.notification.channelId)
        assertNotNull(notificationManager.getNotificationChannel(NotificationConfig.DEFAULT_CHANNEL_ID))
    }
}
