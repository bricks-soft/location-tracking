package com.brickssoft.locationtracking.service

import android.app.Notification
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.FakeTrackingEngine
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fakes behind [ServiceDeps.override] for the service and receiver tests. Coroutines run on [scheduler]; call
 * [runPending] to execute them. Call [install] in `@Before` and [uninstall] in `@After`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ServiceTestEnv(
    config: Config = Config(),
    runtime: RuntimeState = RuntimeState(),
) {
    val scheduler = TestCoroutineScheduler()
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    val configStore = FakeConfigStore(config, runtime)
    val events = RecordingEventBus()
    val engine = FakeTrackingEngine(configStore)
    val logs = FakeLogStore()

    /** How many times the engine was resolved (it must stay lazy). */
    val engineResolutions = AtomicInteger()

    /** How many times [ServiceDeps.from] was called. */
    val depsLookups = AtomicInteger()

    fun install() {
        Logger.sink = logs
        BootReceiver.resetBootHandled()
        ServiceDeps.override = {
            depsLookups.incrementAndGet()
            ServiceDeps(configStore, events, scope, lazy { engineResolutions.incrementAndGet(); engine })
        }
    }

    fun uninstall() {
        ServiceDeps.override = null
        BootReceiver.resetBootHandled()
        scope.cancel()
        Logger.sink = null
    }

    fun runPending() = scheduler.advanceUntilIdle()

    fun logged(level: LogLevel, fragment: String): Boolean =
        logs.lines.any { it.level == level && it.message.contains(fragment) }
}

internal val Notification.title: String? get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

internal val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
