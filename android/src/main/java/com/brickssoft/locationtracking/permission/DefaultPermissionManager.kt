// STUB — owned by Unit 15 (Permissions + settings). Replace this implementation.
package com.brickssoft.locationtracking.permission

import android.content.Context
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale

/** API-aware permission status and requests. Stub: reports PROMPT for everything and requests nothing. */
@Suppress("unused")
class DefaultPermissionManager(private val context: Context) : PermissionManager {
    override fun status(): Map<PermissionType, PermissionState> =
        PermissionType.entries.associateWith { PermissionState.PROMPT }

    override fun hasForegroundLocation(): Boolean = false

    override fun hasBackgroundLocation(): Boolean = false

    override fun hasActivityRecognition(): Boolean = false

    override fun hasNotifications(): Boolean = false

    override fun request(
        host: PermissionHost,
        types: List<PermissionType>,
        rationale: BackgroundPermissionRationale,
        onDone: (Map<PermissionType, PermissionState>) -> Unit,
    ) = onDone(status())
}
