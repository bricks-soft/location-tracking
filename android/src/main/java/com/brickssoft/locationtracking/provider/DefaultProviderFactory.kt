package com.brickssoft.locationtracking.provider

import android.content.Context
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.LocationProviderSetting
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.android.AndroidProviderBundle

/**
 * Selects GMS, HMS or the platform `LocationManager` from config `locationProvider`:
 * - `auto`: GMS if it is packaged ([ProviderPackaging]: allowed by the app's `PROVIDERS` meta-data, SDK class present,
 *   receivers declared) and its bundle is available; else HMS likewise; else ANDROID.
 * - `gms` / `hms`: that backend, or ANDROID (with a warning) if it is not packaged or not available.
 * - `android`: always ANDROID.
 *
 * Bundles are created only by reflection ([ProviderBundles]) and cached; any [Throwable] while probing or creating
 * one (e.g. [NoClassDefFoundError]) marks it unavailable. Selection is lazy (first access), thread-safe, and
 * re-evaluated only by [reselect].
 *
 * @param classPresent whether a class can be loaded; defaults to `Class.forName(name, false, loader)`.
 * @param createBundle instantiates a bundle class by name; defaults to its public (Context) constructor.
 * @param receiverDeclared whether a receiver is declared in the merged manifest; defaults to `getReceiverInfo`.
 * @param providersMetaData the app's `PROVIDERS` meta-data value, or null; defaults to the application's meta-data.
 */
