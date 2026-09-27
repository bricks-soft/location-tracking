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
}
