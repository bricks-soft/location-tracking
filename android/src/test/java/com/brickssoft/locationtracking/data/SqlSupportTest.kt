package com.brickssoft.locationtracking.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqlSupportTest {
    private lateinit var database: TrackingDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = TrackingDatabase(context, name = null)
    }

    @After
    fun tearDown() {
        database.close()
        Logger.sink = null
    }

    private fun insertGeofence(id: String, json: String) {
        database.writableDatabase.insertOrThrow(
            "geofences",
            null,
            ContentValues().apply {
                put("id", id)
                put("json", json)
            },
        )
    }

    private fun geofenceCount() = DatabaseUtils.queryNumEntries(database.readableDatabase, "geofences")

    @Test
    fun `placeholders joins question marks`() {
        assertEquals("?", placeholders(1))
        assertEquals("?,?,?", placeholders(3))
    }

    @Test
    fun `deleteIn deletes across chunks and ignores duplicates`() {
        val ids = (1..1_100).map { "g$it" }
        ids.forEach { insertGeofence(it, "{}") }

        val deleted = database.writableDatabase.deleteIn("geofences", "id", ids.take(1_050) + ids.take(10) + "missing")

        assertEquals(1_050, deleted)
        assertEquals(50L, geofenceCount())
    }

    @Test
    fun `deleteUndecodable keeps a row whose json changed since it was read`() {
        insertGeofence("stale", "{bad")
        insertGeofence("rewritten", "{\"identifier\":\"rewritten\"}")

        val deleted = database.writableDatabase.deleteUndecodable(
            "geofences",
            "id",
            "json",
            listOf(UndecodableRow("stale", "{bad"), UndecodableRow("rewritten", "{bad-old-text")),
        )

        assertEquals(1, deleted)
        assertEquals(1L, geofenceCount())
    }

    @Test
    fun `inTransaction rolls back when the body throws`() {
        val failure = IllegalStateException("boom")
        try {
            database.writableDatabase.inTransaction {
                insertGeofence("a", "{}")
                throw failure
            }
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }

        assertEquals(0L, geofenceCount())
    }

    @Test
    fun `ioErrors wraps failures as IO_ERROR but keeps TrackingException`() {
        try {
            ioErrors("op") { throw IllegalStateException("disk full") }
            fail("expected TrackingException")
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.IO_ERROR, e.code)
            assertEquals("op failed: disk full", e.message)
        }
        val original = TrackingException(ErrorCode.INVALID_ARGUMENT, "bad")
        try {
            ioErrors("op") { throw original }
            fail("expected TrackingException")
        } catch (e: TrackingException) {
            assertSame(original, e)
        }
    }

    @Test
    fun `encoding maps JSONException to INVALID_ARGUMENT`() {
        try {
            encoding("thing") { throw JSONException("Forbidden numeric value: NaN") }
            fail("expected TrackingException")
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
    }
}