class DefaultProviderFactory(
    context: Context,
    private val configStore: ConfigStore,
    classPresent: (String) -> Boolean = reflectiveClassPresent(context.classLoader),
    private val createBundle: (String) -> ProviderBundle = reflectiveBundleCreator(context),
    receiverDeclared: (String) -> Boolean = ProviderPackaging.receiverDeclaredIn(context.applicationContext ?: context),
    providersMetaData: () -> String? = ProviderPackaging.providersMetaDataIn(context.applicationContext ?: context),
) : ProviderFactory {
    private val appContext: Context = context.applicationContext ?: context

    private val packaging = ProviderPackaging(classPresent, receiverDeclared, providersMetaData)

    private val lock = Any()

    @Volatile
    private var selection: Selection? = null

    /** Created bundles; a null value means the bundle could not be loaded. Guarded by [lock]. */
    private val bundles = HashMap<ProviderKind, ProviderBundle?>()

    /** Bundles whose [ProviderBundle.versionDiagnostic] is logged once, when they are created. Guarded by [lock]. */
    private val bundleVersionWarnings = HashSet<ProviderKind>()

    override val kind: ProviderKind get() = current().kind

    override fun location(): LocationBackend = current().bundle.location()

    override fun activity(): ActivityBackend = current().bundle.activity()

    override fun geofence(): GeofenceBackend = current().bundle.geofence()

    override fun isAvailable(kind: ProviderKind): Boolean {
        val bundle = synchronized(lock) { bundleLocked(kind) } ?: return false
        return isUsable(bundle)
    }

    override fun bestAvailable(): ProviderKind = synchronized(lock) {
        if (configStore.config.value.locationProvider != LocationProviderSetting.AUTO) return@synchronized kind
        when {
            availableLocked(ProviderKind.GMS) -> ProviderKind.GMS
            availableLocked(ProviderKind.HMS) -> ProviderKind.HMS
            else -> ProviderKind.ANDROID
        }
    }

    /**
     * Why the current backend was selected (recommendation 15): for each better backend that was not used, its reason
     * (not listed, SDK not packaged, receivers removed, or the availability status code); null when the preferred
     * backend is used. The status codes are those of the last availability checks.
     */
    override fun selectionInfo(): ProviderSelectionInfo = synchronized(lock) {
        val setting = configStore.config.value.locationProvider
        val current = current().kind
        val preferred = when (setting) {
            LocationProviderSetting.AUTO -> listOf(ProviderKind.GMS, ProviderKind.HMS)
            LocationProviderSetting.GMS -> listOf(ProviderKind.GMS)
            LocationProviderSetting.HMS -> listOf(ProviderKind.HMS)
            LocationProviderSetting.ANDROID -> emptyList()
        }
        val skipped = preferred.takeWhile { it != current }.map { kind -> "${kind.wire}: ${skipReasonLocked(kind)}" }
        val hms = bundles[ProviderKind.HMS]
        ProviderSelectionInfo(
            fallbackReason = skipped.takeIf { it.isNotEmpty() }?.joinToString("; "),
            gmsStatus = bundles[ProviderKind.GMS]?.availabilityStatus(),
            hmsStatus = hms?.availabilityStatus(),
            hmsCoreOutdated = hms?.let { guard("HMS Core version check") { it.versionDiagnostic() } != null },
        )
    }

    private fun skipReasonLocked(kind: ProviderKind): String {
        when (packaging.status(kind)) {
            ProviderPackaging.Status.NOT_LISTED -> return "not listed in ${ProviderPackaging.PROVIDERS_META_DATA}"
            ProviderPackaging.Status.SDK_MISSING -> return "SDK not packaged"
            ProviderPackaging.Status.RECEIVERS_REMOVED -> return "receivers not in the manifest"
            ProviderPackaging.Status.PACKAGED -> Unit
        }
        val bundle = bundleLocked(kind) ?: return "backend could not be loaded"
        val available = isUsable(bundle)
        val status = bundle.availabilityStatus()
        return when {
            available -> "available, not selected"
            status != null -> "unavailable (status $status)"
            else -> "unavailable"
        }
    }

    override fun reselect(): Boolean = synchronized(lock) {
        val previous = selection
        val next = selectLocked()
        selection = next
        previous != null && previous.kind != next.kind
    }

    private fun current(): Selection =
        selection ?: synchronized(lock) { selection ?: selectLocked().also { selection = it } }

    private fun selectLocked(): Selection {
        val setting = configStore.config.value.locationProvider
        val kind = when (setting) {
            LocationProviderSetting.AUTO -> when {
                availableLocked(ProviderKind.GMS) -> ProviderKind.GMS
                availableLocked(ProviderKind.HMS) -> ProviderKind.HMS
                else -> ProviderKind.ANDROID
            }
            LocationProviderSetting.GMS -> explicitLocked(ProviderKind.GMS)
            LocationProviderSetting.HMS -> explicitLocked(ProviderKind.HMS)
            LocationProviderSetting.ANDROID -> ProviderKind.ANDROID
        }
        val bundle = if (kind == ProviderKind.ANDROID) androidBundleLocked() else checkNotNull(bundles[kind])
        if (selection?.kind != kind) Logger.i(TAG, "location backend: ${kind.wire} (locationProvider=${setting.wire})")
        return Selection(kind, bundle)
    }

    private fun explicitLocked(kind: ProviderKind): ProviderKind {
        if (availableLocked(kind)) return kind
        Logger.w(TAG, "locationProvider=${kind.wire} is not packaged or not available; falling back to android")
        return ProviderKind.ANDROID
    }

    private fun availableLocked(kind: ProviderKind): Boolean = bundleLocked(kind)?.let(::isUsable) ?: false

    /** The cached bundle of [kind], loading it on first use; null if its SDK or bundle class cannot be loaded. */
    private fun bundleLocked(kind: ProviderKind): ProviderBundle? {
        if (bundles.containsKey(kind)) return bundles[kind]
        val bundle = loadBundle(kind)
        bundles[kind] = bundle
        return bundle
    }

    private fun loadBundle(kind: ProviderKind): ProviderBundle? {
        val reason = when (packaging.status(kind)) {
            ProviderPackaging.Status.PACKAGED -> null
            ProviderPackaging.Status.NOT_LISTED -> "not listed in ${ProviderPackaging.PROVIDERS_META_DATA}"
            ProviderPackaging.Status.SDK_MISSING -> "SDK is not packaged"
            ProviderPackaging.Status.RECEIVERS_REMOVED -> "SDK is present but its receivers are not in the manifest"
        }
        if (reason == null && kind == ProviderKind.HMS) {
            // Diagnostic only (recommendation 15): HMS is used whatever the HMS Core version.
            bundleVersionWarnings += kind
        }
        if (reason != null) {
            // Listed in the app's PROVIDERS meta-data but unusable is a setup error; otherwise it is expected.
            if (packaging.listedExplicitly(kind)) {
                Logger.w(TAG, "${kind.wire} is listed in ${ProviderPackaging.PROVIDERS_META_DATA} but not used: $reason")
            } else {
                Logger.i(TAG, "${kind.wire} is not used: $reason")
            }
            return null
        }
        val name = ProviderBundles.bundleClassName(kind)
        val bundle = guard("create $name") { createBundle(name) }
        if (bundle != null && kind in bundleVersionWarnings) {
            guard("${kind.wire} version check") { bundle.versionDiagnostic() }?.let { Logger.w(TAG, it) }
        }
        if (bundle == null && kind == ProviderKind.ANDROID) {
            // The last resort must always exist; it lives in this module, so it can be constructed directly.
            Logger.e(TAG, "reflective creation of the android bundle failed; constructing it directly")
            return AndroidProviderBundle(appContext)
        }
        return bundle
    }

    private fun androidBundleLocked(): ProviderBundle = checkNotNull(bundleLocked(ProviderKind.ANDROID))

    private fun isUsable(bundle: ProviderBundle): Boolean =
        guard("${bundle.javaClass.name}.isAvailable") { bundle.isAvailable() } == true

    /** Runs [block]; any failure except a VM error (out of memory, stack overflow) is logged and yields null. */
    private inline fun <T> guard(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "$what failed: ${t.javaClass.name}: ${t.message}", t)
            null
        }

    private data class Selection(val kind: ProviderKind, val bundle: ProviderBundle)

    companion object {
        private const val TAG = "LT.ProviderFactory"

        /** `Class.forName(name, false, loader)`: true if the class exists, without initializing it. */
        fun reflectiveClassPresent(loader: ClassLoader?): (String) -> Boolean = { name ->
            try {
                Class.forName(name, false, loader ?: DefaultProviderFactory::class.java.classLoader)
                true
            } catch (e: ClassNotFoundException) {
                false
            } catch (e: LinkageError) {
                false
            }
        }

        /** Instantiates a [ProviderBundle] class through its public (Context) constructor, with the app context. */
        fun reflectiveBundleCreator(context: Context): (String) -> ProviderBundle = { name ->
            val appContext = context.applicationContext ?: context
            val loader = appContext.classLoader ?: DefaultProviderFactory::class.java.classLoader
            Class.forName(name, true, loader).getConstructor(Context::class.java).newInstance(appContext)
                as ProviderBundle
        }
    }
}
