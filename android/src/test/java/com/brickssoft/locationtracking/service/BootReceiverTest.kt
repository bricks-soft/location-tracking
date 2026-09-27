package com.brickssoft.locationtracking.service

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.AppConfig
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.engine.TrackingEngine
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeTrackingEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val env = ServiceTestEnv(
        config = Config(app = AppConfig(startOnBoot = true)),
        runtime = RuntimeState(enabled = true),
    )

    @Before
    fun setUp() {
        env.install()
    }

    @After
    fun tearDown() {
        env.uninstall()
        Logger.sink = null
    }

    @Test
    fun `actions map to restore reasons`() {
        assertEquals("boot", BootReceiver.reasonFor(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("boot", BootReceiver.reasonFor("android.intent.action.QUICKBOOT_POWERON"))
        assertEquals("boot", BootReceiver.reasonFor("com.htc.intent.action.QUICKBOOT_POWERON"))
        assertEquals("package_replaced", BootReceiver.reasonFor(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(BootReceiver.reasonFor(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(BootReceiver.reasonFor(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull(BootReceiver.reasonFor(null))
    }

    @Test
    fun `decision covers every enabled x startOnBoot x action combination`() = runTest {
        val actions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
        for (action in actions) {
            for (enabled in listOf(true, false)) {
                for (startOnBoot in listOf(true, false)) {
                    val case = "$action enabled=$enabled startOnBoot=$startOnBoot"
                    val config = Config(app = AppConfig(startOnBoot = startOnBoot))
                    val store = FakeConfigStore(config, RuntimeState(enabled))
                    val engine = FakeTrackingEngine(store)
                    var resolved = 0
                    val reason = BootReceiver.reasonFor(action)!!

                    val outcome = BootReceiver.handle(reason, store) { resolved++; engine }

                    when {
                        !enabled -> {
                            assertEquals(case, BootReceiver.Outcome.IGNORED, outcome)
                            assertTrue(case, store.calls.isEmpty())
                            assertEquals(case, 0, resolved)
                        }
                        !startOnBoot -> {
                            assertEquals(case, BootReceiver.Outcome.DISABLED, outcome)
                            assertFalse(case, store.runtime.value.enabled)
                            // The engine records tracking_stop so the server learns why heartbeats stopped.
                            val stopReason = if (reason == "boot") "reboot" else "package_replaced"
                            assertEquals(case, listOf(stopReason), engine.endReasons)
                            assertTrue(case, engine.restoreReasons.isEmpty())
                        }
                        else -> {
                            assertEquals(case, BootReceiver.Outcome.RESTORED, outcome)
                            assertEquals(case, listOf(reason), engine.restoreReasons)
                            assertTrue(case, store.runtime.value.enabled)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `enabled without startOnBoot is logged`() = runTest {
        val store = FakeConfigStore(Config(), RuntimeState(enabled = true))

        BootReceiver.handle("boot", store) { FakeTrackingEngine(store) }

        assertTrue(env.logged(LogLevel.INFO, "startOnBoot is false"))
    }

    @Test
    fun `a session already running in this process is left alone`() = runTest {
        val store = FakeConfigStore(Config(), RuntimeState(enabled = true))
        // An engine with a live session ignores endWithoutRestore (the default no-op models that here).
        val engine = object : TrackingEngine by FakeTrackingEngine(store) {
            override suspend fun endWithoutRestore(reason: String) = Unit
        }

        BootReceiver.handle("package_replaced", store) { engine }

        assertTrue(store.runtime.value.enabled)
    }

    @Test
    fun `enabled flag is cleared even if the engine fails to record the stop`() = runTest {
        val store = FakeConfigStore(Config(), RuntimeState(enabled = true))
        val engine = FakeTrackingEngine(store).apply {
            failWith = TrackingException(ErrorCode.IO_ERROR, "disk full")
        }

        val result = runCatching { BootReceiver.handle("boot", store) { engine } }

        assertTrue(result.isFailure)
        assertFalse(store.runtime.value.enabled)
    }

    @Test
    fun `boot broadcast restores tracking through the engine`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.runPending()

        assertEquals(listOf("boot"), env.engine.restoreReasons)
    }

    @Test
    fun `package replaced restores with its own reason`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        env.runPending()

        assertEquals(listOf("package_replaced"), env.engine.restoreReasons)
    }

    @Test
    fun `broadcast sent by the system reaches the manifest receiver`() {
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(Looper.getMainLooper()).idle()
        env.runPending()

        assertEquals(listOf("boot"), env.engine.restoreReasons)
    }

    @Test
    fun `duplicate boot broadcasts restore once per process`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(app, Intent("android.intent.action.QUICKBOOT_POWERON"))
        env.runPending()

        assertEquals(listOf("boot"), env.engine.restoreReasons)
    }

    @Test
    fun `unrelated actions are ignored without loading components`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED))
        env.runPending()

        assertEquals(0, env.depsLookups.get())
        assertTrue(env.engine.calls.isEmpty())
    }

    @Test
    fun `a slow restore releases the broadcast after the budget without being cancelled`() {
        val gate = CompletableDeferred<Unit>()
        var restoreFinished = false
        val slowEngine = object : TrackingEngine by env.engine {
            override suspend fun restore(reason: String) {
                env.engine.restore(reason)
                gate.await()
                restoreFinished = true
            }
        }
        ServiceDeps.override = { ServiceDeps(env.configStore, env.events, env.scope, lazyOf(slowEngine), env.clock) }

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.scheduler.advanceTimeBy(BootReceiver.FINISH_BUDGET_MS - 1)
        env.scheduler.runCurrent()
        assertFalse(env.logged(LogLevel.WARN, "releasing the broadcast"))
        env.scheduler.advanceTimeBy(2)
        env.scheduler.runCurrent()
        assertTrue(env.logged(LogLevel.WARN, "releasing the broadcast"))

        gate.complete(Unit)
        env.runPending()
        assertTrue(restoreFinished)
        assertEquals(listOf("boot"), env.engine.restoreReasons)
    }

    @Test
    fun `a fast restore does not trip the watchdog`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.scheduler.advanceTimeBy(BootReceiver.FINISH_BUDGET_MS * 2)
        env.runPending()

        assertFalse(env.logged(LogLevel.WARN, "releasing the broadcast"))
    }

    @Test
    fun `a boot broadcast that cannot load components does not block the next one`() {
        var fail = true
        ServiceDeps.override = {
            check(!fail) { "database locked" }
            ServiceDeps(env.configStore, env.events, env.scope, lazyOf(env.engine), env.clock)
        }

        BootReceiver().onReceive(app, Intent("android.intent.action.QUICKBOOT_POWERON"))
        fail = false
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.runPending()

        assertTrue(env.logged(LogLevel.ERROR, "components unavailable"))
        assertEquals(listOf("boot"), env.engine.restoreReasons)
    }

    @Test
    fun `restore failures are logged`() {
        env.engine.failWith = TrackingException(ErrorCode.PERMISSION_DENIED, "no background permission")

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.runPending()

        assertEquals(listOf("restore"), env.engine.calls)
        assertTrue(env.logged(LogLevel.ERROR, "restoring tracking failed"))
    }

    // ---- boot count gate (P-L10)

    private val bootCounts = BootCountStore(app)
    private val quickboot = Intent("android.intent.action.QUICKBOOT_POWERON")

    @Test
    fun `boot count gate decisions`() {
        assertNull("first boot seen", BootReceiver.ignoredBootReason(7, lastHandled = null, lastServiceStart = null))
        assertNull("a new boot", BootReceiver.ignoredBootReason(7, lastHandled = 6, lastServiceStart = 6))
        assertNotNull("same boot handled", BootReceiver.ignoredBootReason(7, lastHandled = 7, lastServiceStart = 6))
        assertNotNull("started in this boot", BootReceiver.ignoredBootReason(7, lastHandled = 6, lastServiceStart = 7))
        assertNotNull("started in this boot, none handled", BootReceiver.ignoredBootReason(7, null, 7))
        assertNull("unreadable boot count", BootReceiver.ignoredBootReason(-1, lastHandled = -1, lastServiceStart = -1))
        assertNull("unreadable boot count", BootReceiver.ignoredBootReason(-1, lastHandled = 7, lastServiceStart = 7))
    }

    @Test
    fun `a new boot is handled and its boot count is stored`() {
        bootCounts.setLastHandled(ServiceTestEnv.BOOT_COUNT - 1)

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.runPending()

        assertEquals(listOf("boot"), env.engine.restoreReasons)
        assertEquals(ServiceTestEnv.BOOT_COUNT, BootCountStore(app).lastHandled())
    }

    @Test
    fun `a fake QUICKBOOT_POWERON in a boot that was already handled is ignored`() {
        bootCounts.setLastHandled(ServiceTestEnv.BOOT_COUNT)

        BootReceiver().onReceive(app, quickboot)
        env.runPending()

        assertTrue(env.engine.calls.isEmpty())
        assertTrue(env.logged(LogLevel.INFO, "already handled"))
    }

    @Test
    fun `a fake QUICKBOOT_POWERON after tracking started in this boot is ignored`() {
        // Installed and started without a reboot since: no boot broadcast was handled yet.
        bootCounts.setServiceStarted(ServiceTestEnv.BOOT_COUNT)

        BootReceiver().onReceive(app, quickboot)
        env.runPending()

        assertTrue("no duplicate tracking_start", env.engine.calls.isEmpty())
        assertNull(bootCounts.lastHandled())
        assertTrue(env.logged(LogLevel.INFO, "already started during boot"))
    }

    @Test
    fun `the controller's start marks the boot, so a later fake QUICKBOOT_POWERON is ignored`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(DefaultServiceController(app, env.configStore, env.events, env.clock).start())

        BootReceiver().onReceive(app, quickboot)
        env.runPending()

        assertTrue(env.engine.calls.isEmpty())
    }

    @Test
    fun `a record persisted in this boot without a service start does not block the boot restore`() {
        // For example a providerchange record after the app was opened before BOOT_COMPLETED arrived.
        env.configStore.runtimeFlow.value = RuntimeState(enabled = true, lastRecordBootCount = ServiceTestEnv.BOOT_COUNT)
        bootCounts.setServiceStarted(ServiceTestEnv.BOOT_COUNT - 1)

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.runPending()

        assertEquals(listOf("boot"), env.engine.restoreReasons)
    }

    @Test
    fun `a fake QUICKBOOT_POWERON with startOnBoot false does not end tracking`() {
        env.configStore.configFlow.value = Config(app = AppConfig(startOnBoot = false))
        bootCounts.setServiceStarted(ServiceTestEnv.BOOT_COUNT)

        BootReceiver().onReceive(app, quickboot)
        env.runPending()

        assertTrue("no tracking_stop: reboot", env.engine.endReasons.isEmpty())
        assertTrue(env.configStore.runtime.value.enabled)
    }

    @Test
    fun `the gate holds in a new process of the same boot`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.runPending()
        BootReceiver.resetBootHandled() // a new process: the in-memory flag is gone, the stored boot count is not

        BootReceiver().onReceive(app, quickboot)
        env.runPending()

        assertEquals(listOf("boot"), env.engine.restoreReasons)
    }

    @Test
    fun `an unreadable boot count keeps the previous behavior`() {
        env.clock.bootCountValue = -1
        bootCounts.setServiceStarted(-1)

        BootReceiver().onReceive(app, quickboot)
        env.runPending()

        assertEquals(listOf("boot"), env.engine.restoreReasons)
        assertNull("-1 is never stored", bootCounts.lastHandled())
    }

    @Test
    fun `the boot count is stored when tracking is not enabled`() {
        env.configStore.runtimeFlow.value = RuntimeState(enabled = false)

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.runPending()

        assertEquals(ServiceTestEnv.BOOT_COUNT, bootCounts.lastHandled())
    }

    @Test
    fun `the boot count is stored when the restore fails`() {
        env.engine.failWith = TrackingException(ErrorCode.PERMISSION_DENIED, "no background permission")

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        env.runPending()

        assertEquals(ServiceTestEnv.BOOT_COUNT, bootCounts.lastHandled())
        assertTrue(env.logged(LogLevel.ERROR, "restoring tracking failed"))
    }

    @Test
    fun `package replaced is not gated and does not store a boot count`() {
        bootCounts.setLastHandled(ServiceTestEnv.BOOT_COUNT - 1)
        bootCounts.setServiceStarted(ServiceTestEnv.BOOT_COUNT)

        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        env.runPending()

        assertEquals(listOf("package_replaced"), env.engine.restoreReasons)
        assertEquals(ServiceTestEnv.BOOT_COUNT - 1, bootCounts.lastHandled())
    }
}
