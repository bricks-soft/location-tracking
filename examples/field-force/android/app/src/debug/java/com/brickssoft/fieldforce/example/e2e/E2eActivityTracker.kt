package com.brickssoft.fieldforce.example.e2e

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Debug build only: remembers the app's live activities, so the e2e command `finishActivities` can destroy them while
 * the process keeps running (scenario F-08: PremiseMonitor keeps receiving events natively with no activity and no
 * WebView).
 *
 * A content provider is created before any activity of the process, so no activity is missed. It exposes no data.
 * Why not "Don't keep activities": `settings put global always_finish_activities 1` from the shell does not change the
 * running activity manager (the developer option calls `ActivityManager.setAlwaysFinish`); on the CI emulator the
 * activity stayed alive after HOME.
 */
class E2eActivityTracker : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                live.add(WeakReference(activity))
            }

            override fun onActivityDestroyed(activity: Activity) {
                live.removeAll { it.get() == null || it.get() === activity }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val live = CopyOnWriteArrayList<WeakReference<Activity>>()

        /** Main thread only: calls `finish()` on every live activity and returns how many were finished. */
        fun finishAll(): Int {
            val activities = live.mapNotNull { it.get() }.filter { !it.isFinishing && !it.isDestroyed }
            activities.forEach { it.finish() }
            return activities.size
        }
    }
}
