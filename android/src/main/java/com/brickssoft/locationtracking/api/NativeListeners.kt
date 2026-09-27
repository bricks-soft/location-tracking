// STUB — owned by Unit 5 (Companion native API).
package com.brickssoft.locationtracking.api

import android.content.Context
import com.brickssoft.locationtracking.core.Components

/**
 * Installs the manifest-declared [LocationTrackingListener]s and connects every listener to the plugin
 * (`components.recordHooks` for records, `components.events` for events), delivering on the `LT-native` thread.
 *
 * Called once per [Components] instance from `Components.bootstrap()` (tests reset Components, so a later call must
 * replace the previous instance's subscriptions).
 *
 * SCAFFOLD STUB: does nothing.
 */
internal object NativeListeners {
    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context, components: Components) = Unit
}
