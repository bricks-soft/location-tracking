package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.http.SyncPolicy.Scope
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.RecordEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    private val url = "https://example.com/locations"
    private val wifi = Connectivity(connected = true, type = ConnectivityType.WIFI)
    private val cellular = Connectivity(connected = true, type = ConnectivityType.CELLULAR)
    private val offline = Connectivity(connected = false, type = ConnectivityType.NONE)

    @Test
    fun `priority events are heartbeat and the audit records`() {
        assertEquals(
            setOf(
                RecordEvent.HEARTBEAT,
                RecordEvent.TRACKING_START,
                RecordEvent.TRACKING_STOP,
                RecordEvent.PROVIDERCHANGE,
            ),
            SyncPolicy.PRIORITY_EVENTS,
        )
    }

    @Test
    fun `nothing is uploaded without a url`() {
        assertEquals(Scope.NONE, SyncPolicy.autoScope(HttpConfig(url = null), wifi, queued = 5, priorityQueued = 1))
        assertEquals(Scope.NONE, SyncPolicy.autoScope(HttpConfig(url = "  "), wifi, queued = 5, priorityQueued = 1))
        assertFalse(SyncPolicy.hasUrl(HttpConfig(url = "")))
        assertTrue(SyncPolicy.hasUrl(HttpConfig(url = url)))
    }

    @Test
    fun `nothing is uploaded while offline`() {
        assertEquals(Scope.NONE, SyncPolicy.autoScope(HttpConfig(url = url), offline, queued = 5, priorityQueued = 1))
    }

    @Test
    fun `a priority record drains the whole queue, ignoring autoSync and the threshold`() {
        val http = HttpConfig(url = url, autoSync = false, autoSyncThreshold = 10, batchSync = true)
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, queued = 2, priorityQueued = 1))
    }

    @Test
    fun `on restricted cellular only priority records are sent`() {
        val http = HttpConfig(url = url, disableAutoSyncOnCellular = true, autoSyncThreshold = 10)
        assertEquals(Scope.PRIORITY_ONLY, SyncPolicy.autoScope(http, cellular, queued = 20, priorityQueued = 1))
        assertEquals(Scope.NONE, SyncPolicy.autoScope(http, cellular, queued = 20, priorityQueued = 0))
        assertTrue(SyncPolicy.isCellularRestricted(http, cellular))
        assertFalse(SyncPolicy.isCellularRestricted(http, wifi))
    }

    @Test
    fun `cellular is unrestricted unless disableAutoSyncOnCellular`() {
        val http = HttpConfig(url = url)
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, cellular, queued = 1, priorityQueued = 1))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, cellular, queued = 1, priorityQueued = 0))
    }

    @Test
    fun `threshold 0 uploads every record`() {
        val http = HttpConfig(url = url, autoSyncThreshold = 0)
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, queued = 1, priorityQueued = 0))
        assertEquals(Scope.NONE, SyncPolicy.autoScope(http, wifi, queued = 0, priorityQueued = 0))
    }

    @Test
    fun `normal records wait for the threshold`() {
        val http = HttpConfig(url = url, autoSyncThreshold = 3)
        assertEquals(Scope.NONE, SyncPolicy.autoScope(http, wifi, queued = 2, priorityQueued = 0))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, queued = 3, priorityQueued = 0))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, queued = 4, priorityQueued = 0))
    }

    @Test
    fun `autoSync false leaves normal records for a manual sync`() {
        val http = HttpConfig(url = url, autoSync = false)
        assertEquals(Scope.NONE, SyncPolicy.autoScope(http, wifi, queued = 100, priorityQueued = 0))
    }

    @Test
    fun `chunk size follows batchSync and maxBatchSize`() {
        assertEquals(1, SyncPolicy.chunkSize(HttpConfig(batchSync = false, maxBatchSize = 50)))
        assertEquals(50, SyncPolicy.chunkSize(HttpConfig(batchSync = true, maxBatchSize = 50)))
        assertEquals(1, SyncPolicy.chunkSize(HttpConfig(batchSync = true, maxBatchSize = 0)))
    }
}
