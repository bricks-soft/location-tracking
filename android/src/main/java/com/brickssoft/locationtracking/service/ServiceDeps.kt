package com.brickssoft.locationtracking.service

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.engine.TrackingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The collaborators that [LocationTrackingService], [BootReceiver] and [NotificationActionReceiver] take from
 * [Components]. [engine] is lazy so that components the caller does not need are never constructed.
 *
 * Tests set [override] to inject fakes instead of the real components.
 */
internal class ServiceDeps(
    val configStore: ConfigStore,
    val events: EventBus,
    val scope: CoroutineScope,
    val engine: Lazy<TrackingEngine>,
) {
    /** Launches [block] on the engine in [scope]; failures are logged, never thrown. */
    fun launchEngine(tag: String, what: String, block: suspend TrackingEngine.() -> Unit): Job = scope.launch {
        try {
            engine.value.block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(tag, "$what failed", e)
        }
    }

    companion object {
        /** Test hook: when set, [from] returns its result instead of reading [Components]. */
        @VisibleForTesting
        @Volatile
        var override: ((Context) -> ServiceDeps)? = null

        fun from(context: Context): ServiceDeps {
            override?.let { return it(context) }
            val components = Components.get(context)
            return ServiceDeps(
                configStore = components.configStore,
                events = components.events,
                scope = components.scope,
                engine = lazy { components.engine },
            )
        }
    }
}
