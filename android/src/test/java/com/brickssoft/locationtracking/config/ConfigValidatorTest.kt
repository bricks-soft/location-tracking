package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.config.ConfigSamples.CUSTOM
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.testing.FakeLogStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plain JVM: the validator uses no org.json. */
class ConfigValidatorTest {
    private val log = FakeLogStore()

    @Before
    fun setUp() {
        Logger.sink = log
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private val warnings get() = log.lines.filter { it.level == LogLevel.WARN }

    @Test
    fun `valid configs are returned unchanged without warnings`() {
        val defaults = Config()
        val boundaries = Config(
            geolocation = GeolocationConfig(distanceFilter = 0.0, stationaryRadius = 1.0, locationTimeout = 1),
            activity = ActivityConfig(minimumActivityRecognitionConfidence = 100),
            heartbeat = HeartbeatConfig(minInterval = 60, maxInterval = 60),
            http = HttpConfig(maxBatchSize = 1, autoSyncThreshold = 0, timeout = 1),
            persistence = PersistenceConfig(maxDaysToPersist = 1, maxRecordsToPersist = -1),
            notification = NotificationConfig(
                actions = List(3) { NotificationActionButton("a$it", "A$it") },
            ),
            logger = LoggerConfig(logMaxDays = 1),
        )

        assertSame(defaults, ConfigValidator.validate(defaults))
        assertSame(CUSTOM, ConfigValidator.validate(CUSTOM))
        assertSame(boundaries, ConfigValidator.validate(boundaries))
        assertEquals(emptyList<FakeLogStore.Line>(), warnings)
    }

    @Test
    fun `heartbeat minInterval is at least 60 s`() {
        val result = ConfigValidator.validate(Config(heartbeat = HeartbeatConfig(minInterval = 10, maxInterval = 300)))

        assertEquals(HeartbeatConfig(minInterval = 60, maxInterval = 300), result.heartbeat)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message, warnings[0].message.contains("heartbeat.minInterval=10"))
    }

    @Test
    fun `heartbeat maxInterval is at least the clamped minInterval`() {
        val a = ConfigValidator.validate(Config(heartbeat = HeartbeatConfig(minInterval = 240, maxInterval = 200)))
        val b = ConfigValidator.validate(Config(heartbeat = HeartbeatConfig(minInterval = 30, maxInterval = 45)))

        assertEquals(HeartbeatConfig(minInterval = 240, maxInterval = 240), a.heartbeat)
        assertEquals(HeartbeatConfig(minInterval = 60, maxInterval = 60), b.heartbeat)
    }

    @Test
    fun `activity confidence is clamped to 0-100`() {
        val low = ConfigValidator.validate(Config(activity = ActivityConfig(minimumActivityRecognitionConfidence = -5)))
        val high = ConfigValidator.validate(Config(activity = ActivityConfig(minimumActivityRecognitionConfidence = 150)))

        assertEquals(0, low.activity.minimumActivityRecognitionConfidence)
        assertEquals(100, high.activity.minimumActivityRecognitionConfidence)
    }

    @Test
    fun `distances, counts and days are clamped to their minimums`() {
        val input = Config(
            geolocation = GeolocationConfig(distanceFilter = -1.0, stationaryRadius = 0.5),
            http = HttpConfig(maxBatchSize = 0, autoSyncThreshold = -3),
            persistence = PersistenceConfig(maxDaysToPersist = 0, maxRecordsToPersist = -5),
            logger = LoggerConfig(logMaxDays = -2),
        )

        val result = ConfigValidator.validate(input)

        assertEquals(0.0, result.geolocation.distanceFilter, 0.0)
        assertEquals(1.0, result.geolocation.stationaryRadius, 0.0)
        assertEquals(1, result.http.maxBatchSize)
        assertEquals(0, result.http.autoSyncThreshold)
        assertEquals(1, result.persistence.maxDaysToPersist)
        assertEquals(-1, result.persistence.maxRecordsToPersist)
        assertEquals(1, result.logger.logMaxDays)
        assertEquals(7, warnings.size)
    }

    @Test
    fun `non-positive timeouts fall back to their defaults`() {
        val input = Config(
            geolocation = GeolocationConfig(locationTimeout = 0),
            http = HttpConfig(timeout = -1),
        )

        val result = ConfigValidator.validate(input)

        assertEquals(30_000L, result.geolocation.locationTimeout)
        assertEquals(60_000L, result.http.timeout)
        assertEquals(2, warnings.size)
    }

    @Test
    fun `negative intervals and delays become 0`() {
        val input = Config(
            geolocation = GeolocationConfig(
                locationUpdateInterval = -1,
                fastestLocationUpdateInterval = -1,
                elasticityMultiplier = -2.0,
                stopTimeout = -1,
                stopAfterElapsedMinutes = -1,
                filter = LocationFilterConfig(
                    trackingAccuracyThreshold = -1.0,
                    maxImpliedSpeed = -1.0,
                    odometerAccuracyThreshold = -1.0,
                ),
            ),
            activity = ActivityConfig(activityRecognitionInterval = -1, motionTriggerDelay = -1),
        )

        val result = ConfigValidator.validate(input)

        val expected = Config(
            geolocation = GeolocationConfig(
                locationUpdateInterval = 0,
                fastestLocationUpdateInterval = 0,
                elasticityMultiplier = 0.0,
                stopTimeout = 0,
                stopAfterElapsedMinutes = 0,
                filter = LocationFilterConfig(
                    trackingAccuracyThreshold = 0.0,
                    maxImpliedSpeed = 0.0,
                    odometerAccuracyThreshold = 0.0,
                ),
            ),
            activity = ActivityConfig(activityRecognitionInterval = 0, motionTriggerDelay = 0),
        )
        assertEquals(expected, result)
        assertEquals(10, warnings.size)
    }

    @Test
    fun `non-finite numbers fall back to their defaults`() {
        val input = Config(
            geolocation = GeolocationConfig(
                distanceFilter = Double.NaN,
                stationaryRadius = Double.POSITIVE_INFINITY,
                elasticityMultiplier = Double.NEGATIVE_INFINITY,
                filter = LocationFilterConfig(maxImpliedSpeed = Double.NaN),
            ),
        )

        assertEquals(Config(), ConfigValidator.validate(input))
        assertEquals(4, warnings.size)
    }

    @Test
    fun `at most 3 notification actions are kept`() {
        val actions = List(5) { NotificationActionButton("id$it", "Label $it") }

        val result = ConfigValidator.validate(Config(notification = NotificationConfig(actions = actions)))

        assertEquals(actions.take(3), result.notification.actions)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `every clamp is logged once`() {
        val input = Config(
            heartbeat = HeartbeatConfig(minInterval = 1, maxInterval = 2),
            activity = ActivityConfig(minimumActivityRecognitionConfidence = 101),
            http = HttpConfig(maxBatchSize = -1),
        )

        ConfigValidator.validate(input)

        assertEquals(4, warnings.size)
        val text = warnings.joinToString("\n") { it.message }
        for (path in listOf(
            "heartbeat.minInterval",
            "heartbeat.maxInterval",
            "activity.minimumActivityRecognitionConfidence",
            "http.maxBatchSize",
        )) {
            assertTrue("$path in $text", text.contains(path))
        }
    }
}
