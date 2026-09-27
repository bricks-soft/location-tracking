package com.brickssoft.locationtracking.heartbeat

import com.brickssoft.locationtracking.model.HeartbeatStatus
import com.brickssoft.locationtracking.model.Record

enum class HeartbeatTrigger {
    EXACT_ALARM,
    LISTENER_ALARM,
    BACKUP_ALARM,
}

/** Creates a `heartbeat` record when no record was created for `heartbeat.minInterval` while tracking. */
interface HeartbeatScheduler {
    /** Starts the window from runtime.lastRecord* (or now); observes config itself. */
    fun start()

    fun stop()

    /** Every persisted record restarts the window. */
    fun onRecordRecorded(record: Record)

    suspend fun onAlarm(trigger: HeartbeatTrigger)

    suspend fun status(): HeartbeatStatus
}
