package com.brickssoft.locationtracking.permission

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.lang.ref.WeakReference

/** [Application.ActivityLifecycleCallbacks] with no-op defaults. */
internal abstract class ActivityLifecycleAdapter : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Weakly remembers the most recently created, started or resumed Activity of the app, and the last
 * [PermissionHost] Activity, so `shouldShowRequestPermissionRationale` can be asked outside a request.
 * One tracker per Application ([attach]).
 */
internal class CurrentActivityTracker : ActivityLifecycleAdapter() {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    /** The remembered Activity, or null if there is none or it is finishing or destroyed. */
    fun current(): Activity? = ref?.get()?.takeIf { it.isUsable() }

    fun remember(activity: Activity) {
        if (ref?.get() !== activity) ref = WeakReference(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = remember(activity)

    override fun onActivityStarted(activity: Activity) = remember(activity)

    override fun onActivityResumed(activity: Activity) = remember(activity)

    override fun onActivityDestroyed(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    companion object {
        private var sharedApp: Application? = null
        private var shared: CurrentActivityTracker? = null

        /** The tracker registered on [context]'s Application (created and registered on first use). */
        @Synchronized
        fun attach(context: Context): CurrentActivityTracker {
            val app = context.applicationContext as? Application ?: context as? Application
                ?: return CurrentActivityTracker()
            shared?.let { if (sharedApp === app) return it }
            val tracker = CurrentActivityTracker()
            app.registerActivityLifecycleCallbacks(tracker)
            sharedApp = app
            shared = tracker
            return tracker
        }
    }
}

/** True if [this] can still host a dialog or answer permission-rationale queries. */
internal fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed
