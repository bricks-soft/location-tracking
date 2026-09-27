package com.brickssoft.locationtracking.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.testing.FakeClock
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
 * Fakes behind [ServiceDeps.override] for the service and receiver tests. Coroutines (including the service's
 * background steps, through [LocationTrackingService.dispatcherOverride]) run on [scheduler]; call [runPending] to
 * execute them. Call [install] in `@Before` and [uninstall] in `@After`; both also forget the process-wide
 * [ServiceCommands] state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ServiceTestEnv(
    config: Config = Config(),
    runtime: RuntimeState = RuntimeState(),
) {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = StandardTestDispatcher(scheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val clock = FakeClock(bootCount = BOOT_COUNT)
    val configStore = FakeConfigStore(config, runtime)
    val events = RecordingEventBus()
    val engine = FakeTrackingEngine(configStore)
    val logs = FakeLogStore()

    /** How many times the engine was resolved (it must stay lazy). */
    val engineResolutions = AtomicInteger()

    /** How many times [ServiceDeps.from] was called. */
    val depsLookups = AtomicInteger()

    /** Called with every [ServiceDeps] lookup, before the fakes are returned. */
    @Volatile
    var onDepsLookup: () -> Unit = {}

    fun install() {
        Logger.sink = logs
        BootReceiver.resetBootHandled()
        ServiceCommands.reset()
        LocationTrackingService.dispatcherOverride = dispatcher
        ServiceDeps.override = {
            depsLookups.incrementAndGet()
            onDepsLookup()
            deps()
        }
    }

    /** The fakes as [ServiceDeps]. */
    fun deps(): ServiceDeps =
        ServiceDeps(configStore, events, scope, lazy { engineResolutions.incrementAndGet(); engine }, clock)

    fun uninstall() {
        ServiceDeps.override = null
        LocationTrackingService.dispatcherOverride = null
        ServiceCommands.reset()
        BootReceiver.resetBootHandled()
        scope.cancel()
        Logger.sink = null
    }

    fun runPending() = scheduler.advanceUntilIdle()

    fun logged(level: LogLevel, fragment: String): Boolean =
        logs.lines.any { it.level == level && it.message.contains(fragment) }

    companion object {
        const val BOOT_COUNT = 7
    }
}

internal val Notification.title: String? get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

internal val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

/** A start command as [DefaultServiceController] sends it from this process, recorded in [ServiceCommands] as sent. */
internal fun ownStart(context: Context): Intent {
    val seq = ServiceCommands.nextSeq()
    ServiceCommands.startSent(seq)
    return ServiceCommands.stamp(Intent(context, LocationTrackingService::class.java), seq, foregroundRequired = true)
}

/** A stop command as [DefaultServiceController] sends it from this process, recorded in [ServiceCommands]. */
internal fun ownStop(context: Context, foregroundRequired: Boolean = false): Intent {
    val seq = ServiceCommands.nextSeq()
    ServiceCommands.stopRequested(seq)
    val intent = Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP)
    return ServiceCommands.stamp(intent, seq, foregroundRequired)
}

/** A start command sent by an earlier process that died before `onStartCommand` ran. */
internal fun earlierProcessStart(context: Context): Intent = Intent(context, LocationTrackingService::class.java)
    .putExtra(ServiceCommands.EXTRA_SEQ, 3L)
    .putExtra(ServiceCommands.EXTRA_ORIGIN, "4242-earlier")
    .putExtra(ServiceCommands.EXTRA_FOREGROUND_REQUIRED, true)
