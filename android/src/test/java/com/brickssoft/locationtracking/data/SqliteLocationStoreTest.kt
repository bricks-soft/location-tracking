package com.brickssoft.locationtracking.data

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.PersistenceConfig
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import com.brickssoft.locationtracking.testing.testDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

/** End-to-end tests of [SqliteLocationStore] against a real [TrackingDatabase] file. */
@RunWith(RobolectricTestRunner::class)
class SqliteLocationStoreTest {
    private lateinit var context: Context
    private val clock = FakeClock()
    private val configStore = FakeConfigStore()
    private val logs = FakeLogStore()
    private val opened = mutableListOf<TrackingDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(Constants.DATABASE_NAME)
        Logger.sink = logs
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(Constants.DATABASE_NAME)
        Logger.sink = null
    }

    private fun openDatabase() = TrackingDatabase(context).also { opened += it }

    private fun TestScope.newStore(database: TrackingDatabase = openDatabase()) =
        SqliteLocationStore(database, configStore, clock, testDispatchers(testScheduler))

    private fun persistence(maxDays: Int = 7, maxRecords: Int = -1) {
        configStore.configFlow.value = Config(persistence = PersistenceConfig(maxDays, maxRecords))
    }

    private fun rec(id: String, event: RecordEvent = RecordEvent.LOCATION, recordedAt: Long = clock.now()): Record =
        Fixtures.record(uuid = id, event = event, recordedAt = recordedAt)

    private data class Row(
        val event: String,
        val recordedAt: Long,
        val json: String,
        val attempts: Int,
        val lastAttemptAt: Long?,
    )

    private fun row(database: TrackingDatabase, uuid: String): Row? =
        database.readableDatabase.rawQuery(
            "SELECT event, recorded_at, json, attempts, last_attempt_at FROM records WHERE uuid = ?",
            arrayOf(uuid),
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                Row(c.getString(0), c.getLong(1), c.getString(2), c.getInt(3), if (c.isNull(4)) null else c.getLong(4))
            }
        }

    private fun insertRaw(database: TrackingDatabase, uuid: String, event: String, recordedAt: Long, json: String) {
        database.writableDatabase.insertOrThrow(
            "records",
            null,
            ContentValues().apply {
                put("uuid", uuid)
                put("event", event)
                put("recorded_at", recordedAt)
                put("json", json)
            },
        )
    }

    // ---- insert / list round trip

    @Test
    fun `insert then list returns an equal record`() = runTest {
        val store = newStore()
        val record = Fixtures.record(extras = """{"driver_id":7}""")

        store.insert(record)

        assertEquals(listOf(record), store.list())
    }

    @Test
    fun `every record variant round-trips unchanged`() = runTest {
        val store = newStore()
        val t = clock.now()
        val variants = listOf(
            Fixtures.record(uuid = "full", recordedAt = t, extras = """{"a":{"b":[1,2,"x"],"ok":true},"n":1.5}"""),
            Fixtures.record(
                uuid = "hb-no-location",
                event = RecordEvent.HEARTBEAT,
                location = null,
                recordedAt = t + 1,
            ),
            Fixtures.record(
                uuid = "geofence",
                event = RecordEvent.GEOFENCE,
                recordedAt = t + 2,
                geofence = GeofenceHit("home", GeofenceAction.DWELL, """{"zone":"A","level":3}"""),
            ),
            Fixtures.record(
                uuid = "geofence-no-extras",
                event = RecordEvent.GEOFENCE,
                recordedAt = t + 3,
                geofence = GeofenceHit("work", GeofenceAction.EXIT, null),
            ),
            Fixtures.record(
                uuid = "providerchange",
                event = RecordEvent.PROVIDERCHANGE,
                recordedAt = t + 4,
                provider = Fixtures.providerState(
                    enabled = false,
                    gps = false,
                    network = true,
                    permission = PermissionLevel.WHEN_IN_USE,
                    accuracy = AccuracyLevel.APPROXIMATE,
                    backend = ProviderKind.HMS,
                ),
            ),
            Fixtures.record(uuid = "start", event = RecordEvent.TRACKING_START, recordedAt = t + 5, reason = "boot"),
            Fixtures.record(
                uuid = "stop",
                event = RecordEvent.TRACKING_STOP,
                location = null,
                recordedAt = t + 6,
                reason = "permission_denied",
                backend = null,
            ),
            Fixtures.record(
                uuid = "sparse-mock",
                event = RecordEvent.MOTIONCHANGE,
                recordedAt = t + 7,
                isMoving = false,
                location = Fixtures.location(
                    altitude = null,
                    altitudeAccuracy = null,
                    speed = null,
                    speedAccuracy = null,
                    heading = null,
                    headingAccuracy = null,
                    isMock = true,
                ),
                activity = ActivitySample(ActivityType.STILL, 100),
                battery = BatterySnapshot.UNKNOWN,
                bootCount = -1,
                odometer = 0.0,
            ),
            Fixtures.record(uuid = "current", event = RecordEvent.CURRENT_POSITION, recordedAt = t + 8),
            Fixtures.record(uuid = "watch", event = RecordEvent.WATCH_POSITION, recordedAt = t + 9),
        )

        variants.forEach { store.insert(it) }

        assertEquals(variants, store.list())
    }

    @Test
    fun `row holds the wire json without sent_at plus index columns`() = runTest {
        val database = openDatabase()
        val store = newStore(database)
        val record = Fixtures.record(uuid = "u1", event = RecordEvent.HEARTBEAT, extras = """{"k":1}""")

        store.insert(record)

        val row = row(database, "u1")!!
        assertEquals("heartbeat", row.event)
        assertEquals(record.recordedAt, row.recordedAt)
        assertEquals(0, row.attempts)
        assertNull(row.lastAttemptAt)
        val json = JSONObject(row.json)
        assertFalse(json.has("sent_at"))
        assertJsonEquals(RecordJson.toJson(record), json)
    }

    @Test
    fun `inserting an existing uuid replaces the row and resets attempts`() = runTest {
        val database = openDatabase()
        val store = newStore(database)
        store.insert(rec("u1"))
        store.markAttempt(listOf("u1"), 5L)
        val replacement = rec("u1").copy(odometer = 99.0, isMoving = false)

        store.insert(replacement)

        assertEquals(1, store.count())
        assertEquals(listOf(replacement), store.list())
        assertEquals(0, row(database, "u1")!!.attempts)
    }

    @Test
    fun `list is oldest first by recorded_at then insertion order`() = runTest {
        val store = newStore()
        val t = clock.now()
        store.insert(rec("r1", recordedAt = t + 2_000))
        store.insert(rec("r2", recordedAt = t + 1_000))
        store.insert(rec("r3", recordedAt = t + 1_000))
        store.insert(rec("r4", recordedAt = t))

        assertEquals(listOf("r4", "r2", "r3", "r1"), store.list().map { it.uuid })
    }

    @Test
    fun `list honours limit and event filter`() = runTest {
        val store = newStore()
        val t = clock.now()
        store.insert(rec("loc1", RecordEvent.LOCATION, t))
        store.insert(rec("hb1", RecordEvent.HEARTBEAT, t + 1))
        store.insert(rec("loc2", RecordEvent.LOCATION, t + 2))
        store.insert(rec("start", RecordEvent.TRACKING_START, t + 3))
        store.insert(rec("hb2", RecordEvent.HEARTBEAT, t + 4))
        val priority = RecordEvent.entries.filter { it.isPriority }.toSet()

        assertEquals(listOf("loc1", "hb1"), store.list(limit = 2).map { it.uuid })
        assertEquals(5, store.list(limit = -1).size)
        assertEquals(5, store.list(limit = 50).size)
        assertEquals(emptyList<Record>(), store.list(limit = 0))
        assertEquals(listOf("hb1", "start", "hb2"), store.list(events = priority).map { it.uuid })
        assertEquals(listOf("hb1", "start"), store.list(limit = 2, events = priority).map { it.uuid })
        assertEquals(listOf("loc1", "loc2"), store.list(events = setOf(RecordEvent.LOCATION)).map { it.uuid })
        assertEquals(emptyList<Record>(), store.list(events = emptySet()))
        assertEquals(emptyList<Record>(), store.list(events = setOf(RecordEvent.GEOFENCE)))
    }

    @Test
    fun `count counts all rows or the given events`() = runTest {
        val store = newStore()
        store.insert(rec("loc1", RecordEvent.LOCATION))
        store.insert(rec("hb1", RecordEvent.HEARTBEAT))
        store.insert(rec("hb2", RecordEvent.HEARTBEAT))
        store.insert(rec("stop", RecordEvent.TRACKING_STOP))

        assertEquals(4, store.count())
        assertEquals(2, store.count(setOf(RecordEvent.HEARTBEAT)))
        assertEquals(3, store.count(setOf(RecordEvent.HEARTBEAT, RecordEvent.TRACKING_STOP)))
        assertEquals(0, store.count(setOf(RecordEvent.GEOFENCE)))
        assertEquals(0, store.count(emptySet()))
    }

    // ---- delete / markAttempt

    @Test
    fun `delete removes only the given uuids and returns the number removed`() = runTest {
        val store = newStore()
        listOf("a", "b", "c").forEach { store.insert(rec(it)) }

        assertEquals(2, store.delete(listOf("a", "c", "c", "missing")))
        assertEquals(0, store.delete(emptyList()))
        assertEquals(listOf("b"), store.list().map { it.uuid })
    }

    @Test
    fun `delete of 600 uuids is chunked and atomic`() = runTest {
        val store = newStore()
        val t = clock.now()
        val uuids = (1..650).map { "u$it" }
        uuids.forEachIndexed { i, id -> store.insert(rec(id, recordedAt = t + i)) }

        val deleted = store.delete(uuids.take(600) + listOf("missing-1", "missing-2"))

        assertEquals(600, deleted)
        assertEquals(50, store.count())
        assertEquals(uuids.drop(600), store.list().map { it.uuid })
    }

    @Test
    fun `deleteAll removes every row and returns the count`() = runTest {
        val store = newStore()
        listOf("a", "b", "c").forEach { store.insert(rec(it, RecordEvent.HEARTBEAT)) }

        assertEquals(3, store.deleteAll())
        assertEquals(0, store.count())
        assertEquals(0, store.deleteAll())
    }

    @Test
    fun `markAttempt increments attempts and sets last_attempt_at`() = runTest {
        val database = openDatabase()
        val store = newStore(database)
        listOf("a", "b").forEach { store.insert(rec(it)) }

        store.markAttempt(listOf("a", "missing"), 1_000L)
        store.markAttempt(listOf("a"), 2_000L)
        store.markAttempt(emptyList(), 3_000L)

        assertEquals(2, row(database, "a")!!.attempts)
        assertEquals(2_000L, row(database, "a")!!.lastAttemptAt)
        assertEquals(0, row(database, "b")!!.attempts)
        assertNull(row(database, "b")!!.lastAttemptAt)
        assertEquals(2, store.count())
    }

    @Test
    fun `markAttempt of 600 uuids is chunked`() = runTest {
        val database = openDatabase()
        val store = newStore(database)
        val uuids = (1..600).map { "u$it" }
        uuids.forEach { store.insert(rec(it)) }

        store.markAttempt(uuids, 42L)

        for (uuid in listOf("u1", "u499", "u500", "u600")) {
            val row = row(database, uuid)!!
            assertEquals(uuid, 1, row.attempts)
            assertEquals(uuid, 42L, row.lastAttemptAt)
        }
    }

    // ---- corrupt rows / errors

    @Test
    fun `undecodable rows are skipped, logged and deleted without shrinking the page`() = runTest {
        val database = openDatabase()
        val store = newStore(database)
        val t = clock.now()
        store.insert(rec("good1", recordedAt = t))
        insertRaw(database, "bad-json", "location", t + 1, "{not json")
        insertRaw(database, "bad-event", "location", t + 2, """{"uuid":"bad-event","event":"teleport"}""")
        store.insert(rec("good2", recordedAt = t + 3))
        store.insert(rec("good3", recordedAt = t + 4))

        val page = store.list(limit = 2)

        assertEquals(listOf("good1", "good2"), page.map { it.uuid })
        assertEquals(3, store.count())
        assertNull(row(database, "bad-json"))
        assertNull(row(database, "bad-event"))
        val errors = logs.lines.filter { it.level == LogLevel.ERROR && it.tag == "LT.LocationStore" }
        assertEquals(2, errors.size)
        assertTrue(errors.any { "bad-json" in it.message })
    }

    @Test
    fun `a record that cannot be encoded is rejected as INVALID_ARGUMENT`() = runTest {
        val store = newStore()

        try {
            store.insert(rec("nan").copy(odometer = Double.NaN))
            fail("expected TrackingException")
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
        assertEquals(0, store.count())
    }

    // ---- pruning

    @Test
    fun `first use prunes records older than maxDaysToPersist, heartbeats included`() = runTest {
        persistence(maxDays = 7)
        val database = openDatabase()
        val first = newStore(database)
        first.insert(rec("old-heartbeat", RecordEvent.HEARTBEAT))
        first.insert(rec("old-start", RecordEvent.TRACKING_START))
        clock.advance(3 * DAY)
        first.insert(rec("recent", RecordEvent.LOCATION))
        clock.advance(5 * DAY) // old-* are now 8 days old, recent is 5 days old

        val second = newStore(database)

        assertEquals(listOf("recent"), second.list().map { it.uuid })
        assertEquals(1, first.count())
    }

    @Test
    fun `age pruning runs again after every 50 inserts`() = runTest {
        persistence(maxDays = 7)
        val store = newStore()
        store.insert(rec("old", RecordEvent.HEARTBEAT)) // insert 1 (first-use prune ran on the empty table)
        clock.advance(8 * DAY)
        (2..49).forEach { store.insert(rec("new$it")) }

        assertEquals(49, store.count())
        assertEquals("old", store.list(limit = 1).single().uuid)

        store.insert(rec("new50")) // insert 50 -> prune

        assertEquals(49, store.count())
        assertEquals("new2", store.list(limit = 1).single().uuid)
    }

    @Test
    fun `keeps only the newest maxRecordsToPersist records`() = runTest {
        persistence(maxDays = 7, maxRecords = 10)
        val database = openDatabase()
        val store = newStore(database)
        val t = clock.now()

        (1..60).forEach { store.insert(rec("r$it", recordedAt = t + it)) }

        // pruned after insert 50 (kept r41..r50), then r51..r60 were added
        assertEquals((41..60).map { "r$it" }, store.list().map { it.uuid })

        val restarted = newStore(database)

        assertEquals((51..60).map { "r$it" }, restarted.list().map { it.uuid })
    }

    @Test
    fun `max-records pruning breaks recorded_at ties by insertion order`() = runTest {
        persistence(maxDays = 7, maxRecords = 2)
        val database = openDatabase()
        val first = newStore(database)
        val t = clock.now()
        first.insert(rec("a", recordedAt = t))
        first.insert(rec("b", recordedAt = t))
        first.insert(rec("c", recordedAt = t))

        val second = newStore(database)

        assertEquals(listOf("b", "c"), second.list().map { it.uuid })
    }

    @Test
    fun `non-positive limits disable pruning`() = runTest {
        persistence(maxDays = 0, maxRecords = -1)
        val database = openDatabase()
        val first = newStore(database)
        first.insert(rec("ancient", recordedAt = clock.now() - 365 * DAY))
        (1..60).forEach { first.insert(rec("r$it")) }

        val second = newStore(database)

        assertEquals(61, second.count())
    }

    // ---- persistence and concurrency

    @Test
    fun `records and attempts survive a new database instance`() = runTest {
        val firstDatabase = openDatabase()
        val first = newStore(firstDatabase)
        val t = clock.now()
        val records = listOf(
            rec("a", RecordEvent.TRACKING_START, t).copy(reason = "start"),
            rec("b", RecordEvent.HEARTBEAT, t + 1).copy(location = null),
            rec("c", RecordEvent.LOCATION, t + 2),
        )
        records.forEach { first.insert(it) }
        first.markAttempt(listOf("b"), 7L)
        firstDatabase.close()

        val secondDatabase = openDatabase()
        val second = newStore(secondDatabase)

        assertEquals(records, second.list())
        assertEquals(1, second.count(setOf(RecordEvent.HEARTBEAT)))
        assertEquals(1, row(secondDatabase, "b")!!.attempts)
        assertEquals(7L, row(secondDatabase, "b")!!.lastAttemptAt)
    }

    @Test
    fun `concurrent writers and readers on many threads`() {
        persistence(maxDays = 7, maxRecords = -1)
        val pool = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        try {
            val store = SqliteLocationStore(openDatabase(), configStore, clock, AppDispatchers(pool, pool, pool))
            runBlocking {
                val writers = (1..200).map { i ->
                    async(Dispatchers.Default) { store.insert(rec("u$i", recordedAt = clock.now() + i)) }
                }
                val readers = (1..20).map {
                    async(Dispatchers.Default) { store.list(limit = 10).size + store.count() }
                }
                (writers + readers).awaitAll()
                assertEquals(200, store.count())
                store.markAttempt((1..200).map { "u$it" }, 1L)
                val deletes = (1..200).chunked(20).map { chunk ->
                    async(Dispatchers.Default) { store.delete(chunk.map { "u$it" }) }
                }

                assertEquals(200, deletes.awaitAll().sum())
                assertEquals(0, store.count())
            }
        } finally {
            pool.close()
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
