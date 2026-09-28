package com.brickssoft.locationtracking.api

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.RecordHooks
import com.brickssoft.locationtracking.core.Subscription
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.EventJson
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordJson
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Installs the manifest-declared [LocationTrackingListener]s and connects every listener (manifest and programmatic)
 * to the plugin: `components.recordHooks` for records, `components.events` for events. Delivery runs on the
 * `LT-native` thread ([NativeThread]).
 *
 * How a record or an event reaches a listener:
 * 1. [RecordHooks.dispatch] or [EventBus.emit] calls this object synchronously on the emitting thread (the engine,
 *    a receiver, an I/O coroutine).
 * 2. This object takes a snapshot of the registered listeners (if there is any) and queues one task on the `LT-native`
 *    thread. It does not build any JSON on the emitting thread, so the engine is never slowed down by a listener.
 * 3. The task builds the JSON once (`RecordJson.toJson(record)` without `sent_at`, or `EventJson.name/payload`), gives
 *    each listener its own JSONObject (a listener that changes its object does not change what the next listener
 *    receives) and calls each listener that is still subscribed. A listener that throws is logged; the other
 *    listeners still run.
 *
 * Programmatic listeners are process-wide: they stay registered when a new [Components] instance is installed (only
 * tests create more than one). Manifest listeners are created again by every [install].
 */
internal object NativeListeners {
    private const val TAG = "LT.Native"

    /** One registered listener. [active] turns false when it is removed; queued deliveries then skip it. */
    private class Entry(
        val listener: LocationTrackingListener,
        val context: Context,
        val label: String,
    ) {
        @Volatile
        var active = true
    }

    private val lock = Any()

    /** Every listener, manifest listeners first (in manifest order), then programmatic ones in subscription order. */
    private val entries = CopyOnWriteArrayList<Entry>()

    /** The manifest listeners of the current installation. Guarded by [lock]. */
    private var manifestEntries: List<Entry> = emptyList()

    /** The record hook and event bus subscriptions of the current installation. Guarded by [lock]. */
    private var connection: List<Subscription> = emptyList()

    /**
     * Called once per [Components] instance from `Components.bootstrap()`: reads the manifest meta-data, creates the
     * listener classes and subscribes to the instance's record hooks and event bus, replacing the subscriptions and
     * the manifest listeners of a previous instance. A failure while reading or creating manifest listeners is logged
     * and never prevents the subscriptions.
     */
    fun install(context: Context, components: Components) {
        val appContext = context.applicationContext ?: context
        val manifest = try {
            createManifestListeners(appContext)
        } catch (e: Exception) {
            Logger.e(TAG, "cannot read the listener meta-data; no manifest listener is installed", e)
            emptyList()
        }
        connect(appContext, components.recordHooks, components.events, manifest)
    }

    /**
     * Subscribes to [hooks] and [events] and makes [manifest] the manifest listeners (called with [context]).
     * Cancels the subscriptions of the previous call and deactivates its manifest listeners.
     * [install] uses it; tests call it directly with the full-stack harness or fakes.
     */
    fun connect(
        context: Context,
        hooks: RecordHooks,
        events: EventBus,
        manifest: List<Pair<String, LocationTrackingListener>>,
    ) {
        val appContext = context.applicationContext ?: context
        synchronized(lock) {
            connection.forEach { it.cancel() }
            for (entry in manifestEntries) {
                entry.active = false
                entries.remove(entry)
            }
            val created = manifest.map { (key, listener) ->
                Entry(listener, appContext, "manifest listener ${listener.javaClass.name} ($key)")
            }
            entries.addAll(0, created)
            manifestEntries = created
            connection = listOf(
                hooks.add { record -> onRecordQueued(record) },
                events.subscribe { event -> onEvent(event) },
            )
        }
        if (manifest.isNotEmpty()) {
            Logger.i(TAG, "installed ${manifest.size} manifest listener(s): ${manifest.joinToString { it.second.javaClass.name }}")
        }
    }

    /** Programmatic subscription ([LocationTrackingNative.addListener]). */
    fun add(context: Context, listener: LocationTrackingListener): NativeSubscription {
        val entry = Entry(listener, context.applicationContext ?: context, "listener ${listener.javaClass.name}")
        entries.add(entry)
        return NativeSubscription {
            entry.active = false
            entries.remove(entry)
        }
    }

    // ---- delivery

    private fun onRecordQueued(record: Record) {
        if (entries.isEmpty()) return
        // The iterator of a CopyOnWriteArrayList is a snapshot of the listeners registered now; taking it copies nothing.
        val targets = entries.iterator()
        deliver({ "record ${record.event.wire} ${record.uuid}" }, targets, { RecordJson.toJson(record) }) { entry, json ->
            entry.listener.onRecord(entry.context, json)
        }
    }

