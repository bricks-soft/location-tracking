package com.brickssoft.locationtracking.api

import android.app.Application
import android.content.Context
import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One listener call as a test listener saw it. */
data class ListenerCall(
    val listener: String,
    /** "record" or "event". */
    val kind: String,
    /** The record's `event` or the event's name. */
    val name: String,
    val json: JSONObject,
    val thread: String,
    val context: Context,
) {
    override fun toString(): String = "$listener:$kind:$name"
}

/** Every call of every test listener, in delivery order (all run on the one `LT-native` thread). */
object ListenerLog {
    val calls = CopyOnWriteArrayList<ListenerCall>()

    fun clear() = calls.clear()

    fun of(listener: String): List<ListenerCall> = calls.filter { it.listener == listener }

    /** "record:<event>" / "event:<name>" of [listener]'s calls, in order. */
    fun keys(listener: String): List<String> = of(listener).map { "${it.kind}:${it.name}" }
}

/** Base of the test listeners: logs every call under [label]. */
abstract class LoggingListener(private val label: String) : LocationTrackingListener {
    override fun onRecord(context: Context, record: JSONObject) {
        ListenerLog.calls += ListenerCall(label, "record", record.getString("event"), record, Thread.currentThread().name, context)
    }

    override fun onEvent(context: Context, name: String, payload: JSONObject) {
        ListenerLog.calls += ListenerCall(label, "event", name, payload, Thread.currentThread().name, context)
    }
}

/** A manifest listener (public no-arg constructor); counts its instances. */
class RecordingListener : LoggingListener(NAME) {
    init {
        created.incrementAndGet()
    }

    companion object {
        const val NAME = "recording"
        val created = AtomicInteger()
    }
}

/** A second manifest listener class. */
class SecondListener : LoggingListener(NAME) {
    init {
        created.incrementAndGet()
    }

    companion object {
        const val NAME = "second"
        val created = AtomicInteger()
    }
}

/** Throws in every callback; logs nothing. */
class ThrowingListener : LocationTrackingListener {
    override fun onRecord(context: Context, record: JSONObject): Unit = throw IllegalStateException("onRecord boom")

    override fun onEvent(context: Context, name: String, payload: JSONObject): Unit =
        throw NoClassDefFoundError("onEvent boom")
}

/** Its constructor throws. */
class ConstructorThrowsListener : LocationTrackingListener {
    init {
        throw IllegalStateException("constructor boom")
    }
}

/** Its static initializer throws (ExceptionInInitializerError when the class is initialized). */
class StaticInitThrowsListener : LocationTrackingListener {
    companion object {
        @JvmField
        val value: Int = if (System.nanoTime() != 0L) throw IllegalStateException("static boom") else 1
    }
}

/** No no-arg constructor. */
class NeedsArgumentListener(@Suppress("unused") private val value: Int) : LocationTrackingListener

/** Does not implement [LocationTrackingListener]. */
class NotAListener

/** Sets the application's `<meta-data>` (what the merged manifest would declare) through Robolectric. */
internal fun setMetaData(app: Application, values: Map<String, Any>) {
    val bundle = Bundle()
    for ((key, value) in values) {
        when (value) {
            is String -> bundle.putString(key, value)
            is Int -> bundle.putInt(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            else -> error("unsupported meta-data value $value")
        }
    }
    val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
    info.applicationInfo!!.metaData = bundle
}

/** Collects the results of [NativeCallback]s. */
internal class CallbackProbe<T> : NativeCallback<T> {
    private val latch = CountDownLatch(1)
    val results = CopyOnWriteArrayList<Result<T>>()

    @Volatile
    var thread: String? = null

    override fun onResult(result: Result<T>) {
        thread = Thread.currentThread().name
        results += result
        latch.countDown()
    }

    /** Waits for the (single) result. */
    fun await(timeoutMs: Long = 15_000L): Result<T> {
        assertTrue("the callback was not invoked within $timeoutMs ms", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        return results.single()
    }
}
