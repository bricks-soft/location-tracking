package com.brickssoft.locationtracking.provider.android

import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.ActivityBackend

/**
 * The platform has no activity recognition without GMS or HMS: [isSupported] is false and [start] always returns
 * false, so motion detection relies on location distance (`stationaryRadius`) alone.
 */
class AndroidActivityBackend : ActivityBackend {
    override val kind: ProviderKind = ProviderKind.ANDROID
    override val isSupported: Boolean = false

    override fun start(intervalMs: Long): Boolean {
        Logger.d(TAG, "activity recognition is not supported by the android backend")
        return false
    }

    override fun stop() = Unit

    private companion object {
        const val TAG = "LT.AndroidActivity"
    }
}
