package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.model.Record

/** Uploads queued records (see architecture §2 for the wire format and policy). */
interface HttpSyncer {
    /** Idempotent; subscribes to ConnectivityChange. */
    fun start()

    /** Non-blocking; applies the policy (priority / threshold / cellular) and may schedule an upload. */
    fun onRecordInserted(record: Record)

    /**
     * Manual upload of the whole queue; returns the uploaded records.
     * @throws com.brickssoft.locationtracking.core.TrackingException NO_URL, HTTP_ERROR or NETWORK_ERROR.
     */
    suspend fun sync(): List<Record>

    /** Tracking stopped: make sure what is still queued is uploaded later, also if this process dies (upload job). */
    fun onTrackingStopped() = Unit

    /**
     * The upload job runs: one automatic pass, and more while they succeed and leave records due. Returns when done.
     */
    suspend fun runScheduledPass() = Unit

    /** A ticket for [awaitUpload]; take it before the record whose upload is awaited is queued. */
    fun uploadTicket(): Long = 0L

    /**
     * Suspends until the first automatic pass started after [ticket] was taken has finished (uploaded, failed, or
     * found nothing to send). Returns at once if no `http.url` is set. Callers bound the wait with a timeout.
     */
    suspend fun awaitUpload(ticket: Long) = Unit
}
