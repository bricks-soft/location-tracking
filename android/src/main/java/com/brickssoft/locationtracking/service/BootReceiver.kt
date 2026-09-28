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
 * - Tracking enabled but not `startOnBoot` → `engine.endWithoutRestore("reboot" | "package_replaced")` records
 *   `tracking_stop` with that reason (so the server learns why heartbeats stopped) and clears `enabled`.
 * - Tracking not enabled → nothing.
 *
 * Devices that send several boot broadcasts are handled once per process.
 *
 * **Boot count gate.** The receiver is exported (plugin manifest) and `QUICKBOOT_POWERON` is not a protected
 * broadcast, so any app, or `adb shell am broadcast`, can send it without a reboot. A boot action is therefore
 * handled only when `Settings.Global.BOOT_COUNT` (via `Clock.bootCount()`) shows a boot that has not been seen yet;
 * it is ignored when the current boot count
 * - equals the boot count of the last boot broadcast this receiver handled (stored in [BootCountStore] after handling,
 *   also when tracking was not enabled or the restore failed), or
 * - equals the boot count during which [DefaultServiceController] last started the location service
 *   ([BootCountStore.lastServiceStart]): a tracking session was already started or restored during this boot, so a
 *   boot restore would duplicate `tracking_start` (or end a running audit trail with `tracking_stop: reboot`). This
 *   also covers an app that was installed and started without a reboot since.
 *
 * If the boot count cannot be read (-1), every boot action is handled as before. `MY_PACKAGE_REPLACED` is not gated.
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
        val bootCounts = BootCountStore(context)
        val work = deps.scope.launch {
            try {
                if (reason == REASON_BOOT) {
                    handleBoot(intent.action, deps, bootCounts)
                } else {
                    handle(reason, deps.configStore) { deps.engine.value }
                }
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

    /** Applies the boot count gate, then [handle]; stores the boot count once it was handled (also on failure). */
    private suspend fun handleBoot(action: String?, deps: ServiceDeps, bootCounts: BootCountStore) {
        val bootCount = deps.clock.bootCount()
        ignoredBootReason(bootCount, bootCounts.lastHandled(), bootCounts.lastServiceStart())?.let {
            Logger.i(TAG, "$action ignored: $it")
            return
        }
        var failure: Exception? = null
        try {
            handle(REASON_BOOT, deps.configStore) { deps.engine.value }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }
        if (bootCount >= 0) bootCounts.setLastHandled(bootCount)
        failure?.let { throw it }
    }

    /** What [handle] did. */
    internal enum class Outcome { RESTORED, DISABLED, IGNORED }

    internal companion object {
        private const val TAG = "LT.Boot"
        const val REASON_BOOT = "boot"
        const val STOP_REASON_REBOOT = "reboot"
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

        /**
         * Why a boot action must be ignored, or null to handle it. [bootCount] is the current `BOOT_COUNT` (-1 if it
         * cannot be read: always handled), [lastHandled] the boot count of the last handled boot broadcast (null if
         * none), [lastServiceStart] the boot count during which the location service was last started (null if none).
         */
        fun ignoredBootReason(bootCount: Int, lastHandled: Int?, lastServiceStart: Int?): String? = when {
            bootCount < 0 -> null
            bootCount == lastHandled -> "boot $bootCount was already handled (no reboot since)"
            bootCount == lastServiceStart -> "tracking was already started during boot $bootCount (no reboot since)"
            else -> null
        }

        /** `tracking_stop` reason when tracking is not resumed: `"reboot"` or `"package_replaced"`. */
        fun stopReasonFor(reason: String): String = if (reason == REASON_BOOT) STOP_REASON_REBOOT else reason

        /** The boot decision; [engine] is only resolved when tracking was enabled. */
        suspend fun handle(reason: String, configStore: ConfigStore, engine: () -> TrackingEngine): Outcome {
            if (!configStore.runtime.value.enabled) {
                Logger.d(TAG, "$reason: tracking is not enabled")
                return Outcome.IGNORED
            }
            if (!configStore.config.value.app.startOnBoot) {
                Logger.i(TAG, "$reason: tracking was enabled but app.startOnBoot is false; tracking does not resume")
                try {
                    // Clears `enabled` together with the tracking_stop record. It deliberately does nothing when a
                    // session is already running in this process (e.g. START_STICKY or start() before this broadcast).
                    engine().endWithoutRestore(stopReasonFor(reason))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Recording failed: tracking does not resume, so the persisted state must still say so.
                    configStore.updateRuntime { it.copy(enabled = false) }
                    throw e
                }
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
