package com.brickssoft.locationtracking.provider

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind

/**
 * Whether the GMS or HMS backend is packaged in this APK: its SDK class is present **and** the receivers its activity
 * and geofence results are delivered to are declared in the app's merged manifest. ANDROID is always packaged.
 *
 * An app that publishes one APK per store removes the other provider's receivers with `tools:node="remove"` (README,
 * "Separate Play and AppGallery APKs"). That provider's SDK classes can still reach the APK through other libraries,
 * but without its receivers it cannot deliver activity or geofence events, so it is not packaged.
 *
 * A check that throws (anything except a VM error) reads as not packaged and is logged.
 *
 * @param classPresent whether a class can be loaded (see [DefaultProviderFactory.reflectiveClassPresent]).
 * @param receiverDeclared whether a receiver class is declared in the merged manifest (see [receiverDeclaredIn]).
 */
class ProviderPackaging(
    private val classPresent: (String) -> Boolean,
    private val receiverDeclared: (String) -> Boolean,
) {
    enum class Status { PACKAGED, SDK_MISSING, RECEIVERS_REMOVED }

    fun status(kind: ProviderKind): Status {
        val sdkClass = ProviderBundles.sdkClassName(kind) ?: return Status.PACKAGED
        if (!passes("probe $sdkClass") { classPresent(sdkClass) }) return Status.SDK_MISSING
        val receivers = ProviderBundles.receiverClassNames(kind)
        if (!receivers.all { passes("look up receiver $it") { receiverDeclared(it) } }) return Status.RECEIVERS_REMOVED
        return Status.PACKAGED
    }

    fun isPackaged(kind: ProviderKind): Boolean = status(kind) == Status.PACKAGED

    private inline fun passes(what: String, block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "$what failed: ${t.javaClass.name}: ${t.message}", t)
            false
        }

    companion object {
        private const val TAG = "LT.ProviderPackaging"

        /** `PackageManager.getReceiverInfo` for a receiver of this app: true if the merged manifest declares it. */
        fun receiverDeclaredIn(context: Context): (String) -> Boolean = { name ->
            try {
                @Suppress("DEPRECATION") // the ComponentInfoFlags overload needs API 33
                context.packageManager.getReceiverInfo(
                    ComponentName(context.packageName, name),
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                )
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
