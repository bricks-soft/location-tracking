package com.brickssoft.locationtracking.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.brickssoft.locationtracking.R
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.config.NotificationPriority
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger

/**
 * Builds the foreground-service notification and its channel from [NotificationConfig].
 *
 * - Channel id and name come from the config. The default name and text use the plugin string resources
 *   (`lt_notification_channel_name`, `lt_notification_text`), so an app can translate them.
 * - Channel importance follows `priority`: min → MIN, low → LOW, default → DEFAULT, high/max → HIGH.
 *   **Android fixes a channel's importance when the channel is first created**; the app cannot change it
 *   afterwards (only the user can). A later `priority` change therefore affects only the notification's own
 *   priority (used below API 26). To apply a new importance, also change `channelId`.
 * - The small icon is resolved from `drawable/name`, `mipmap/name` or `name`; an unknown or adaptive (launcher)
 *   icon falls back to `lt_ic_notification`, because such a small icon shows as a white shape or breaks
 *   `startForeground`.
 * - The notification is ongoing, silent, alerts only once and is shown immediately
 *   (`FOREGROUND_SERVICE_IMMEDIATE`). Tapping it opens the app; up to [Constants.MAX_NOTIFICATION_ACTIONS]
 *   buttons broadcast to [NotificationActionReceiver].
 */
internal class NotificationFactory(context: Context) {
    private val context: Context = context.applicationContext ?: context

    /** Creates the channel, or updates its name if it exists. No-op below API 26. */
    fun ensureChannel(config: NotificationConfig) {
        val channel = NotificationChannelCompat.Builder(channelId(config), importanceFor(config.priority))
            .setName(channelName(config))
            .setShowBadge(false)
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Ensures the channel, then builds the notification. */
    fun build(config: NotificationConfig): Notification {
        ensureChannel(config)
        return compose(config)
    }

    /**
     * The notification for the first `startForeground` of a service command. It is built without loading the config
     * (no config store, no SharedPreferences, no JSON), so it is ready within milliseconds on a cold start:
     * - [spec] is the notification fields that the sender copied into the start command (channel id and name,
     *   priority, title, text, small icon, color; no action buttons, no large icon). Its channel is created with the
     *   configured importance only if it does not exist yet (Android fixes a channel's importance at creation, so this
     *   first creation must use the configured priority); an existing channel is left unchanged.
     * - Without [spec] (a restart with a null intent), the default channel ([NotificationConfig.DEFAULT_CHANNEL_ID],
     *   created only if it does not exist), the app label as title, `lt_notification_text` as text and the plugin icon:
     *   the same content as [build] of `NotificationConfig()`.
     *
     * If [spec] cannot be built, the defaults are used. The service replaces this notification with the configured
     * one (same id) once the config is loaded.
     */
    fun buildInitial(spec: NotificationConfig? = null): Initial {
        if (spec != null) {
            try {
                ensureChannelExists(spec)
                return Initial(spec, compose(spec))
            } catch (e: Exception) {
                Logger.e(TAG, "invalid notification fields in the start command; using the defaults", e)
            }
        }
        val defaults = NotificationConfig()
        ensureChannelExists(defaults)
        return Initial(defaults, compose(defaults, R.drawable.lt_ic_notification))
    }

    /** A notification from [buildInitial] and the config whose content it shows. */
    data class Initial(val config: NotificationConfig, val notification: Notification)

    /** Creates the channel of [config] if it does not exist; an existing channel is not changed. No-op below API 26. */
    private fun ensureChannelExists(config: NotificationConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (NotificationManagerCompat.from(context).getNotificationChannelCompat(channelId(config)) == null) {
            ensureChannel(config)
        }
    }

    /** The notification for [config]; its channel must exist. [smallIcon] skips the icon lookup when given. */
    private fun compose(config: NotificationConfig, @DrawableRes smallIcon: Int? = null): Notification {
        val builder = NotificationCompat.Builder(context, channelId(config))
            .setContentTitle(title(config))
            .setContentText(text(config))
            .setSmallIcon(smallIcon ?: resolveSmallIcon(config.smallIcon))
            .setPriority(compatPriorityFor(config.priority))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        loadLargeIcon(config.largeIcon)?.let { builder.setLargeIcon(it) }
        parseColor(config.color)?.let { builder.setColor(it) }
        contentIntent()?.let { builder.setContentIntent(it) }
        config.actions.take(Constants.MAX_NOTIFICATION_ACTIONS).forEachIndexed { index, action ->
            builder.addAction(0, action.label, actionIntent(index, action.id))
        }
        return builder.build()
    }

    /** [build], falling back to the default config if the configured notification cannot be built. */
    fun buildSafely(config: NotificationConfig): Notification = try {
        build(config)
    } catch (e: Exception) {
        Logger.e(TAG, "invalid notification config; using the defaults", e)
        build(NotificationConfig())
    }

    /**
     * `name` / `drawable/name` / `mipmap/name` → resource id, or [R.drawable.lt_ic_notification] if the name is
     * unknown or names an adaptive launcher icon (it renders as a blank shape and crashes some Android 8.0 builds).
     */
    @DrawableRes
    fun resolveSmallIcon(spec: String?): Int {
        val id = resolveImage(spec)
        return when {
            id == 0 -> {
                if (!spec.isNullOrBlank()) Logger.w(TAG, "small icon '$spec' not found; using the plugin icon")
                R.drawable.lt_ic_notification
            }
            id != R.drawable.lt_ic_notification && isAdaptiveIcon(id) -> {
                Logger.w(TAG, "small icon '$spec' is an adaptive launcher icon; using the plugin icon")
                R.drawable.lt_ic_notification
            }
            else -> id
        }
    }

    private fun isAdaptiveIcon(@DrawableRes id: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            Api26.isAdaptive(ResourcesCompat.getDrawable(context.resources, id, context.theme))
        } catch (e: Exception) {
            false
        }
    }

