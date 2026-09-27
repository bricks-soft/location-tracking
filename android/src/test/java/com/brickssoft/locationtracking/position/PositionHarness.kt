package com.brickssoft.locationtracking.position

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.config.LocationFilterConfig
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeLocationBackend
import com.brickssoft.locationtracking.testing.FakePermissionManager
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeRecordFactory
import com.brickssoft.locationtracking.testing.FakeRecordSink
import com.brickssoft.locationtracking.testing.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [DefaultPositionService] over scaffold fakes. [clock] follows the scheduler's virtual time.
 * While [backendOverride] is set, the provider factory returns it instead of [backend] (e.g. a failing backend,
 * or another backend after a provider reselection).
 */
internal class PositionHarness(
    scheduler: TestCoroutineScheduler,
    scope: CoroutineScope,
    config: Config = Config(),
    sinkOverride: RecordSink? = null,
    @Volatile var backendOverride: LocationBackend? = null,
) {
    val clock = FakeClock(scheduler = scheduler)
    val backend = FakeLocationBackend()
    val providers = FakeProviderFactory(locationBackend = backend)
    val configStore = FakeConfigStore(config)
    val permissions = FakePermissionManager()
    val device = FakeDeviceMonitor()
    val recordFactory = FakeRecordFactory(clock, configStore)
    val sink = FakeRecordSink()

    private val providerFactory: ProviderFactory = object : ProviderFactory by providers {
        override fun location(): LocationBackend = backendOverride ?: providers.location()
    }

    val service = DefaultPositionService(
        providerFactory,
        configStore,
        permissions,
        device,
        recordFactory,
        sinkOverride ?: sink,
        clock,
        scope,
    )

    /** A fix with [accuracy] that is [ageMs] old at the current virtual time. */
    fun fix(accuracy: Float, ageMs: Long = 0L, isMock: Boolean = false): TrackedLocation =
        Fixtures.location(accuracy = accuracy, time = clock.now() - ageMs, isMock = isMock)

    companion object {
        val REJECT_MOCK = Config(geolocation = GeolocationConfig(filter = LocationFilterConfig(rejectMockLocations = true)))
    }
}

/** Collects watch callbacks. */
internal class Deliveries : (Record?, TrackingException?) -> Unit {
    val all = CopyOnWriteArrayList<Pair<Record?, TrackingException?>>()
    val records: List<Record> get() = all.mapNotNull { it.first }
    val errors: List<TrackingException> get() = all.mapNotNull { it.second }

    override fun invoke(record: Record?, error: TrackingException?) {
        all += record to error
    }
}

/** A [RecordSink] whose submit takes [delayMs] of virtual time. */
internal class DelayingSink(private val delayMs: Long) : RecordSink {
    val records = CopyOnWriteArrayList<Record>()

    override suspend fun submit(record: Record): Record {
        delay(delayMs)
        records += record
        return record
    }
}
