package com.brickssoft.locationtracking.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.engine.TrackingEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resumes tracking after a reboot (`BOOT_COMPLETED`, both `QUICKBOOT_POWERON` variants) or an app update
 * (`MY_PACKAGE_REPLACED`).
 *
 * - Tracking enabled and `app.startOnBoot` → `engine.restore("boot" | "package_replaced")`.
 * - Tracking enabled but not `startOnBoot` → the persisted `enabled` flag is cleared, because tracking does not
 *   resume and the state must say so.
 * - Tracking not enabled → nothing.
 *
 * Devices that send several boot broadcasts are handled once per process.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = reasonFor(intent.action) ?: return
        val deps = try {
            ServiceDeps.from(context)
        } catch (e: Exception) {
            Logger.e(TAG, "components unavailable", e)
            return
        }
        if (reason == REASON_BOOT && !bootHandled.compareAndSet(false, true)) {
            Logger.d(TAG, "${intent.action}: boot already handled")
            return
        }
        // Null when onReceive is called directly instead of by the system.
        val pending: PendingResult? = goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pending?.finish() }
        val work = deps.scope.launch {
            try {
                handle(reason, deps.configStore) { deps.engine.value }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "$reason: restoring tracking failed", e)
            }
        }
        // Release the broadcast before its ~10 s limit even if the restore takes longer; the restore keeps
        // running (by then its foreground service keeps the process alive).
        val watchdog = deps.scope.launch {
            delay(FINISH_BUDGET_MS)
            Logger.w(TAG, "$reason: restore still running; releasing the broadcast")
            finish()
        }
        // Also runs if the work is cancelled before it starts, unlike a finally block inside it.
        work.invokeOnCompletion {
            watchdog.cancel()
            finish()
        }
    }

    /** What [handle] did. */
    internal enum class Outcome { RESTORED, DISABLED, IGNORED }

    internal companion object {
        private const val TAG = "LT.Boot"
        const val REASON_BOOT = "boot"
        const val REASON_PACKAGE_REPLACED = "package_replaced"

        /** How long the broadcast is held for the restore (receivers must finish within about 10 s). */
        const val FINISH_BUDGET_MS = 8_000L

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )

        private val bootHandled = AtomicBoolean(false)

        /** `"boot"`, `"package_replaced"`, or null for any other action. */
        fun reasonFor(action: String?): String? = when (action) {
            in BOOT_ACTIONS -> REASON_BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> REASON_PACKAGE_REPLACED
            else -> null
        }

        /** The boot decision; [engine] is only resolved when tracking is restored. */
        suspend fun handle(reason: String, configStore: ConfigStore, engine: () -> TrackingEngine): Outcome {
            if (!configStore.runtime.value.enabled) {
                Logger.d(TAG, "$reason: tracking is not enabled")
                return Outcome.IGNORED
            }
            if (!configStore.config.value.app.startOnBoot) {
                configStore.updateRuntime { it.copy(enabled = false) }
                Logger.i(TAG, "$reason: tracking was enabled but app.startOnBoot is false; tracking does not resume")
                return Outcome.DISABLED
            }
            Logger.i(TAG, "$reason: restoring tracking")
            engine().restore(reason)
            return Outcome.RESTORED
        }

        /** Test hook: forgets that a boot broadcast was handled in this process. */
        @VisibleForTesting
        fun resetBootHandled() = bootHandled.set(false)
    }
}