    /** `name` / `drawable/name` / `mipmap/name` (an optional leading `@` is ignored) → resource id, or 0. */
    @SuppressLint("DiscouragedApi") // names come from JS config, so they can only be resolved at runtime
    fun resolveImage(spec: String?): Int {
        val value = spec?.trim()?.removePrefix("@").orEmpty()
        if (value.isEmpty()) return 0
        val slash = value.indexOf('/')
        val types = if (slash >= 0) listOf(value.substring(0, slash)) else IMAGE_TYPES
        val name = if (slash >= 0) value.substring(slash + 1) else value
        if (name.isEmpty() || types.any { it !in IMAGE_TYPES }) return 0
        val resources = context.resources
        for (type in types) {
            val id = try {
                resources.getIdentifier(name, type, context.packageName)
            } catch (e: Exception) {
                0
            }
            if (id != 0) return id
        }
        return 0
    }

    private fun loadLargeIcon(spec: String?): Bitmap? {
        if (spec.isNullOrBlank()) return null
        val id = resolveImage(spec)
        if (id == 0) {
            Logger.w(TAG, "large icon '$spec' not found")
            return null
        }
        return try {
            // decodeResource returns null for vector and adaptive drawables; render those instead.
            BitmapFactory.decodeResource(context.resources, id)
                ?: ResourcesCompat.getDrawable(context.resources, id, context.theme)?.toBitmap()
        } catch (e: Exception) {
            Logger.w(TAG, "could not load large icon '$spec'", e)
            null
        }
    }

    private fun title(config: NotificationConfig): CharSequence =
        config.title?.takeIf { it.isNotBlank() } ?: appLabel()

    private fun text(config: NotificationConfig): CharSequence =
        if (config.text.isBlank() || config.text == NotificationConfig.DEFAULT_TEXT) {
            context.getString(R.string.lt_notification_text)
        } else {
            config.text
        }

    private fun channelId(config: NotificationConfig): String =
        config.channelId.ifBlank { NotificationConfig.DEFAULT_CHANNEL_ID }

    private fun channelName(config: NotificationConfig): CharSequence =
        if (config.channelName.isBlank() || config.channelName == NotificationConfig.DEFAULT_CHANNEL_NAME) {
            context.getString(R.string.lt_notification_channel_name)
        } else {
            config.channelName
        }

    private fun appLabel(): CharSequence = try {
        context.applicationInfo.loadLabel(context.packageManager)
    } catch (e: Exception) {
        context.packageName
    }

    /** Opens the app like the launcher does (brings an existing task to the front). */
    private fun contentIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        // Without the package the intent matches the launcher's, so an existing task is resumed, not duplicated.
        launch.setPackage(null)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(context, Constants.RC_NOTIFICATION_CONTENT, launch, Constants.piImmutable())
    }

    private fun actionIntent(index: Int, id: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(Constants.ACTION_NOTIFICATION_ACTION)
            .putExtra(Constants.EXTRA_ACTION_ID, id)
        return PendingIntent.getBroadcast(
            context,
            Constants.RC_NOTIFICATION_ACTION_BASE + index,
            intent,
            Constants.piImmutable(),
        )
    }

    /** Keeps the API 26 class reference out of code that is verified on older devices. */
    @RequiresApi(Build.VERSION_CODES.O)
    internal object Api26 {
        fun isAdaptive(drawable: Drawable?): Boolean = drawable is AdaptiveIconDrawable
    }

    companion object {
        private const val TAG = "LT.Notification"
        private val IMAGE_TYPES = listOf("drawable", "mipmap")

        /** Channel importance for [priority]; applied only when the channel is created. */
        fun importanceFor(priority: NotificationPriority): Int = when (priority) {
            NotificationPriority.MIN -> NotificationManagerCompat.IMPORTANCE_MIN
            NotificationPriority.LOW -> NotificationManagerCompat.IMPORTANCE_LOW
            NotificationPriority.DEFAULT -> NotificationManagerCompat.IMPORTANCE_DEFAULT
            NotificationPriority.HIGH, NotificationPriority.MAX -> NotificationManagerCompat.IMPORTANCE_HIGH
        }

        /** Notification priority for [priority] (effective below API 26). */
        fun compatPriorityFor(priority: NotificationPriority): Int = when (priority) {
            NotificationPriority.MIN -> NotificationCompat.PRIORITY_MIN
            NotificationPriority.LOW -> NotificationCompat.PRIORITY_LOW
            NotificationPriority.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
            NotificationPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
            NotificationPriority.MAX -> NotificationCompat.PRIORITY_MAX
        }

        /** `#RRGGBB` / `#AARRGGBB` / color name → color int; null (logged) if blank or invalid. */
        @ColorInt
        fun parseColor(value: String?): Int? {
            if (value.isNullOrBlank()) return null
            return try {
                Color.parseColor(value.trim())
            } catch (e: IllegalArgumentException) {
                Logger.w(TAG, "invalid notification color '$value'; ignored")
                null
            }
        }
    }
}
