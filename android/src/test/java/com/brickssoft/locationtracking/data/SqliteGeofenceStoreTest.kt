package com.brickssoft.locationtracking.data

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.GeofenceJson
import com.brickssoft.locationtracking.model.LatLng
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import com.brickssoft.locationtracking.testing.testDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** End-to-end tests of [SqliteGeofenceStore] against a real [TrackingDatabase] file. */
@RunWith(RobolectricTestRunner::class)
class SqliteGeofenceStoreTest {
    private lateinit var context: Context
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
        SqliteGeofenceStore(database, testDispatchers(testScheduler))

    /** A polygon whose enclosing circle has been computed (as the GeofenceManager stores it). */
    private val zone = Fixtures.polygon(
        identifier = "zone",
        vertices = listOf(
            LatLng(24.7127, 46.6743),
            LatLng(24.7127, 46.6763),
            LatLng(24.7145, 46.6763),
            LatLng(24.7140, 46.6750),
            LatLng(24.7145, 46.6743),
        ),
        notifyOnDwell = true,
        loiteringDelay = 120_000L,
        extras = """{"site":"hq","floors":[1,2]}""",
    ).copy(latitude = 24.7136, longitude = 46.6753, radius = 141.42136f)

    private val home = Fixtures.circle(
        identifier = "home",
        radius = 150.5f,
        notifyOnExit = false,
        extras = """{"owner":"me"}""",
    )

