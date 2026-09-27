package com.brickssoft.locationtracking.heartbeat

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.DefaultRecordSink
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeHttpSyncer
import com.brickssoft.locationtracking.testing.FakeLocationBackend
import com.brickssoft.locationtracking.testing.FakeLocationStore
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.CoroutineScope
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * A [DefaultHeartbeatScheduler] wired to the real scaffold [DefaultRecordSink] over fakes, plus the Robolectric
 * AlarmManager. Times in tests are offsets (ms) from [t0Elapsed], the elapsed time at construction.
 */
internal class HeartbeatHarness(
    scope: CoroutineScope,
    exact: Boolean = false,
    idle: Boolean = false,
    config: Config = Config(),
    runtime: RuntimeState = RuntimeState(enabled = true),
    val syncer: HttpSyncer = FakeHttpSyncer(),
    val clock: FakeClock = FakeClock(),
    alarmsOverride: ((HeartbeatAlarms) -> HeartbeatAlarms)? = null,
    providersOverride: ((FakeProviderFactory) -> ProviderFactory)? = null,
    factoryOverride: ((FakeRecordFactory) -> RecordFactory)? = null,
    deviceOverride: ((FakeDeviceMonitor) -> DeviceMonitor)? = null,
) {
    val app: Application = ApplicationProvider.getApplicationContext<Application>().also {
        // Below API 33, ContextCompat's RECEIVER_NOT_EXPORTED needs this permission, which androidx.core's
        // manifest declares and grants in a real app (the library's unit-test manifest lacks it).
        shadowOf(it).grantPermissions(it.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    val configStore = FakeConfigStore(config, runtime)
    val device = FakeDeviceMonitor().apply {
        exactAlarms = exact
        deviceIdleMode = idle
    }
    val lastKnown: TrackedLocation = Fixtures.location(latitude = 24.7136, longitude = 46.6753, time = clock.now() - 60_000L)
    val backend = FakeLocationBackend().apply { lastLocation = lastKnown }
    val providers = FakeProviderFactory(locationBackend = backend)
    val store = FakeLocationStore()
    val events = RecordingEventBus()
    val factory = FakeRecordFactory(clock, configStore)
    val alarms: HeartbeatAlarms = SystemHeartbeatAlarms(app).let { alarmsOverride?.invoke(it) ?: it }

    private lateinit var sinkRef: DefaultRecordSink
    val scheduler: HeartbeatScheduler = DefaultHeartbeatScheduler(
        app,
        configStore,
        deviceOverride?.invoke(device) ?: device,
        providersOverride?.invoke(providers) ?: providers,
        store,
        factoryOverride?.invoke(factory) ?: factory,
        lazy { sinkRef },
        clock,
        scope,
        alarms,
    )
    val sink: DefaultRecordSink = DefaultRecordSink(store, configStore, scheduler, syncer, events).also { sinkRef = it }

    val t0Elapsed: Long = clock.elapsedRealtime()
    val t0Wall: Long = clock.now()

    val alarmManager: AlarmManager = app.getSystemService(AlarmManager::class.java)
    val shadowAlarms: ShadowAlarmManager get() = shadowOf(alarmManager)

    fun scheduled(): List<ShadowAlarmManager.ScheduledAlarm> = shadowAlarms.scheduledAlarms

    fun listenerAlarm(): ShadowAlarmManager.ScheduledAlarm? = scheduled().singleOrNull { listenerOf(it) != null }

    fun intentAlarm(): ShadowAlarmManager.ScheduledAlarm? = scheduled().singleOrNull { operationOf(it) != null }

    // ScheduledAlarm exposes the listener and the PendingIntent only as deprecated fields.
    @Suppress("DEPRECATION")
    fun listenerOf(alarm: ShadowAlarmManager.ScheduledAlarm): AlarmManager.OnAlarmListener? = alarm.onAlarmListener

    @Suppress("DEPRECATION")
    fun operationOf(alarm: ShadowAlarmManager.ScheduledAlarm): PendingIntent? = alarm.operation

    fun triggerOf(alarm: ShadowAlarmManager.ScheduledAlarm): HeartbeatTrigger =
        HeartbeatIntents.triggerOf(shadowOf(operationOf(alarm)).savedIntent)

    /** Delivers a listener alarm the way AlarmManager does (on the caller's thread; the work is launched). */
    fun fireListener(alarm: ShadowAlarmManager.ScheduledAlarm = listenerAlarm()!!) {
        listenerOf(alarm)!!.onAlarm()
    }

    /** Moves the clock to [t0Elapsed] + [offsetMs]. */
    fun at(offsetMs: Long) {
        clock.advance(t0Elapsed + offsetMs - clock.elapsedRealtime())
    }

    /** Elapsed time [offsetMs] after t0. */
    fun t(offsetMs: Long): Long = t0Elapsed + offsetMs

    /** Delivers a PendingIntent alarm the way [HeartbeatAlarmReceiver] does (trigger from the intent extra). */
    suspend fun fireIntentAlarm(alarm: ShadowAlarmManager.ScheduledAlarm = intentAlarm()!!) {
        scheduler.onAlarm(triggerOf(alarm))
    }

    /** The process dies: its listener alarms die with it; PendingIntent alarms survive. */
    fun killProcess() {
        scheduled().mapNotNull { listenerOf(it) }.forEach { alarmManager.cancel(it) }
    }

    /** The device reboots: every alarm is gone, elapsed time restarts and the boot count increments. */
    fun reboot(elapsedAfterBoot: Long = 30_000L) {
        killProcess()
        scheduled().mapNotNull { operationOf(it) }.forEach { alarmManager.cancel(it) }
        clock.reboot(elapsedAfterBoot)
    }

    /** Submits a record of [event] created now, as a producer would. */
    suspend fun submit(event: RecordEvent = RecordEvent.LOCATION, location: TrackedLocation? = Fixtures.location()): Record =
        sink.submit(factory.create(event, location))

    fun heartbeats(): List<Record> = store.all.filter { it.event == RecordEvent.HEARTBEAT }

    companion object {
        /** Default `heartbeat.minInterval`, ms. */
        const val MIN_MS = 180_000L

        /** Allow-while-idle backup spacing while idle, ms. */
        const val IDLE_SPACING_MS = 9 * 60_000L
    }
}
