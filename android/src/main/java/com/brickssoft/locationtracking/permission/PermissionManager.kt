package com.brickssoft.locationtracking.permission

import android.app.Activity
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale

/** JS `PermissionType`; [alias] is the `@CapacitorPlugin` permission alias. */
enum class PermissionType(val alias: String) {
    LOCATION("location"),
    BACKGROUND_LOCATION("backgroundLocation"),
    ACTIVITY_RECOGNITION("activityRecognition"),
    NOTIFICATIONS("notifications"),
    ;

    companion object {
        fun fromAlias(alias: String?): PermissionType? = entries.firstOrNull { it.alias == alias }
    }
}

/** Capacitor `PermissionState`; [js] is the JS value. */
enum class PermissionState(val js: String) {
    GRANTED("granted"),
    DENIED("denied"),
    PROMPT("prompt"),
    PROMPT_WITH_RATIONALE("prompt-with-rationale"),
}

/** Implemented by the bridge (U2) on top of the Capacitor plugin. */
interface PermissionHost {
    val activity: Activity?

    /** Wraps `requestPermissionForAliases` + `@PermissionCallback`; [onDone] runs on the main thread. */
    fun requestAliases(aliases: List<String>, onDone: () -> Unit)
}

interface PermissionManager {
    /** API-level aware: background < 29 = foreground; activity recognition < 29 granted; notifications < 33 granted. */
    fun status(): Map<PermissionType, PermissionState>

    fun hasForegroundLocation(): Boolean

    fun hasBackgroundLocation(): Boolean

    fun hasActivityRecognition(): Boolean

    fun hasNotifications(): Boolean

    /** Main thread. Background location is always requested last. */
    fun request(
        host: PermissionHost,
        types: List<PermissionType>,
        rationale: BackgroundPermissionRationale,
        onDone: (Map<PermissionType, PermissionState>) -> Unit,
    )
}
