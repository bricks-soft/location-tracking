package com.brickssoft.locationtracking.config

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.ConfigSamples.CUSTOM
import com.brickssoft.locationtracking.config.ConfigSamples.CUSTOM_JSON
import com.brickssoft.locationtracking.config.ConfigSamples.RUNTIME
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.LocationProviderSetting
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
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
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SharedPrefsConfigStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val log = FakeLogStore()
    private val prefs: SharedPreferences get() = app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        Logger.sink = log
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    /** Stands in for the encrypted tokens file (Robolectric has no Android Keystore); it outlives store instances. */
    private val secrets = SecretStore.inMemory()

    /** A new instance reads everything back from the prefs file and [secrets], as after a process restart. */
    private fun newStore(secretStore: SecretStore = secrets) = SharedPrefsConfigStore(app, clock, secretStore)

    // ---- load

    @Test
    fun `a fresh install starts with defaults`() {
        val store = newStore()

        assertEquals(Config(), store.config.value)
        assertEquals(RuntimeState(), store.runtime.value)
    }

    @Test
    fun `unreadable persisted values fall back to defaults`() {
        prefs.edit()
            .putString(SharedPrefsConfigStore.KEY_CONFIG, "{not json")
            .putString(SharedPrefsConfigStore.KEY_RUNTIME, "[]")
            .commit()

        val store = newStore()

        assertEquals(Config(), store.config.value)
        assertEquals(RuntimeState(), store.runtime.value)
        assertEquals(2, log.lines.count { it.level == LogLevel.ERROR })
    }

    @Test
    fun `invalid persisted values fall back to their defaults and keep the rest`() {
        val stored = JSONObject(CUSTOM_JSON)
        stored.getJSONObject("http").put("method", "GET").put("timeout", "soon")
        stored.getJSONObject("http").getJSONObject("authorization").put("strategy", "Bearer")
        stored.getJSONObject("notification").put("actions", JSONObject("""{"x":[{"id":""}]}""").getJSONArray("x"))
        stored.put("locationProvider", "future-backend")
        prefs.edit().putString(SharedPrefsConfigStore.KEY_CONFIG, stored.toString()).commit()

        val loaded = newStore().config.value

        val expected = CUSTOM.copy(
            http = CUSTOM.http.copy(
                method = HttpMethod.POST,
                timeout = 60_000,
                authorization = CUSTOM.http.authorization!!.copy(strategy = "JWT"),
            ),
            notification = CUSTOM.notification.copy(actions = emptyList()),
            locationProvider = LocationProviderSetting.AUTO,
        )
        assertEquals(expected, loaded)
        assertEquals(5, log.lines.count { it.level == LogLevel.WARN })
    }

    @Test
    fun `a bad value written through update does not discard the stored url and tokens`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))
        store.update { c ->
            c.copy(
                http = c.http.copy(authorization = c.http.authorization!!.copy(strategy = "Bearer")),
                notification = c.notification.copy(actions = listOf(NotificationActionButton(" ", "Blank"))),
            )
        }

        val reloaded = newStore().config.value

        assertEquals(CUSTOM.http, reloaded.http)
        assertEquals(CUSTOM.notification.copy(actions = emptyList()), reloaded.notification)
    }

    @Test
    fun `a persisted config is clamped on load`() {
        prefs.edit().putString(SharedPrefsConfigStore.KEY_CONFIG, """{"heartbeat":{"minInterval":5}}""").commit()

        assertEquals(60, newStore().config.value.heartbeat.minInterval)
    }

    // ---- ready

    @Test
    fun `ready with reset=false applies the config on the very first run only`() {
        val first = newStore()
        val applied = first.ready(JSONObject(CUSTOM_JSON), reset = false)

        assertEquals(CUSTOM, applied)
        assertEquals(CUSTOM, first.config.value)
        assertTrue(first.runtime.value.didReady)

        first.merge(JSONObject("""{"geolocation":{"distanceFilter":99}}"""))

        // next launch: the given config is ignored and the persisted one (with the later merge) wins
        val second = newStore()
        assertTrue(second.runtime.value.didReady)
        val kept = second.ready(JSONObject("""{"geolocation":{"distanceFilter":1},"heartbeat":null}"""), reset = false)

        val expected = CUSTOM.copy(geolocation = CUSTOM.geolocation.copy(distanceFilter = 99.0))
        assertEquals(expected, kept)
        assertEquals(expected, second.config.value)
    }

    @Test
    fun `ready with reset=false and no config on the first run keeps the defaults`() {
        val store = newStore()

        assertEquals(Config(), store.ready(null, reset = false))
        assertTrue(newStore().runtime.value.didReady)
    }

    @Test
    fun `ready with reset=true applies defaults plus the config on every launch`() {
        val first = newStore()
        first.ready(JSONObject(CUSTOM_JSON), reset = true)
        first.merge(JSONObject("""{"logger":{"logMaxDays":30}}"""))
        first.updateRuntime { it.copy(odometer = 12.5, enabled = true) }

        val second = newStore()
        val result = second.ready(JSONObject("""{"http":{"url":"https://example.com/b"}}"""), reset = true)

        assertEquals(Config(http = HttpConfig(url = "https://example.com/b")), result)
        assertEquals(result, second.config.value)
        assertEquals(result, newStore().config.value)
        // runtime state survives a reset of the config
        assertEquals(12.5, second.runtime.value.odometer, 0.0)
        assertTrue(second.runtime.value.enabled)
        assertTrue(second.runtime.value.didReady)
    }

    @Test
    fun `ready with reset=true and no config restores the defaults`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))

        assertEquals(Config(), store.ready(null, reset = true))
        assertEquals(Config(), newStore().config.value)
    }

    @Test
    fun `ready with an invalid config throws and changes nothing`() {
        val store = newStore()

        assertInvalid { store.ready(JSONObject("""{"logger":{"logLevel":"loud"}}"""), reset = false) }

        assertEquals(Config(), store.config.value)
        assertFalse(store.runtime.value.didReady)
        assertFalse(newStore().runtime.value.didReady)
    }

    @Test
    fun `ready clamps the given config`() {
        val result = newStore().ready(JSONObject("""{"heartbeat":{"minInterval":30,"maxInterval":40}}"""), reset = true)

        assertEquals(HeartbeatConfig(minInterval = 60, maxInterval = 60), result.heartbeat)
    }

    // ---- merge / reset / update

    @Test
    fun `merge deep-merges, publishes immediately and persists`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))

        val result = store.merge(JSONObject("""{"geolocation":{"desiredAccuracy":"low"},"notification":{"title":null}}"""))

        val expected = CUSTOM.copy(
            geolocation = CUSTOM.geolocation.copy(desiredAccuracy = DesiredAccuracy.LOW),
            notification = CUSTOM.notification.copy(title = null),
        )
        assertEquals(expected, result)
        assertEquals(expected, store.config.value)
        assertEquals(expected, newStore().config.value)
    }

    @Test
    fun `merge clamps values`() {
        val store = newStore()

        val result = store.merge(JSONObject("""{"http":{"maxBatchSize":0},"activity":{"minimumActivityRecognitionConfidence":120}}"""))

        assertEquals(1, result.http.maxBatchSize)
        assertEquals(100, result.activity.minimumActivityRecognitionConfidence)
        assertEquals(result, newStore().config.value)
    }

    @Test
    fun `merge with an invalid value throws and changes nothing`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))
        val persisted = prefs.getString(SharedPrefsConfigStore.KEY_CONFIG, null)

        assertInvalid { store.merge(JSONObject("""{"geolocation":{"distanceFilter":5},"http":{"method":"DELETE"}}""")) }

        assertEquals(CUSTOM, store.config.value)
        assertEquals(persisted, prefs.getString(SharedPrefsConfigStore.KEY_CONFIG, null))
    }

    @Test
    fun `reset applies defaults plus the given config`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))

        val result = store.reset(JSONObject("""{"heartbeat":{"maxInterval":240}}"""))

        assertEquals(Config(heartbeat = HeartbeatConfig(maxInterval = 240)), result)
        assertEquals(result, newStore().config.value)
        assertEquals(Config(), store.reset(null))
        assertEquals(Config(), newStore().config.value)
    }

    @Test
    fun `update persists internal writes such as refreshed tokens`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))

        val result = store.update { c ->
            c.copy(http = c.http.copy(authorization = c.http.authorization!!.copy(accessToken = "access-2", expires = 5L)))
        }

        assertEquals("access-2", result.http.authorization!!.accessToken)
        assertEquals(result, store.config.value)
        assertEquals(result, newStore().config.value)
    }

    @Test
    fun `update clamps the transformed config`() {
        val store = newStore()

        val result = store.update { it.copy(heartbeat = HeartbeatConfig(minInterval = 1, maxInterval = 1)) }

        assertEquals(HeartbeatConfig(minInterval = 60, maxInterval = 60), result.heartbeat)
    }

    @Test
    fun `the persisted config is the TS Config shape without the tokens`() {
        newStore().merge(JSONObject(CUSTOM_JSON))

        val expected = JSONObject(CUSTOM_JSON)
        expected.getJSONObject("http").getJSONObject("authorization")
            .put("accessToken", JSONObject.NULL)
            .put("refreshToken", JSONObject.NULL)
            .put("refreshPayload", JSONObject(AuthorizationConfig().refreshPayload))
            .put("refreshHeaders", JSONObject())
        assertJsonEquals(expected.toString(), JSONObject(prefs.getString(SharedPrefsConfigStore.KEY_CONFIG, null)!!))
        assertEquals(AuthorizationSecrets.of(CUSTOM.http.authorization!!), secrets.read())
        assertTrue(prefs.getBoolean(SharedPrefsConfigStore.KEY_SECRETS, false))
    }

    // ---- runtime

    @Test
    fun `updateRuntime publishes immediately and persists every field`() {
        val store = newStore()

        val result = store.updateRuntime { RUNTIME }

        assertEquals(RUNTIME, result)
        assertEquals(RUNTIME, store.runtime.value)
        assertEquals(RUNTIME, newStore().runtime.value)
    }

    @Test
    fun `updateRuntime clears nullable fields`() {
        val store = newStore()
        store.updateRuntime { RUNTIME }

        store.updateRuntime { it.copy(lastLocation = null, providerState = null, lastRecordAt = null, lastHeartbeatAt = null) }

        val reloaded = newStore().runtime.value
        assertNull(reloaded.lastLocation)
        assertNull(reloaded.providerState)
        assertNull(reloaded.lastRecordAt)
        assertNull(reloaded.lastHeartbeatAt)
        assertEquals(RUNTIME.lastRecordElapsed, reloaded.lastRecordElapsed)
    }

    @Test
    fun `writes that change nothing do not touch the prefs file`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))
        store.updateRuntime { RUNTIME }
        val changes = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changes += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        store.updateRuntime { it.copy() }
        store.merge(JSONObject())
        store.update { it }
        store.updateRuntime { it.copy(odometer = 1.0) }
        shadowOf(Looper.getMainLooper()).idle()

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        assertEquals(listOf<String?>(SharedPrefsConfigStore.KEY_RUNTIME), changes)
    }

    @Test
    fun `an invalid persisted lastLocation is dropped`() {
        val runtime = JSONObject(
            """{"enabled":true,"odometer":3.5,"lastLocation":{"longitude":1},"trackingMode":"bogus","didReady":true}""",
        )
        prefs.edit().putString(SharedPrefsConfigStore.KEY_RUNTIME, runtime.toString()).commit()

        val loaded = newStore().runtime.value

        assertEquals(RuntimeState(enabled = true, odometer = 3.5, didReady = true), loaded)
    }

    // ---- isolation and threads

    @Test
    fun `other units' keys in the shared prefs file are left alone`() {
        prefs.edit().putLong("hb_last_fire", 7L).putBoolean("perm_asked", true).commit()
        val store = newStore()

        store.ready(JSONObject(CUSTOM_JSON), reset = true)
        store.merge(JSONObject("""{"app":null}"""))
        store.reset(null)
        store.updateRuntime { RUNTIME }

        assertEquals(7L, prefs.getLong("hb_last_fire", 0L))
        assertTrue(prefs.getBoolean("perm_asked", false))
        assertTrue(prefs.all.keys.all { it.startsWith("cfg_") || it.startsWith("hb_") || it.startsWith("perm_") })
    }

    @Test
    fun `concurrent updates are not lost`() {
        val store = newStore()
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    store.updateRuntime { it.copy(odometer = it.odometer + 1.0) }
                    if (i % 20 == 0) store.update { c -> c.copy(logger = c.logger.copy(logMaxDays = c.logger.logMaxDays + 1)) }
                    if (t == 0 && i % 50 == 0) store.merge(JSONObject("""{"app":{"startOnBoot":true}}"""))
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        val expectedOdometer = (threads * perThread).toDouble()
        assertEquals(expectedOdometer, store.runtime.value.odometer, 0.0)
        assertEquals(3 + threads * (perThread / 20), store.config.value.logger.logMaxDays)
        assertTrue(store.config.value.app.startOnBoot)
        val reloaded = newStore()
        assertEquals(store.runtime.value, reloaded.runtime.value)
        assertEquals(store.config.value, reloaded.config.value)
    }

    // ---- tokens (recommendation 8) and restore detection (recommendation 7)

    @Test
    fun `plain-text tokens written by an older version move to the secret store on the first load`() {
        prefs.edit().putString(SharedPrefsConfigStore.KEY_CONFIG, CUSTOM_JSON).commit()

        val store = newStore()

        assertEquals(CUSTOM, store.config.value)
        assertEquals(AuthorizationSecrets.of(CUSTOM.http.authorization!!), secrets.read())
        val persisted = JSONObject(prefs.getString(SharedPrefsConfigStore.KEY_CONFIG, null)!!)
            .getJSONObject("http").getJSONObject("authorization")
        assertTrue(persisted.isNull("accessToken"))
        assertTrue(persisted.isNull("refreshToken"))
        assertFalse(store.secretsUnavailable)
        assertEquals(CUSTOM, newStore().config.value)
    }

    @Test
    fun `tokens that cannot be decrypted read as unset until the app sets new ones`() {
        newStore().merge(JSONObject(CUSTOM_JSON))
        val broken = object : SecretStore by secrets {
            override fun read(): AuthorizationSecrets? = throw java.security.GeneralSecurityException("key gone")
        }

        val store = newStore(broken)

        val auth = store.config.value.http.authorization!!
        assertNull(auth.accessToken)
        assertNull(auth.refreshToken)
        assertEquals(CUSTOM.http.url, store.config.value.http.url)
        assertTrue(store.secretsUnavailable)

        store.merge(JSONObject("""{"http":{"authorization":{"accessToken":"new-access"}}}"""))
        assertFalse(store.secretsUnavailable)
        assertEquals("new-access", secrets.read()!!.accessToken)
    }

    @Test
    fun `a missing secrets file after tokens were stored marks the tokens unavailable`() {
        newStore().merge(JSONObject(CUSTOM_JSON))
        secrets.clear()

        val store = newStore()

        assertNull(store.config.value.http.authorization!!.accessToken)
        assertTrue(store.secretsUnavailable)
    }

    @Test
    fun `tokens that cannot be encrypted stay in memory and the old ones are removed`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))
        val failing = object : SecretStore by secrets {
            override fun write(secrets: AuthorizationSecrets) = throw java.security.GeneralSecurityException("no key")
        }
        val second = newStore(failing)

        second.merge(JSONObject("""{"http":{"authorization":{"accessToken":"other-user"}}}"""))

        assertEquals("other-user", second.config.value.http.authorization!!.accessToken)
        assertNull(secrets.read())
        assertFalse(prefs.getBoolean(SharedPrefsConfigStore.KEY_SECRETS, true))
        assertTrue(log.lines.any { it.level == LogLevel.ERROR && it.message.contains("cannot store the authorization tokens") })
    }

    @Test
    fun `removing the authorization deletes the stored tokens`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))

        store.merge(JSONObject("""{"http":{"authorization":null}}"""))

        assertNull(secrets.read())
        assertNull(newStore().config.value.http.authorization)
    }

    @Test
    fun `a prefs file restored from another installation never turns tracking on and drops the tokens`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))
        store.updateRuntime { RUNTIME.copy(enabled = true, isMoving = true) }
        val installed = prefs.getLong(SharedPrefsConfigStore.KEY_FIRST_INSTALL, -1L)
        assertTrue(installed >= 0)
        // As written by the other installation (Auto Backup restore or device-to-device transfer).
        prefs.edit().putLong(SharedPrefsConfigStore.KEY_FIRST_INSTALL, installed + 1).commit()

        val restored = newStore()

        assertFalse(restored.runtime.value.enabled)
        assertFalse(restored.runtime.value.isMoving)
        assertNull(restored.config.value.http.authorization!!.accessToken)
        assertNull(restored.config.value.http.authorization!!.refreshToken)
        assertEquals(CUSTOM.http.url, restored.config.value.http.url)
        assertNull(secrets.read())
        assertEquals(installed, prefs.getLong(SharedPrefsConfigStore.KEY_FIRST_INSTALL, -1L))
        assertFalse(newStore().runtime.value.enabled)
    }

    @Test
    fun `a file without a stored install time is not a restore`() {
        val store = newStore()
        store.merge(JSONObject(CUSTOM_JSON))
        store.updateRuntime { RUNTIME.copy(enabled = true) }
        prefs.edit().remove(SharedPrefsConfigStore.KEY_FIRST_INSTALL).commit()

        val reloaded = newStore()

        assertTrue(reloaded.runtime.value.enabled)
        assertEquals(CUSTOM, reloaded.config.value)
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected INVALID_ARGUMENT")
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
    }
}
