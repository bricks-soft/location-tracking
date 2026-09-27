package com.brickssoft.locationtracking.core

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.api.NativeListeners
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.SharedPrefsConfigStore
import com.brickssoft.locationtracking.data.GeofenceStore
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.data.SqliteGeofenceStore
import com.brickssoft.locationtracking.data.SqliteLocationStore
import com.brickssoft.locationtracking.data.TrackingDatabase
import com.brickssoft.locationtracking.device.DefaultDeviceInfoProvider
import com.brickssoft.locationtracking.device.DefaultDeviceMonitor
import com.brickssoft.locationtracking.device.DeviceInfoProvider
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.engine.DefaultTrackingEngine
import com.brickssoft.locationtracking.engine.TrackingEngine
import com.brickssoft.locationtracking.geofence.DefaultGeofenceManager
import com.brickssoft.locationtracking.geofence.GeofenceManager
import com.brickssoft.locationtracking.heartbeat.DefaultHeartbeatScheduler
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.http.OkHttpSyncer
import com.brickssoft.locationtracking.logging.FileLogger
import com.brickssoft.locationtracking.logging.LogStore
import com.brickssoft.locationtracking.permission.DefaultPermissionManager
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.position.DefaultPositionService
import com.brickssoft.locationtracking.position.PositionService
import com.brickssoft.locationtracking.processing.DefaultLocationProcessor
import com.brickssoft.locationtracking.processing.DefaultOdometer
import com.brickssoft.locationtracking.processing.DefaultRecordFactory
import com.brickssoft.locationtracking.processing.LocationProcessor
import com.brickssoft.locationtracking.processing.Odometer
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.DefaultProviderFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.provider.StationaryRegionSink
import com.brickssoft.locationtracking.record.DefaultRecordSink
import com.brickssoft.locationtracking.record.RecordSink
import com.brickssoft.locationtracking.service.DefaultServiceController
import com.brickssoft.locationtracking.service.ServiceController
import com.brickssoft.locationtracking.settings.DefaultDeviceSettings
import com.brickssoft.locationtracking.settings.DeviceSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Lazy service locator: the single place where every component is constructed. It fixes each unit's constructor
 * signature. Dependency cycles are broken with [Lazy] parameters, which constructors must not touch.
 *
 * Obtain it with [Components.get] from any context (plugin, service, receivers).
 */
class Components private constructor(context: Context) {
    /** The application context. */
    val context: Context = context.applicationContext ?: context

    val clock: Clock = SystemClockImpl(this.context)
    val dispatchers: AppDispatchers = AppDispatchers.DEFAULT
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + dispatchers.engine + CoroutineExceptionHandler { _, t -> Logger.e("LT", "uncaught", t) },
    )
    val events: EventBus = SimpleEventBus()

    /** Round 2: every queued record (sink and insertLocation) for the companion native API (unit 5). */
    val recordHooks: RecordHooks = RecordHooks()
    val http: OkHttpClient by lazy { OkHttpClient() }

    // U17
    val logStore: LogStore by lazy { FileLogger(this.context, clock, dispatchers, lazy { http }) }

    // U3
    val configStore: ConfigStore by lazy { SharedPrefsConfigStore(this.context, clock) }

    // U15
    val permissions: PermissionManager by lazy { DefaultPermissionManager(this.context) }
    val deviceSettings: DeviceSettings by lazy { DefaultDeviceSettings(this.context) }

    // U6
    val providers: ProviderFactory by lazy { DefaultProviderFactory(this.context, configStore) }

    // U14
    val device: DeviceMonitor by lazy {
        DefaultDeviceMonitor(
            this.context, configStore, permissions, lazy { providers }, events, clock,
            lazy { recordFactory }, lazy { recordSink }, scope,
        )
    }
    val deviceInfo: DeviceInfoProvider by lazy { DefaultDeviceInfoProvider(this.context, lazy { providers }) }

    // U10
    private val database by lazy { TrackingDatabase(this.context) }
    val locationStore: LocationStore by lazy { SqliteLocationStore(database, configStore, clock, dispatchers) }
    val geofenceStore: GeofenceStore by lazy { SqliteGeofenceStore(database, dispatchers) }

    // U9
    val processor: LocationProcessor by lazy { DefaultLocationProcessor(configStore) }
    val odometer: Odometer by lazy { DefaultOdometer(configStore) }
    val recordFactory: RecordFactory by lazy { DefaultRecordFactory(configStore, device, providers, clock) }

    // U11
    val syncer: HttpSyncer by lazy {
        OkHttpSyncer(configStore, locationStore, device, events, clock, http, dispatchers, scope)
    }

    // U12
    val heartbeat: HeartbeatScheduler by lazy {
        DefaultHeartbeatScheduler(
            this.context, configStore, device, providers, locationStore, recordFactory, lazy { recordSink }, clock, scope,
        )
    }

    // SCAFFOLD
    val recordSink: RecordSink by lazy {
        DefaultRecordSink(locationStore, configStore, heartbeat, syncer, events, recordHooks)
    }

    // U13 (round 2: STATIONARY_REGION_ID transitions go to the engine)
    val geofences: GeofenceManager by lazy {
        DefaultGeofenceManager(
            geofenceStore, providers, configStore, recordFactory, recordSink, events, clock, scope,
            lazy { stationarySink },
        )
    }

    // U16
    val positions: PositionService by lazy {
        DefaultPositionService(providers, configStore, permissions, device, recordFactory, recordSink, clock, scope)
    }

    // U8
    val serviceController: ServiceController by lazy { DefaultServiceController(this.context, configStore, events, clock) }

    // U7
    val engine: TrackingEngine by lazy {
        DefaultTrackingEngine(
            configStore, providers, processor, odometer, recordFactory, recordSink,
            heartbeat, geofences, serviceController, device, syncer, permissions, events, clock, scope,
        )
    }

    /** Round 2: the engine receives the transitions of its stationary region (unit 2). */
    val stationarySink: StationaryRegionSink get() = engine

    /**
     * Runs once, right after construction: installs the file logger, keeps its level in sync with config and (round 2)
     * installs the native companion listeners before any component can emit.
     */
    private fun bootstrap() {
        Logger.sink = logStore
        val store = configStore
        val initial = store.config.value.logger
        logStore.configure(initial.logLevel, initial.logMaxDays)
        scope.launch {
            store.config
                .map { it.logger }
                .distinctUntilChanged()
                .collect { logStore.configure(it.logLevel, it.logMaxDays) }
        }
        try {
            NativeListeners.install(context, this)
        } catch (e: Exception) {
            Logger.e(Constants.TAG, "failed to install native listeners", e)
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile
        private var instance: Components? = null

        /** Thread-safe; always uses the application context. */
        fun get(context: Context): Components {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: Components(context).also {
                    instance = it
                    it.bootstrap()
                }
            }
        }

        /** Test hook: cancels the scope, detaches the log sink and forgets the instance. */
        @VisibleForTesting
        fun reset() {
            synchronized(this) {
                instance?.scope?.cancel()
                instance = null
                Logger.sink = null
            }
        }
    }
}
