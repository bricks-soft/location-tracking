package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.http.SyncPolicy.IntervalCheck
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
    // ---- syncInterval (round 2 §4)

    @Test
    fun `normal events are every event that is not a priority event`() {
        assertEquals(
            setOf(
                RecordEvent.LOCATION,
                RecordEvent.MOTIONCHANGE,
                RecordEvent.CURRENT_POSITION,
                RecordEvent.WATCH_POSITION,
                RecordEvent.GEOFENCE,
            ),
            SyncPolicy.NORMAL_EVENTS,
        )
    }

    @Test
    fun `syncInterval applies only with autoSync`() {
        assertTrue(SyncPolicy.usesInterval(HttpConfig(syncInterval = 300)))
        assertFalse(SyncPolicy.usesInterval(HttpConfig(syncInterval = 300, autoSync = false)))
        assertFalse(SyncPolicy.usesInterval(HttpConfig(syncInterval = 0)))
        assertEquals(300_000L, SyncPolicy.intervalMs(HttpConfig(syncInterval = 300)))
    }

    @Test
    fun `the oldest normal record is due at syncInterval, or at once with a negative age`() {
        val http = HttpConfig(url = url, syncInterval = 300)
        val recordedAt = 1_000_000L
        assertFalse(SyncPolicy.isIntervalDue(http, recordedAt, now = recordedAt))
        assertFalse(SyncPolicy.isIntervalDue(http, recordedAt, now = recordedAt + 299_999))
        assertTrue(SyncPolicy.isIntervalDue(http, recordedAt, now = recordedAt + 300_000))
        assertTrue(SyncPolicy.isIntervalDue(http, recordedAt, now = recordedAt - 1)) // clock set back
        assertEquals(recordedAt + 300_000, SyncPolicy.intervalDueAt(http, recordedAt, now = recordedAt + 10))
        assertEquals(null, SyncPolicy.intervalDueAt(http, recordedAt, now = recordedAt + 300_000))
        assertEquals(null, SyncPolicy.intervalDueAt(http, recordedAt, now = recordedAt - 60_000))
    }

    @Test
    fun `with syncInterval normal records wait until the oldest is due`() {
        val http = HttpConfig(url = url, syncInterval = 300)
        assertEquals(Scope.NONE, SyncPolicy.autoScope(http, wifi, 50, priorityQueued = 0, IntervalCheck.NOT_DUE))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, 1, priorityQueued = 0, IntervalCheck.DUE))
        assertEquals(Scope.NONE, SyncPolicy.autoScope(http, wifi, 0, priorityQueued = 0, IntervalCheck.DUE))
    }

    @Test
    fun `with syncInterval a positive threshold is a size cap`() {
        val http = HttpConfig(url = url, syncInterval = 300, autoSyncThreshold = 10)
        assertEquals(Scope.NONE, SyncPolicy.autoScope(http, wifi, 9, priorityQueued = 0, IntervalCheck.NOT_DUE))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, 10, priorityQueued = 0, IntervalCheck.NOT_DUE))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, 2, priorityQueued = 0, IntervalCheck.DUE))
    }

    @Test
    fun `a pending retry holds normal records, even over the size cap`() {
        val http = HttpConfig(url = url, syncInterval = 300, autoSyncThreshold = 10)
        val retry = IntervalCheck.RETRY_PENDING
        assertEquals(Scope.NONE, SyncPolicy.autoScope(http, wifi, 50, priorityQueued = 0, retry))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, 50, priorityQueued = 1, retry))
    }

    @Test
    fun `with the interval rule off the threshold rule applies`() {
        // IntervalCheck.OFF is also what the syncer passes while tracking is off.
        val http = HttpConfig(url = url, syncInterval = 300)
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, 1, priorityQueued = 0, IntervalCheck.OFF))
        val threshold = HttpConfig(url = url, syncInterval = 300, autoSyncThreshold = 3)
        assertEquals(Scope.NONE, SyncPolicy.autoScope(threshold, wifi, 2, priorityQueued = 0, IntervalCheck.OFF))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(threshold, wifi, 3, priorityQueued = 0, IntervalCheck.OFF))
    }

    @Test
    fun `with syncInterval priority records still upload at once`() {
        val http = HttpConfig(url = url, syncInterval = 300, autoSync = false)
        assertEquals(Scope.ALL, SyncPolicy.autoScope(http, wifi, 5, priorityQueued = 1, IntervalCheck.NOT_DUE))
        val restricted = HttpConfig(url = url, syncInterval = 300, disableAutoSyncOnCellular = true)
        assertEquals(
            Scope.PRIORITY_ONLY,
            SyncPolicy.autoScope(restricted, cellular, 5, priorityQueued = 1, IntervalCheck.NOT_DUE),
        )
    }

    @Test
    fun `with syncInterval cellular restriction and autoSync off still hold normal records`() {
        val restricted = HttpConfig(url = url, syncInterval = 300, disableAutoSyncOnCellular = true)
        assertEquals(Scope.NONE, SyncPolicy.autoScope(restricted, cellular, 5, priorityQueued = 0, IntervalCheck.DUE))
        assertEquals(Scope.ALL, SyncPolicy.autoScope(restricted, wifi, 5, priorityQueued = 0, IntervalCheck.DUE))
        val manual = HttpConfig(url = url, syncInterval = 300, autoSync = false)
        assertEquals(Scope.NONE, SyncPolicy.autoScope(manual, wifi, 5, priorityQueued = 0, IntervalCheck.DUE))
    }
}