    private fun onEvent(event: TrackingEvent) {
        if (entries.isEmpty()) return
        val targets = entries.iterator()
        deliver({ "event ${event::class.simpleName}" }, targets, { EventJson.payload(event) }) { entry, json ->
            entry.listener.onEvent(entry.context, EventJson.name(event), json)
        }
    }

    /**
     * Queues one task on the `LT-native` thread that builds the JSON once and calls [call] for every listener of
     * [targets] that is still subscribed. Each listener gets its own JSONObject: the first one the built object, the
     * others a copy parsed from its text (made before any listener runs).
     *
     * Every [Throwable] is caught, also an Error such as NoClassDefFoundError or StackOverflowError: an exception that
     * escapes the `LT-native` thread would crash the process, and with it the tracking service.
     */
    private fun deliver(
        what: () -> String,
        targets: Iterator<Entry>,
        build: () -> JSONObject,
        call: (Entry, JSONObject) -> Unit,
    ) {
        NativeThread.post {
            try {
                val active = targets.asSequence().filter { it.active }.toList()
                if (active.isEmpty()) return@post
                val json = build()
                val text = if (active.size > 1) json.toString() else null
                for ((index, entry) in active.withIndex()) {
                    if (!entry.active) continue // removed while an earlier listener ran
                    try {
                        call(entry, if (index == 0) json else JSONObject(text!!))
                    } catch (t: Throwable) {
                        Logger.e(TAG, "${entry.label} threw while receiving ${what()}", t)
                    }
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "cannot deliver ${what()} to the native listeners", t)
            }
        }
    }

    // ---- manifest

    /**
     * The listener class names declared in the manifest, as (meta-data name, class name) pairs: the entry named
     * [LocationTrackingNative.LISTENER_META_DATA] first, then the entries whose name starts with it followed by `.`,
     * sorted by name. A class named twice is returned once (the first occurrence).
     */
    fun declaredListenerClasses(context: Context): List<Pair<String, String>> {
        val metaData = applicationInfo(context).metaData ?: return emptyList()
        val base = LocationTrackingNative.LISTENER_META_DATA
        val keys = metaData.keySet()
            .filter { it == base || it.startsWith("$base.") }
            .sortedWith(compareBy<String>({ it != base }, { it }))
        val seen = HashSet<String>()
        val result = ArrayList<Pair<String, String>>()
        for (key in keys) {
            val className = classNameOf(metaData, key)
            if (className == null) {
                Logger.e(TAG, "meta-data $key must be a class name (android:value=\"com.example.MyListener\")")
                continue
            }
            if (seen.add(className)) {
                result += key to className
            } else {
                Logger.w(TAG, "meta-data $key names $className again; it is created once")
            }
        }
        return result
    }

    /**
     * Creates one instance of each declared listener class with its public no-arg constructor. A class that cannot
     * be loaded or created, or that does not implement [LocationTrackingListener], is logged with its [Throwable]
     * and skipped.
     */
    fun createManifestListeners(context: Context): List<Pair<String, LocationTrackingListener>> =
        declaredListenerClasses(context).mapNotNull { (key, className) -> instantiate(context, key, className) }

    private fun instantiate(context: Context, key: String, className: String): Pair<String, LocationTrackingListener>? =
        try {
            val type = Class.forName(className, true, context.classLoader)
            if (!LocationTrackingListener::class.java.isAssignableFrom(type)) {
                Logger.e(TAG, "$className ($key) does not implement ${LocationTrackingListener::class.java.name}; skipped")
                null
            } else {
                key to (type.getConstructor().newInstance() as LocationTrackingListener)
            }
        } catch (e: Exception) {
            // ClassNotFoundException, NoSuchMethodException (no public no-arg constructor), InstantiationException,
            // IllegalAccessException, InvocationTargetException (the constructor threw).
            Logger.e(TAG, "cannot create the listener $className ($key); skipped", e)
            null
        } catch (e: LinkageError) {
            // NoClassDefFoundError (a missing dependency), ExceptionInInitializerError (a static initializer threw).
            Logger.e(TAG, "cannot load the listener $className ($key); skipped", e)
            null
        }

    private fun classNameOf(metaData: Bundle, key: String): String? {
        @Suppress("DEPRECATION") // Bundle.get(String): the value type is not known in advance.
        val value = metaData.get(key)
        return (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
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

    // ---- tests

    /** Test hook: cancels the subscriptions and forgets every listener (manifest and programmatic). */
    @VisibleForTesting
    fun resetForTests() {
        synchronized(lock) {
            connection.forEach { it.cancel() }
            connection = emptyList()
            manifestEntries = emptyList()
            entries.forEach { it.active = false }
            entries.clear()
        }
    }

    /** Test hook: the number of registered listeners (manifest and programmatic). */
    @VisibleForTesting
    val listenerCount: Int get() = entries.size
}
