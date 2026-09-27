package com.brickssoft.premisemonitor

import com.brickssoft.locationtracking.core.Iso8601
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The bounded audit log: newest entries kept, uploaded older ones deleted, a hard cap for pending ones. */
@RunWith(RobolectricTestRunner::class)
internal class AuditStoreTest {
    private lateinit var app: Application
    private val stores = ArrayList<AuditStore>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        stores.forEach { it.close() }
    }

    private fun store(name: String, keepNewest: Int, hardCap: Int, pruneEvery: Int) =
        AuditStore(app, name, keepNewest, hardCap, pruneEvery).also { stores.add(it) }

    private fun entry(n: Int): JSONObject =
        JSONObject().put("id", "e$n").put("at", Iso8601.format(1_000L * n)).put("kind", "event").put("n", n)

    private fun ids(texts: List<String>) = texts.map { JSONObject(it).getString("id") }

    @Test
    fun keepsTheNewestAndDeletesOlderUploadedEntries() {
        val store = store("small.db", keepNewest = 10, hardCap = 25, pruneEvery = 1000)
        val seqs = (1..20).map { store.append(entry(it)) }
        store.markUploaded(seqs.take(5))
        store.prune()

        // e1..e5 were uploaded and older than the newest 10: deleted. e6..e10 are older but still pending: kept.
        assertEquals((6..20).map { "e$it" }, ids(store.recent(0)))
        assertEquals((6..20).map { "e$it" }, store.pending(100).map { JSONObject(it.json).getString("id") })
        assertEquals(15, store.pendingCount())
    }

    @Test
    fun theHardCapDropsTheOldestEvenIfPending() {
        val store = store("cap.db", keepNewest = 10, hardCap = 25, pruneEvery = 1)
        (1..40).forEach { store.append(entry(it)) }
        assertEquals(25, store.count())
        assertEquals((16..40).map { "e$it" }, ids(store.recent(0)))
    }

    @Test
    fun defaultBoundsKeepAtLeastTheLast1000() {
        val store = store("default.db", AuditStore.KEEP_NEWEST, AuditStore.HARD_CAP, AuditStore.PRUNE_EVERY)
        val seqs = (1..1100).map { store.append(entry(it)) }
        store.markUploaded(seqs)
        store.append(entry(1101))
        store.prune()

        val kept = ids(store.recent(0))
        assertEquals(1000, kept.size)
        assertEquals("e102", kept.first())
        assertEquals("e1101", kept.last())
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun recentReturnsTheNewestLastAndLastEntryAt() {
        val store = store("recent.db", 10, 25, 1000)
        assertNull(store.lastEntryAt())
        (1..5).forEach { store.append(entry(it)) }
        assertEquals(listOf("e3", "e4", "e5"), ids(store.recent(3)))
        assertEquals((1..5).map { "e$it" }, ids(store.recent(0)))
        assertEquals(Iso8601.format(5_000L), store.lastEntryAt())
        assertEquals(listOf("e1", "e2"), store.pending(2).map { JSONObject(it.json).getString("id") })
    }
}
