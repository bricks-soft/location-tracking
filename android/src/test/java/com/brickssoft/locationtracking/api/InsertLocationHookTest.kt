package com.brickssoft.locationtracking.api

import com.brickssoft.locationtracking.bridge.BridgeServices
import com.brickssoft.locationtracking.bridge.PluginHandlers
import com.brickssoft.locationtracking.bridge.TestServices
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.RecordHooks
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.Record
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** The JS bridge's `insertLocation` hands its record to the record hooks (it bypasses the record sink). */
@RunWith(RobolectricTestRunner::class)
class InsertLocationHookTest {
    private val calls = CopyOnWriteArrayList<String>()
    private val base = TestServices()
    private val hooks = RecordHooks().apply { add { calls += "hook:${it.uuid}" } }
    private val services = object : BridgeServices by base {
        override val locationStore: LocationStore = object : LocationStore by base.locationStore {
            override suspend fun insert(record: Record) {
                base.locationStore.insert(record)
                calls += "insert:${record.uuid}"
            }
        }
        override val syncer: HttpSyncer = object : HttpSyncer by base.syncer {
            override fun onRecordInserted(record: Record) {
                calls += "sync:${record.uuid}"
            }
        }
        override val recordHooks: RecordHooks = hooks
    }
    private val handlers = PluginHandlers(services, readyFlag = AtomicBoolean(true))

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun input() = JSONObject("""{"location":{"coords":{"latitude":24.7,"longitude":46.6}}}""")

    @Test
    fun `the inserted record reaches the record hooks after the insert and before the upload policy`() = runTest {
        val uuid = handlers.insertLocation(input()).getString("uuid")

        assertEquals(listOf("insert:$uuid", "hook:$uuid", "sync:$uuid"), calls.toList())
    }

    @Test
    fun `a failed insert rejects the call and dispatches nothing`() = runTest {
        base.locationStore.failInsertWith = IOException("disk full")

        try {
            handlers.insertLocation(input())
            fail("expected the insert failure")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `invalid input dispatches nothing`() = runTest {
        try {
            handlers.insertLocation(JSONObject("""{"location":{}}"""))
            fail("expected INVALID_ARGUMENT")
        } catch (e: Exception) {
            // TrackingException(INVALID_ARGUMENT); the code is covered by PluginHandlersTest.
        }
        assertTrue(calls.isEmpty())
    }
}
