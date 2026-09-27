package com.brickssoft.locationtracking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Logger
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every component can be constructed through [Components] on every supported SDK. Must stay green after every unit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 33, 34, 35])
class ScaffoldWiringTest {
    @After
    fun tearDown() {
        Components.reset()
        Logger.sink = null
    }

    @Test
    fun `components wire up and every public val resolves`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        val c = Components.get(app)

        assertSame(c, Components.get(app))
        assertSame(app, c.context)
        val all: List<Any> = listOf(
            c.clock, c.dispatchers, c.scope, c.events, c.http, c.logStore, c.configStore, c.permissions,
            c.deviceSettings, c.providers, c.device, c.deviceInfo, c.locationStore, c.geofenceStore, c.processor,
            c.odometer, c.recordFactory, c.syncer, c.heartbeat, c.recordSink, c.geofences, c.positions,
            c.serviceController, c.engine, c.recordHooks, c.stationarySink,
        )
        all.forEach { assertNotNull(it) }
        assertSame(c.logStore, Logger.sink)
        assertNotNull(c.engine.state())
        assertNotNull(c.providers.kind)
        assertSame(c.engine, c.stationarySink)
    }

    @Test
    fun `reset forgets the instance`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = Components.get(app)

        Components.reset()

        val second = Components.get(app)
        assertNotSame(first, second)
    }
}
