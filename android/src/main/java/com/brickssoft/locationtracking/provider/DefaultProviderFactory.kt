package com.brickssoft.locationtracking.provider

import android.content.Context
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.LocationProviderSetting
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.android.AndroidProviderBundle

/**
 * Selects GMS, HMS or the platform `LocationManager` from config `locationProvider`:
 * - `auto`: GMS if it is packaged ([ProviderPackaging]: SDK class present and receivers declared) and its bundle is
 *   available; else HMS likewise; else ANDROID.
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
 */
class DefaultProviderFactory(
    context: Context,
    private val configStore: ConfigStore,
    classPresent: (String) -> Boolean = reflectiveClassPresent(context.classLoader),
    private val createBundle: (String) -> ProviderBundle = reflectiveBundleCreator(context),
    receiverDeclared: (String) -> Boolean = ProviderPackaging.receiverDeclaredIn(context.applicationContext ?: context),
) : ProviderFactory {
    private val appContext: Context = context.applicationContext ?: context

    private val packaging = ProviderPackaging(classPresent, receiverDeclared)

    private val lock = Any()

    @Volatile
    private var selection: Selection? = null

    /** Created bundles; a null value means the bundle could not be loaded. Guarded by [lock]. */
    private val bundles = HashMap<ProviderKind, ProviderBundle?>()

    override val kind: ProviderKind get() = current().kind

    override fun location(): LocationBackend = current().bundle.location()

    override fun activity(): ActivityBackend = current().bundle.activity()

    override fun geofence(): GeofenceBackend = current().bundle.geofence()

    override fun isAvailable(kind: ProviderKind): Boolean {
        val bundle = synchronized(lock) { bundleLocked(kind) } ?: return false
        return isUsable(bundle)
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
        when (packaging.status(kind)) {
            ProviderPackaging.Status.PACKAGED -> Unit
            ProviderPackaging.Status.SDK_MISSING -> {
                Logger.d(TAG, "${kind.wire} SDK is not packaged")
                return null
            }
            ProviderPackaging.Status.RECEIVERS_REMOVED -> {
                Logger.i(TAG, "${kind.wire} SDK is present but its receivers are not in the manifest; not used")
                return null
            }
        }
        val name = ProviderBundles.bundleClassName(kind)
        val bundle = guard("create $name") { createBundle(name) }
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
