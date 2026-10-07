package com.brickssoft.locationtracking.provider

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind

/**
 * Whether the GMS or HMS backend may be used in this APK. ANDROID always may. GMS or HMS is packaged when:
 * 1. the app's `<meta-data android:name="com.brickssoft.locationtracking.PROVIDERS">` ([PROVIDERS_META_DATA]) lists
 *    it, or the app declares no such entry;
 * 2. its SDK class is present; and
 * 3. the receivers its activity and geofence results are delivered to are declared in the app's merged manifest.
 *
 * An app that publishes one APK per store declares the entry in each flavor's manifest (`gms` or `hms`) and removes
 * the other provider's receivers with `tools:node="remove"` (README, "Separate Play and AppGallery APKs"). The other
 * provider's SDK classes can still reach the APK through other libraries; the entry and the missing receivers both
 * keep the plugin from using it.
 *
 * The entry's value is a comma-separated list of `gms`, `hms` and `android` (case and spaces are ignored); `android`
 * alone means neither GMS nor HMS. Unknown names are logged and ignored. A value with no known name is logged as an
 * error and the entry is ignored.
 *
 * A check that throws (anything except a VM error) reads as not packaged and is logged.
 *
 * @param classPresent whether a class can be loaded (see [DefaultProviderFactory.reflectiveClassPresent]).
 * @param receiverDeclared whether a receiver class is declared in the merged manifest (see [receiverDeclaredIn]).
 * @param providersMetaData the raw value of the [PROVIDERS_META_DATA] entry, or null if the app declares none (see
 *   [providersMetaDataIn]).
 */
class ProviderPackaging(
    private val classPresent: (String) -> Boolean,
    private val receiverDeclared: (String) -> Boolean,
    providersMetaData: () -> String? = { null },
) {
    enum class Status { PACKAGED, NOT_LISTED, SDK_MISSING, RECEIVERS_REMOVED }

    /** The providers the app's entry lists, or null if there is no usable entry. Read once. */
    private val listed: Set<ProviderKind>? by lazy {
        val raw = passesOrNull("read meta-data $PROVIDERS_META_DATA") { providersMetaData() }
        raw?.let(::parseProviders)
    }

    /** True if the app's [PROVIDERS_META_DATA] entry names [kind]; false without an entry. */
    fun listedExplicitly(kind: ProviderKind): Boolean = listed?.contains(kind) == true

    fun status(kind: ProviderKind): Status {
        val sdkClass = ProviderBundles.sdkClassName(kind) ?: return Status.PACKAGED
        listed?.let { if (kind !in it) return Status.NOT_LISTED }
        if (!passes("probe $sdkClass") { classPresent(sdkClass) }) return Status.SDK_MISSING
        val receivers = ProviderBundles.receiverClassNames(kind)
        if (!receivers.all { passes("look up receiver $it") { receiverDeclared(it) } }) return Status.RECEIVERS_REMOVED
        return Status.PACKAGED
    }

    fun isPackaged(kind: ProviderKind): Boolean = status(kind) == Status.PACKAGED

    private fun parseProviders(raw: String): Set<ProviderKind>? {
        val names = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val kinds = names.mapNotNull { name ->
            ProviderKind.fromWire(name) ?: null.also {
                Logger.w(TAG, "$PROVIDERS_META_DATA: unknown provider '$name' ignored (use gms, hms or android)")
            }
        }.toSet()
        if (kinds.isEmpty()) {
            Logger.e(TAG, "$PROVIDERS_META_DATA='$raw' names no provider (use gms, hms or android); entry ignored")
            return null
        }
        return kinds
    }

    private inline fun passes(what: String, block: () -> Boolean): Boolean = passesOrNull(what, block) ?: false

    private inline fun <T> passesOrNull(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "$what failed: ${t.javaClass.name}: ${t.message}", t)
            null
        }

    companion object {
        private const val TAG = "LT.ProviderPackaging"

        /**
         * Name of the `<meta-data>` in the app's `<application>` that lists the providers the plugin may use:
         * `<meta-data android:name="com.brickssoft.locationtracking.PROVIDERS" android:value="hms"/>`.
         */
        const val PROVIDERS_META_DATA = "com.brickssoft.locationtracking.PROVIDERS"

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

        /** The string value of the app's [PROVIDERS_META_DATA] entry, or null if it declares none. */
        fun providersMetaDataIn(context: Context): () -> String? = {
            applicationInfo(context).metaData?.getString(PROVIDERS_META_DATA)
        }

        private fun applicationInfo(context: Context): ApplicationInfo {
            val pm = context.packageManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    context.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            }
        }
    }
}
