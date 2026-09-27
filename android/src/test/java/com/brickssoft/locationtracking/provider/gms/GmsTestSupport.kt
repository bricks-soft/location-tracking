package com.brickssoft.locationtracking.provider.gms

import android.location.Location
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.LogSink
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.CopyOnWriteArrayList

/** Collects log lines written through `Logger`. */
internal class RecordingLogSink : LogSink {
    data class Line(val level: LogLevel, val tag: String, val message: String, val error: Throwable?)

    val lines = CopyOnWriteArrayList<Line>()

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        lines += Line(level, tag, message, error)
    }

    fun at(level: LogLevel): List<Line> = lines.filter { it.level == level }
}

/** An android [Location] as GMS would deliver it. */
internal fun androidLocation(
    latitude: Double = 24.7136,
    longitude: Double = 46.6753,
    accuracy: Float = 5f,
    time: Long = 1_790_417_730_123L,
    provider: String = "fused",
): Location = Location(provider).apply {
    this.latitude = latitude
    this.longitude = longitude
    this.accuracy = accuracy
    this.time = time
}

/** A successful `Task<Void>`. */
internal fun voidTask(): Task<Void> = Tasks.forResult(null)
