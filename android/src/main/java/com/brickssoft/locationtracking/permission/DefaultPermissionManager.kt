package com.brickssoft.locationtracking.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger

/**
 * API-level-aware [PermissionManager].
 *
 * Status: location is granted if fine or coarse is granted. Permissions that do not exist on the device's
 * API level are mapped: background location below API 29 equals location; activity recognition below 29
 * and notifications below 33 are granted. A permission that is not granted is `PROMPT_WITH_RATIONALE` if
 * Android says a rationale should be shown, `PROMPT` if the plugin never asked for it, otherwise `DENIED`.
 * "Asked" flags (scoped to the current install, cleared once granted) and the last rationale answer (used
 * when no Activity is at hand) are kept in the plugin prefs under `perm_` keys.
 *
 * Request order: location, notifications, activity recognition, background location — one alias group at
 * a time, skipping granted and not-applicable types. Background location is requested only once foreground
 * location is granted: directly on API 29, after [BackgroundRationaleDialog] on API 30+. Overlapping
 * `request` calls run one after another.
 *
 * @param activityProvider optional source of the Activity used for `shouldShowRequestPermissionRationale`.
 *   When it returns null, the app's last started/resumed Activity or the last [PermissionHost] Activity is used.
 */
class DefaultPermissionManager @VisibleForTesting internal constructor(
    context: Context,
    private val activityProvider: () -> Activity?,
    private val sdkInt: Int,
) : PermissionManager {
    constructor(context: Context, activityProvider: () -> Activity? = { null }) :
        this(context, activityProvider, Build.VERSION.SDK_INT)

    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Identifies this install, so "asked" flags restored from a backup onto a new install are ignored. */
    private val installMarker: Long by lazy {
        try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).firstInstallTime
        } catch (e: Exception) {
            Logger.w(TAG, "cannot read the install time", e)
            0L
        }
    }
    private val activityTracker = CurrentActivityTracker.attach(appContext)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Request flows; main thread only.
    private var activeFlow: RequestFlow? = null
    private val queuedFlows = ArrayDeque<RequestFlow>()

    override fun status(): Map<PermissionType, PermissionState> {
        val activity = currentActivity()
        return PermissionType.entries.associateWith { stateOf(it, activity) }
    }

    override fun hasForegroundLocation(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun hasBackgroundLocation(): Boolean = if (sdkInt < Build.VERSION_CODES.Q) {
        hasForegroundLocation()
    } else {
        hasForegroundLocation() && isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    override fun hasActivityRecognition(): Boolean =
        sdkInt < Build.VERSION_CODES.Q || isGranted(Manifest.permission.ACTIVITY_RECOGNITION)

    override fun hasNotifications(): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU || isGranted(Manifest.permission.POST_NOTIFICATIONS)

    override fun request(
        host: PermissionHost,
        types: List<PermissionType>,
        rationale: BackgroundPermissionRationale,
        onDone: (Map<PermissionType, PermissionState>) -> Unit,
    ) {
        if (!isMainThread()) {
            mainHandler.post { request(host, types, rationale, onDone) }
            return
        }
        hostActivity(host)?.let { activityTracker.remember(it) }
        val flow = RequestFlow(host, types.toSet(), rationale, onDone)
        if (activeFlow != null) {
            Logger.d(TAG, "a permission request is in progress; queued ${types.map { it.alias }}")
            queuedFlows.addLast(flow)
            return
        }
        activeFlow = flow
        flow.next()
    }

    // ---- status

    private fun stateOf(type: PermissionType, activity: Activity?): PermissionState = when {
        type == PermissionType.BACKGROUND_LOCATION && sdkInt < Build.VERSION_CODES.Q ->
            stateOf(PermissionType.LOCATION, activity)
        isGranted(type) -> PermissionState.GRANTED.also { forget(type) }
        activity != null && shouldShowRationale(type, activity) -> PermissionState.PROMPT_WITH_RATIONALE
        !wasAsked(type) -> PermissionState.PROMPT
        activity == null && lastRationale(type) -> PermissionState.PROMPT_WITH_RATIONALE
        else -> PermissionState.DENIED
    }

    private fun isGranted(type: PermissionType): Boolean = when (type) {
        PermissionType.LOCATION -> hasForegroundLocation()
        PermissionType.BACKGROUND_LOCATION -> hasBackgroundLocation()
        PermissionType.ACTIVITY_RECOGNITION -> hasActivityRecognition()
        PermissionType.NOTIFICATIONS -> hasNotifications()
    }

    /** True if [type] is a runtime permission on this API level (otherwise it is implied or not needed). */
    private fun isRuntime(type: PermissionType): Boolean = when (type) {
        PermissionType.LOCATION -> true
        PermissionType.BACKGROUND_LOCATION, PermissionType.ACTIVITY_RECOGNITION -> sdkInt >= Build.VERSION_CODES.Q
        PermissionType.NOTIFICATIONS -> sdkInt >= Build.VERSION_CODES.TIRAMISU
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun shouldShowRationale(type: PermissionType, activity: Activity): Boolean =
        isRuntime(type) && androidPermissions(type).any { permission ->
            try {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            } catch (e: RuntimeException) {
                Logger.w(TAG, "shouldShowRequestPermissionRationale($permission) failed", e)
                false
            }
        }

    private fun currentActivity(): Activity? =
        activityProvider()?.takeIf { it.isUsable() } ?: activityTracker.current()

    private fun hostActivity(host: PermissionHost): Activity? =
        try {
            host.activity?.takeIf { it.isUsable() }
        } catch (e: RuntimeException) {
            Logger.w(TAG, "permission host has no activity", e)
            null
        }

    // ---- persisted flags (plugin prefs, `perm_` keys only)

    private fun wasAsked(type: PermissionType): Boolean =
        try {
            prefs.getLong(askedKey(type), NEVER) == installMarker
        } catch (e: ClassCastException) {
            false
        }

    private fun lastRationale(type: PermissionType): Boolean =
        try {
            prefs.getBoolean(rationaleKey(type), false)
        } catch (e: ClassCastException) {
            false
        }

    private fun markAsked(type: PermissionType) {
        prefs.edit().putLong(askedKey(type), installMarker).apply()
    }

    /** Drops [type]'s flags: once granted, a later revocation or auto-reset starts from "never asked". */
    private fun forget(type: PermissionType) {
        if (prefs.contains(askedKey(type)) || prefs.contains(rationaleKey(type))) {
            prefs.edit().remove(askedKey(type)).remove(rationaleKey(type)).apply()
        }
    }

    /** After a request for [type] finished: remember whether Android would show a rationale now. */
    private fun recordResult(type: PermissionType, activity: Activity?) {
        if (activity == null || isGranted(type)) return
        prefs.edit().putBoolean(rationaleKey(type), shouldShowRationale(type, activity)).apply()
    }

    // ---- request flow

    private fun onFlowFinished(flow: RequestFlow) {
        if (activeFlow !== flow) return
        val next = queuedFlows.removeFirstOrNull()
        activeFlow = next
        if (next != null) mainHandler.post { next.next() }
    }

    /** One `request()` call: walks [REQUEST_ORDER] and calls [onDone] exactly once, on the main thread. */
    private inner class RequestFlow(
        private val host: PermissionHost,
        private val types: Set<PermissionType>,
        private val rationale: BackgroundPermissionRationale,
        private val onDone: (Map<PermissionType, PermissionState>) -> Unit,
    ) {
        private val pending = ArrayDeque(REQUEST_ORDER.filter { it in types })
        private var finished = false

        /** Runs steps until one waits for the user (it calls [next] again later) or none is left. */
        fun next() {
            while (!finished) {
                val type = pending.removeFirstOrNull() ?: return finish()
                val waiting = if (type == PermissionType.BACKGROUND_LOCATION) requestBackground() else requestPlain(type)
                if (waiting) return
            }
        }

        private fun finish() {
            if (finished) return
            finished = true
            try {
                onDone(status())
            } finally {
                onFlowFinished(this)
            }
        }

        /** Location, notifications, activity recognition. Returns true if a request is in flight. */
        private fun requestPlain(type: PermissionType): Boolean {
            if (!isRuntime(type)) {
                Logger.d(TAG, "${type.alias}: not a runtime permission on API $sdkInt, skipped")
                return false
            }
            if (isGranted(type)) return false
            return ask(type)
        }

        private fun requestBackground(): Boolean {
            val type = PermissionType.BACKGROUND_LOCATION
            if (!isRuntime(type)) {
                Logger.d(TAG, "backgroundLocation: implied by location on API $sdkInt, skipped")
                return false
            }
            if (isGranted(type)) return false
            if (!hasForegroundLocation()) {
                Logger.i(TAG, "backgroundLocation: skipped, foreground location is not granted")
                return false
            }
            if (sdkInt < Build.VERSION_CODES.R) return ask(type)

            val activity = hostActivity(host) ?: currentActivity()
            if (activity == null) {
                Logger.w(TAG, "backgroundLocation: skipped, no activity to show the rationale dialog")
                return false
            }
            return BackgroundRationaleDialog.show(activity, rationale) { accepted ->
                onMain {
                    if (accepted) {
                        ask(type)
                    } else {
                        Logger.i(TAG, "backgroundLocation: rationale declined")
                        next()
                    }
                }
            }
        }

        /**
         * Requests [type]'s alias through the host; always returns true, the host callback continues the flow.
         * The continuation is posted when the host calls back synchronously or off the main thread, so the rest
         * of the flow never runs inside `requestAliases`. A host that throws leaves [type] "never asked".
         */
        private fun ask(type: PermissionType): Boolean {
            val askedBefore = wasAsked(type)
            markAsked(type)
            var continued = false
            var insideHostCall = true
            val continueFlow = {
                if (!continued) {
                    continued = true
                    recordResult(type, hostActivity(host) ?: currentActivity())
                    next()
                }
            }
            try {
                host.requestAliases(listOf(type.alias)) {
                    if (insideHostCall || !isMainThread()) mainHandler.post(continueFlow) else continueFlow()
                }
            } catch (e: RuntimeException) {
                Logger.w(TAG, "requesting ${type.alias} failed", e)
                if (!askedBefore) prefs.edit().remove(askedKey(type)).apply()
                if (!continued) {
                    continued = true
                    mainHandler.post { next() }
                }
            } finally {
                insideHostCall = false
            }
            return true
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun onMain(block: () -> Unit) {
        if (isMainThread()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "LT.Permissions"
        const val KEY_PREFIX = "perm_"
        const val NEVER = -1L

        val REQUEST_ORDER = listOf(
            PermissionType.LOCATION,
            PermissionType.NOTIFICATIONS,
            PermissionType.ACTIVITY_RECOGNITION,
            PermissionType.BACKGROUND_LOCATION,
        )

        fun askedKey(type: PermissionType) = "${KEY_PREFIX}asked_${type.alias}"

        fun rationaleKey(type: PermissionType) = "${KEY_PREFIX}rationale_${type.alias}"

        fun androidPermissions(type: PermissionType): List<String> = when (type) {
            PermissionType.LOCATION ->
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            PermissionType.BACKGROUND_LOCATION -> listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            PermissionType.ACTIVITY_RECOGNITION -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)
            PermissionType.NOTIFICATIONS -> listOf(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