    private fun runtimeJson(database: TrackingDatabase, id: String): String? =
        database.readableDatabase.rawQuery("SELECT runtime_json FROM geofences WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    @Test
    fun `circle and polygon round-trip through upsert and get`() = runTest {
        val store = newStore()

        store.upsert(listOf(home, zone))

        assertEquals(home, store.get("home"))
        assertEquals(zone, store.get("zone"))
        assertNull(store.get("missing"))
        assertEquals(2, store.count())
    }

    @Test
    fun `row json is the GeofenceJson shape`() = runTest {
        val database = openDatabase()
        val store = newStore(database)

        store.upsert(listOf(zone))

        val json = database.readableDatabase.rawQuery("SELECT json FROM geofences WHERE id = 'zone'", null)
            .use { c -> c.moveToFirst(); c.getString(0) }
        assertJsonEquals(GeofenceJson.toJson(zone), JSONObject(json))
    }

    @Test
    fun `all is ordered by identifier`() = runTest {
        val store = newStore()

        store.upsert(listOf(Fixtures.circle("charlie"), Fixtures.circle("alpha"), Fixtures.circle("bravo")))

        assertEquals(listOf("alpha", "bravo", "charlie"), store.all().map { it.identifier })
    }

    @Test
    fun `upsert replaces an existing spec and keeps its runtime`() = runTest {
        val store = newStore()
        store.upsert(listOf(home))
        val runtime = GeofenceRuntime(insideCircle = true, insidePolygon = false, enteredAt = 1_000L)
        store.setRuntime("home", runtime)
        val moved = home.copy(latitude = 25.0, radius = 300f, extras = null)

        store.upsert(listOf(moved))

        assertEquals(listOf(moved), store.all())
        assertEquals(mapOf("home" to runtime), store.runtimes())
    }

    @Test
    fun `duplicate identifiers in one upsert keep the last spec`() = runTest {
        val store = newStore()
        val last = home.copy(radius = 999f)

        store.upsert(listOf(home, last))

        assertEquals(listOf(last), store.all())
    }

    @Test
    fun `remove deletes the given ids with their runtime and returns the count`() = runTest {
        val store = newStore()
        store.upsert(listOf(home, zone, Fixtures.circle("work")))
        store.setRuntime("zone", GeofenceRuntime(insideCircle = true, insidePolygon = true, enteredAt = 5L))

        assertEquals(2, store.remove(listOf("zone", "work", "work", "missing")))
        assertEquals(0, store.remove(emptyList()))
        assertEquals(listOf(home), store.all())
        assertEquals(emptyMap<String, GeofenceRuntime>(), store.runtimes())

        store.upsert(listOf(zone))
        assertEquals(emptyMap<String, GeofenceRuntime>(), store.runtimes())
    }

    @Test
    fun `remove of 600 ids is chunked`() = runTest {
        val store = newStore()
        val ids = (1..650).map { "g%03d".format(it) }
        store.upsert(ids.map { Fixtures.circle(it) })

        assertEquals(600, store.remove(ids.take(600) + "missing"))
        assertEquals(ids.drop(600), store.all().map { it.identifier })
    }

    @Test
    fun `removeAll deletes everything and returns the count`() = runTest {
        val store = newStore()
        store.upsert(listOf(home, zone))
        store.setRuntime("home", GeofenceRuntime(insideCircle = true, insidePolygon = false, enteredAt = null))

        assertEquals(2, store.removeAll())
        assertEquals(0, store.count())
        assertEquals(emptyList<Any>(), store.all())
        assertEquals(emptyMap<String, GeofenceRuntime>(), store.runtimes())
        assertEquals(0, store.removeAll())
    }

    @Test
    fun `runtimes round-trip and are stored as insideCircle, insidePolygon, enteredAt`() = runTest {
        val database = openDatabase()
        val store = newStore(database)
        store.upsert(listOf(home, zone, Fixtures.circle("idle")))
        val homeRuntime = GeofenceRuntime(insideCircle = true, insidePolygon = false, enteredAt = null)
        val zoneRuntime = GeofenceRuntime(insideCircle = true, insidePolygon = true, enteredAt = 1_790_417_730_456L)

        store.setRuntime("home", homeRuntime)
        store.setRuntime("zone", zoneRuntime)
        store.setRuntime("missing", zoneRuntime)

        assertEquals(mapOf("home" to homeRuntime, "zone" to zoneRuntime), store.runtimes())
        assertJsonEquals(
            """{"insideCircle":true,"insidePolygon":true,"enteredAt":1790417730456}""",
            JSONObject(runtimeJson(database, "zone")!!),
        )
        assertJsonEquals(
            """{"insideCircle":true,"insidePolygon":false,"enteredAt":null}""",
            JSONObject(runtimeJson(database, "home")!!),
        )
        assertNull(runtimeJson(database, "idle"))
        assertEquals(3, store.count())
    }

    @Test
    fun `setRuntime overwrites the previous runtime`() = runTest {
        val store = newStore()
        store.upsert(listOf(zone))
        store.setRuntime("zone", GeofenceRuntime(insideCircle = true, insidePolygon = true, enteredAt = 1L))
        val exited = GeofenceRuntime(insideCircle = false, insidePolygon = false, enteredAt = null)

        store.setRuntime("zone", exited)

        assertEquals(mapOf("zone" to exited), store.runtimes())
    }

    @Test
    fun `undecodable rows are skipped, logged and deleted`() = runTest {
        val database = openDatabase()
        val store = newStore(database)
        store.upsert(listOf(home))
        for ((id, json) in listOf("broken" to "{oops", "no-geometry" to """{"identifier":"no-geometry"}""")) {
            database.writableDatabase.insertOrThrow(
                "geofences",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("json", json)
                },
            )
        }
        database.writableDatabase.execSQL("UPDATE geofences SET runtime_json = 'garbage' WHERE id = 'home'")

        assertEquals(listOf(home), store.all())
        assertEquals(1, store.count())
        assertEquals(emptyMap<String, GeofenceRuntime>(), store.runtimes())
        val errors = logs.lines.filter { it.level == LogLevel.ERROR && it.tag == "LT.GeofenceStore" }
        assertEquals(2, errors.size)
        assertTrue(logs.lines.any { it.level == LogLevel.WARN && "home" in it.message })
    }

    @Test
    fun `get of an undecodable row returns null and deletes it`() = runTest {
        val database = openDatabase()
        val store = newStore(database)
        database.writableDatabase.insertOrThrow(
            "geofences",
            null,
            ContentValues().apply {
                put("id", "broken")
                put("json", "[]")
            },
        )

        assertNull(store.get("broken"))
        assertEquals(0, store.count())
    }

    @Test
    fun `a geofence that cannot be encoded is rejected as INVALID_ARGUMENT`() = runTest {
        val store = newStore()

        try {
            store.upsert(listOf(home, Fixtures.circle("nan", latitude = Double.NaN)))
            fail("expected TrackingException")
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
        assertEquals(0, store.count())
    }

    @Test
    fun `geofences and runtimes survive a new database instance`() = runTest {
        val firstDatabase = openDatabase()
        val first = newStore(firstDatabase)
        first.upsert(listOf(zone, home))
        val runtime = GeofenceRuntime(insideCircle = true, insidePolygon = true, enteredAt = 77L)
        first.setRuntime("zone", runtime)
        firstDatabase.close()

        val second = newStore(openDatabase())

        assertEquals(listOf(home, zone), second.all())
        assertEquals(mapOf("zone" to runtime), second.runtimes())
    }
}
