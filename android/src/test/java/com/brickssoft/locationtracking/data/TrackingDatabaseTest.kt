package com.brickssoft.locationtracking.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackingDatabaseTest {
    private lateinit var context: Context
    private val opened = mutableListOf<TrackingDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(Constants.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(Constants.DATABASE_NAME)
        Logger.sink = null
    }

    private fun open() = TrackingDatabase(context).also { opened += it }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?,
        val pk: Int,
    )

    private fun columns(database: TrackingDatabase, table: String): List<Column> =
        database.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val out = mutableListOf<Column>()
            while (c.moveToNext()) {
                out += Column(
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    type = c.getString(c.getColumnIndexOrThrow("type")),
                    notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                    default = c.getString(c.getColumnIndexOrThrow("dflt_value")),
                    pk = c.getInt(c.getColumnIndexOrThrow("pk")),
                )
            }
            out
        }

    private fun indexColumns(database: TrackingDatabase, index: String): List<String> =
        database.readableDatabase.rawQuery("PRAGMA index_info($index)", null).use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
            names
        }

    @Test
    fun `uses the scaffold database name and schema version 1`() {
        val database = open()

        assertEquals(Constants.DATABASE_NAME, database.databaseName)
        assertEquals(1, TrackingDatabase.VERSION)
        assertEquals(1, database.writableDatabase.version)
        assertTrue(context.getDatabasePath(Constants.DATABASE_NAME).exists())
    }

    @Test
    fun `creates the records table`() {
        val database = open()

        assertEquals(
            listOf(
                Column("uuid", "TEXT", notNull = true, default = null, pk = 1),
                Column("event", "TEXT", notNull = true, default = null, pk = 0),
                Column("recorded_at", "INTEGER", notNull = true, default = null, pk = 0),
                Column("json", "TEXT", notNull = true, default = null, pk = 0),
                Column("attempts", "INTEGER", notNull = true, default = "0", pk = 0),
                Column("last_attempt_at", "INTEGER", notNull = false, default = null, pk = 0),
            ),
            columns(database, "records"),
        )
    }

    @Test
    fun `indexes records by recorded_at and by event`() {
        val database = open()

        val indexed = database.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'records' AND sql IS NOT NULL",
            null,
        ).use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(0)
            names
        }
        assertEquals(setOf("records_recorded_at", "records_event_recorded_at"), indexed.toSet())
        assertEquals(listOf("recorded_at"), indexColumns(database, "records_recorded_at"))
        assertEquals(listOf("event", "recorded_at"), indexColumns(database, "records_event_recorded_at"))
    }

    @Test
    fun `creates the geofences table`() {
        val database = open()

        assertEquals(
            listOf(
                Column("id", "TEXT", notNull = true, default = null, pk = 1),
                Column("json", "TEXT", notNull = true, default = null, pk = 0),
                Column("runtime_json", "TEXT", notNull = false, default = null, pk = 0),
            ),
            columns(database, "geofences"),
        )
    }

    @Test
    fun `enables write-ahead logging`() {
        val database = open()

        val db = database.writableDatabase
        assertTrue(db.isWriteAheadLoggingEnabled)
        val mode = db.rawQuery("PRAGMA journal_mode", null).use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("wal", mode.lowercase())
    }

    @Test
    fun `a new instance reads rows written by a previous one`() {
        val first = open()
        first.writableDatabase.insertOrThrow(
            "geofences",
            null,
            ContentValues().apply {
                put("id", "home")
                put("json", "{}")
            },
        )
        first.writableDatabase.insertOrThrow(
            "records",
            null,
            ContentValues().apply {
                put("uuid", "u1")
                put("event", "heartbeat")
                put("recorded_at", 1L)
                put("json", "{}")
            },
        )
        first.close()

        val second = open()

        val db = second.readableDatabase
        assertEquals(1L, DatabaseUtils.queryNumEntries(db, "records"))
        assertEquals(1L, DatabaseUtils.queryNumEntries(db, "geofences"))
    }

    @Test
    fun `a downgrade from a newer schema keeps the data`() {
        val newer = open()
        newer.writableDatabase.insertOrThrow(
            "records",
            null,
            ContentValues().apply {
                put("uuid", "queued")
                put("event", "heartbeat")
                put("recorded_at", 1L)
                put("json", "{}")
            },
        )
        newer.writableDatabase.version = 2
        newer.close()

        val older = open()

        val db = older.writableDatabase
        assertEquals(1, db.version)
        assertEquals(1L, DatabaseUtils.queryNumEntries(db, "records"))
    }

    @Test
    fun `a null name opens an in-memory database with the same schema`() {
        val database = TrackingDatabase(context, name = null).also { opened += it }

        assertEquals(6, columns(database, "records").size)
        assertEquals(3, columns(database, "geofences").size)
    }
}
