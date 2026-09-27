package com.brickssoft.locationtracking.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default [DeviceMonitor].
 *
 * [start] registers (once) a receiver for location provider/mode changes, power-save and device-idle changes, and
 * a default-network callback, then schedules a provider check. Provider broadcasts schedule a debounced
 * [checkProviderState] on [scope]. Connectivity and power-save changes are emitted as
 * [TrackingEvent.ConnectivityChange] / [TrackingEvent.PowerSaveChange], deduplicated against the last published
 * value (seeded at registration, so registering alone emits nothing). Device-idle changes are only logged; the
 * heartbeat scheduler listens for them itself.
 *
 * [checkProviderState] diffs the current [ProviderState] against the persisted `runtime.providerState`. The first
 * observation is persisted silently. A later difference is emitted as [TrackingEvent.ProviderChange] and, while
 * tracking is enabled, submitted as a `providerchange` record with the last known location; the new state is
 * persisted after the record was handed to the sink, so a failed submit is retried by the next check.
 *
 * The constructor does not touch the lazy parameters or any system service.
 */
class DefaultDeviceMonitor(
    private val context: Context,
    private val configStore: ConfigStore,
    private val permissions: PermissionManager,
    private val providers: Lazy<ProviderFactory>,
    private val events: EventBus,
    @Suppress("unused") private val clock: Clock,
    private val recordFactory: Lazy<RecordFactory>,
    private val recordSink: Lazy<RecordSink>,
    private val scope: CoroutineScope,
) : DeviceMonitor {
    private val receiverRegistered = AtomicBoolean(false)
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val providerCheckPending = AtomicBoolean(false)
    private val providerMutex = Mutex()
    private val lastConnectivity = AtomicReference<Connectivity?>(null)
    private val lastPowerSave = AtomicReference<Boolean?>(null)

    private val locationManager: LocationManager? by lazy { systemService(LocationManager::class.java) }
    private val connectivityManager: ConnectivityManager? by lazy { systemService(ConnectivityManager::class.java) }
    private val powerManager: PowerManager? by lazy { systemService(PowerManager::class.java) }
    private val alarmManager: AlarmManager? by lazy { systemService(AlarmManager::class.java) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                LocationManager.PROVIDERS_CHANGED_ACTION,
                LocationManager.MODE_CHANGED_ACTION,
                -> scheduleProviderCheck(REASON_PROVIDERS_CHANGED)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> publishPowerSave(isPowerSaveMode())
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> Logger.d(TAG, "device idle mode: ${isDeviceIdleMode()}")
            }
        }
    }

    /** Tracks the default network. Callbacks of one registration arrive serially on one thread. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        @Volatile
        private var current: Network? = null

        /** Connectivity of [current] from its capabilities, before [isBlocked] is applied. */
        @Volatile
        private var fromCapabilities: Connectivity? = null

        /**
         * Whether this app's traffic is blocked (Data Saver, background restrictions, Doze); reported on API 29+.
         * Kept across network switches: API 29+ reports the new network's status right after its capabilities.
         */
        @Volatile
        private var isBlocked = false

        override fun onAvailable(network: Network) {
            if (network != current) fromCapabilities = null
            current = network
            // From API 26 onAvailable is always followed by onCapabilitiesChanged, and querying the capabilities
            // here is racy. Before that, the query is the only way to learn the new network's state.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                capabilitiesOf(network)?.let { update(DeviceStateMapping.connectivityOf(it)) }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            current = network
            update(DeviceStateMapping.connectivityOf(networkCapabilities))
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            val active = current
            if (active != null && active != network) return
            isBlocked = blocked
            fromCapabilities?.let { publishConnectivity(effective(it)) }
        }

        override fun onLost(network: Network) {
            val active = current
            // A default-network callback can report the old network as lost after the new one became available.
            if (active != null && active != network) return
            current = null
            fromCapabilities = null
            publishConnectivity(DeviceStateMapping.DISCONNECTED)
        }

        private fun update(value: Connectivity) {
            fromCapabilities = value
            publishConnectivity(effective(value))
        }

        private fun effective(value: Connectivity): Connectivity =
            if (isBlocked && value.connected) value.copy(connected = false) else value
    }

    override fun start() {
        if (receiverRegistered.compareAndSet(false, true)) {
            lastPowerSave.set(isPowerSaveMode())
            val filter = IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
            try {
                registerNotExported(receiver, filter)
                // Catches changes made while no receiver was listening (service start, cold start).
                scheduleProviderCheck(REASON_START)
            } catch (e: Exception) {
                receiverRegistered.set(false)
                Logger.e(TAG, "failed to register the device receiver", e)
            }
        }
        if (networkCallbackRegistered.compareAndSet(false, true)) {
            lastConnectivity.set(connectivity())
            val cm = connectivityManager
            try {
                checkNotNull(cm) { "no ConnectivityManager" }.registerDefaultNetworkCallback(networkCallback)
            } catch (e: Exception) {
                networkCallbackRegistered.set(false)
                Logger.e(TAG, "failed to register the network callback", e)
            }
        }
        Logger.d(TAG, "started (receiver=${receiverRegistered.get()}, network=${networkCallbackRegistered.get()})")
    }

    override suspend fun checkProviderState(reason: String) {
        providerMutex.withLock {
            try {
                val current = providerState()
                val runtime = configStore.runtime.value
                val previous = runtime.providerState
                if (current == previous) {
                    Logger.v(TAG, "provider state unchanged ($reason)")
                    return
                }
                if (previous == null) {
                    configStore.updateRuntime { it.copy(providerState = current) }
                    Logger.i(TAG, "provider state first observed ($reason): $current")
                    return
                }
                Logger.i(TAG, "providerchange ($reason): $previous -> $current")
                events.emit(TrackingEvent.ProviderChange(current))
                if (runtime.enabled) {
                    val record = recordFactory.value.create(
                        RecordEvent.PROVIDERCHANGE,
                        runtime.lastLocation,
                        provider = current,
                    )
                    recordSink.value.submit(record)
                }
                configStore.updateRuntime { it.copy(providerState = current) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The state stays unpersisted, so the next check reports the change again.
                Logger.e(TAG, "provider state check failed ($reason)", e)
            }
        }
    }

    override fun providerState(): ProviderState {
        val lm = locationManager
        val foreground = permissions.hasForegroundLocation()
        val background = foreground && permissions.hasBackgroundLocation()
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return ProviderState(
            enabled = lm != null && safely("isLocationEnabled", false) { LocationManagerCompat.isLocationEnabled(lm) },
            gps = isProviderEnabled(lm, LocationManager.GPS_PROVIDER),
            network = isProviderEnabled(lm, LocationManager.NETWORK_PROVIDER),
            permission = DeviceStateMapping.permissionLevel(foreground, background),
            accuracy = DeviceStateMapping.accuracyLevel(foreground, fine),
            backend = providers.value.kind,
        )
    }

    override fun connectivity(): Connectivity {
        val cm = connectivityManager ?: return DeviceStateMapping.DISCONNECTED
        return safely("connectivity", DeviceStateMapping.DISCONNECTED) {
            val network = cm.activeNetwork
            if (network == null) {
                DeviceStateMapping.DISCONNECTED
            } else {
                DeviceStateMapping.connectivityOf(capabilitiesOf(network))
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun battery(): BatterySnapshot = safely("battery", BatterySnapshot.UNKNOWN) {
        // A null receiver only reads the sticky broadcast and registers nothing, so no export flag is needed
        // (ACTION_BATTERY_CHANGED is a protected broadcast).
        DeviceStateMapping.batteryOf(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    override fun isPowerSaveMode(): Boolean = safely("isPowerSaveMode", false) { powerManager?.isPowerSaveMode == true }

    override fun isDeviceIdleMode(): Boolean =
        safely("isDeviceIdleMode", false) { powerManager?.isDeviceIdleMode == true }

    override fun isIgnoringBatteryOptimizations(): Boolean = safely("isIgnoringBatteryOptimizations", false) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    override fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return safely("canScheduleExactAlarms", false) { alarmManager?.canScheduleExactAlarms() == true }
    }

    /**
     * Runs one [checkProviderState] [PROVIDER_CHECK_DEBOUNCE_MS] after the first of a burst of triggers. One user
     * toggle can send several broadcasts (one per provider, plus MODE_CHANGED) with intermediate states in between;
     * the delay lets them settle so a single toggle yields a single providerchange. A trigger that arrives after
     * the check has begun schedules another one, so the final state is always observed.
     */
    private fun scheduleProviderCheck(reason: String) {
        if (!providerCheckPending.compareAndSet(false, true)) return
        val job = scope.launch {
            delay(PROVIDER_CHECK_DEBOUNCE_MS)
            providerCheckPending.set(false)
            checkProviderState(reason)
        }
        // If the scope is no longer active the body never runs; don't leave the flag stuck.
        job.invokeOnCompletion { cause -> if (cause != null) providerCheckPending.set(false) }
    }

    private fun publishConnectivity(value: Connectivity) {
        if (lastConnectivity.getAndSet(value) == value) return
        Logger.i(TAG, "connectivity: connected=${value.connected}, type=${value.type.wire}")
        events.emit(TrackingEvent.ConnectivityChange(value))
    }

    private fun publishPowerSave(value: Boolean) {
        if (lastPowerSave.getAndSet(value) == value) return
        Logger.i(TAG, "power save mode: $value")
        events.emit(TrackingEvent.PowerSaveChange(value))
    }

    /**
     * Registers [receiver] with RECEIVER_NOT_EXPORTED; system broadcasts still reach it. Below API 33 androidx
     * emulates the flag with the `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` its manifest declares.
     * If an app strips that permission, fall back to a plain registration: safe, because every action this
     * monitor listens to is a protected broadcast that only the system can send.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerNotExported(receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) throw e
            Logger.w(TAG, "RECEIVER_NOT_EXPORTED emulation unavailable; registering without it", e)
            context.registerReceiver(receiver, filter)
        }
    }

    private fun capabilitiesOf(network: Network): NetworkCapabilities? =
        safely("getNetworkCapabilities", null) { connectivityManager?.getNetworkCapabilities(network) }

    private fun isProviderEnabled(lm: LocationManager?, provider: String): Boolean =
        lm != null && safely("isProviderEnabled($provider)", false) { lm.isProviderEnabled(provider) }

    private fun <T> systemService(type: Class<T>): T? = safely("getSystemService(${type.simpleName})", null) {
        ContextCompat.getSystemService(context, type)
    }

    private inline fun <T> safely(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Logger.w(TAG, "$what failed", e)
        fallback
    }

    internal companion object {
        private const val TAG = "LT.DeviceMonitor"
        private const val REASON_PROVIDERS_CHANGED = "providers_changed"
        private const val REASON_START = "start"

        /** Settle time between the first provider trigger of a burst and the check. */
        const val PROVIDER_CHECK_DEBOUNCE_MS = 1_000L
    }
}
