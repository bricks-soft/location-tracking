package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.LocationProviderSetting
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.testing.Fixtures

/** Test data for the config package: a config where every field differs from its default, and its JSON. */
object ConfigSamples {
    /** Every field differs from [Config]'s default (JSON-text fields are in canonical `JSONObject.toString()` form). */
    val CUSTOM = Config(
        geolocation = GeolocationConfig(
            desiredAccuracy = DesiredAccuracy.BALANCED,
            distanceFilter = 42.5,
            locationUpdateInterval = 5_000,
            fastestLocationUpdateInterval = 2_500,
            disableElasticity = true,
            elasticityMultiplier = 2.5,
            stationaryRadius = 75.0,
            stopTimeout = 10,
            stopAfterElapsedMinutes = 120,
            stopOnStationary = true,
            locationTimeout = 45_000,
            filter = LocationFilterConfig(
                useKalman = true,
                trackingAccuracyThreshold = 50.0,
                maxImpliedSpeed = 0.0,
                odometerAccuracyThreshold = 15.5,
                allowIdenticalLocations = true,
                rejectMockLocations = true,
            ),
        ),
        activity = ActivityConfig(
            disableMotionActivityUpdates = true,
            activityRecognitionInterval = 20_000,
            minimumActivityRecognitionConfidence = 50,
            motionTriggerDelay = 30_000,
            disableStopDetection = true,
        ),
        heartbeat = HeartbeatConfig(enabled = false, minInterval = 240, maxInterval = 360),
        http = HttpConfig(
            url = "https://example.com/locations",
            method = HttpMethod.PUT,
            headers = mapOf("X-Api-Key" to "k1", "X-Tenant" to "t"),
            params = """{"device_id":"abc","fleet":7}""",
            autoSync = false,
            autoSyncThreshold = 5,
            syncInterval = 300,
            batchSync = true,
            maxBatchSize = 50,
            disableAutoSyncOnCellular = true,
            rootProperty = "data",
            locationTemplate = """{"lat":<%= latitude %>,"ts":"<%= timestamp %>"}""",
            geofenceTemplate = """{"id":"<%= geofence.identifier %>"}""",
            timeout = 15_000,
            authorization = AuthorizationConfig(
                strategy = "JWT",
                accessToken = "access-1",
                refreshToken = "refresh-1",
                refreshUrl = "https://example.com/refresh",
                refreshPayload = """{"refresh_token":"{refreshToken}"}""",
                refreshHeaders = mapOf("X-Refresh" to "1"),
                refreshPayloadEncoding = RefreshPayloadEncoding.FORM,
                expires = 1_790_417_730_456L,
            ),
        ),
        persistence = PersistenceConfig(
            maxDaysToPersist = 14,
            maxRecordsToPersist = 5_000,
            extras = """{"driver_id":7,"tags":["a","b"]}""",
        ),
        app = AppConfig(stopOnTerminate = false, startOnBoot = true),
        notification = NotificationConfig(
            title = "Tracking",
            text = "On duty",
            smallIcon = "mipmap/ic_launcher",
            largeIcon = "drawable/big",
            color = "#FF0000",
            priority = NotificationPriority.HIGH,
            channelId = "ch",
            channelName = "Channel",
            actions = listOf(NotificationActionButton("pause", "Pause"), NotificationActionButton("stop", "Stop")),
        ),
        geofence = GeofenceConfig(initialTriggerEntry = false),
        logger = LoggerConfig(logLevel = LogLevel.DEBUG, logMaxDays = 10),
        backgroundPermissionRationale = BackgroundPermissionRationale(
            title = "Allow all the time",
            message = "Needed for tracking",
            positiveAction = "Settings",
            negativeAction = "Later",
        ),
        locationProvider = LocationProviderSetting.HMS,
    )

    /** [CUSTOM] as TS `Config` JSON. */
    const val CUSTOM_JSON = """
        {
          "geolocation": {
            "desiredAccuracy": "balanced", "distanceFilter": 42.5, "locationUpdateInterval": 5000,
            "fastestLocationUpdateInterval": 2500, "disableElasticity": true, "elasticityMultiplier": 2.5,
            "stationaryRadius": 75, "stopTimeout": 10, "stopAfterElapsedMinutes": 120, "stopOnStationary": true,
            "locationTimeout": 45000,
            "filter": { "useKalman": true, "trackingAccuracyThreshold": 50, "maxImpliedSpeed": 0,
                        "odometerAccuracyThreshold": 15.5, "allowIdenticalLocations": true, "rejectMockLocations": true }
          },
          "activity": { "disableMotionActivityUpdates": true, "activityRecognitionInterval": 20000,
                        "minimumActivityRecognitionConfidence": 50, "motionTriggerDelay": 30000,
                        "disableStopDetection": true },
          "heartbeat": { "enabled": false, "minInterval": 240, "maxInterval": 360 },
          "http": {
            "url": "https://example.com/locations", "method": "PUT",
            "headers": { "X-Api-Key": "k1", "X-Tenant": "t" },
            "params": { "device_id": "abc", "fleet": 7 },
            "autoSync": false, "autoSyncThreshold": 5, "syncInterval": 300, "batchSync": true, "maxBatchSize": 50,
            "disableAutoSyncOnCellular": true, "rootProperty": "data",
            "locationTemplate": "{\"lat\":<%= latitude %>,\"ts\":\"<%= timestamp %>\"}",
            "geofenceTemplate": "{\"id\":\"<%= geofence.identifier %>\"}",
            "timeout": 15000,
            "authorization": {
              "strategy": "JWT", "accessToken": "access-1", "refreshToken": "refresh-1",
              "refreshUrl": "https://example.com/refresh", "refreshPayload": { "refresh_token": "{refreshToken}" },
              "refreshHeaders": { "X-Refresh": "1" }, "refreshPayloadEncoding": "form", "expires": 1790417730456
            }
          },
          "persistence": { "maxDaysToPersist": 14, "maxRecordsToPersist": 5000,
                           "extras": { "driver_id": 7, "tags": ["a", "b"] } },
          "app": { "stopOnTerminate": false, "startOnBoot": true },
          "notification": {
            "title": "Tracking", "text": "On duty", "smallIcon": "mipmap/ic_launcher", "largeIcon": "drawable/big",
            "color": "#FF0000", "priority": "high", "channelId": "ch", "channelName": "Channel",
            "actions": [ { "id": "pause", "label": "Pause" }, { "id": "stop", "label": "Stop" } ]
          },
          "geofence": { "initialTriggerEntry": false },
          "logger": { "logLevel": "debug", "logMaxDays": 10 },
          "backgroundPermissionRationale": { "title": "Allow all the time", "message": "Needed for tracking",
                                             "positiveAction": "Settings", "negativeAction": "Later" },
          "locationProvider": "hms"
        }
    """

    /** [Config] defaults as TS `Config` JSON. */
    const val DEFAULT_JSON = """
        {
          "geolocation": {
            "desiredAccuracy": "high", "distanceFilter": 10, "locationUpdateInterval": 1000,
            "fastestLocationUpdateInterval": 500, "disableElasticity": false, "elasticityMultiplier": 1,
            "stationaryRadius": 25, "stopTimeout": 5, "stopAfterElapsedMinutes": 0, "stopOnStationary": false,
            "locationTimeout": 30000,
            "filter": { "useKalman": false, "trackingAccuracyThreshold": 100, "maxImpliedSpeed": 80,
                        "odometerAccuracyThreshold": 20, "allowIdenticalLocations": false, "rejectMockLocations": false }
          },
          "activity": { "disableMotionActivityUpdates": false, "activityRecognitionInterval": 10000,
                        "minimumActivityRecognitionConfidence": 75, "motionTriggerDelay": 0,
                        "disableStopDetection": false },
          "heartbeat": { "enabled": true, "minInterval": 180, "maxInterval": 300 },
          "http": {
            "url": null, "method": "POST", "headers": {}, "params": {}, "autoSync": true, "autoSyncThreshold": 0,
            "syncInterval": 0, "batchSync": false, "maxBatchSize": 100, "disableAutoSyncOnCellular": false, "rootProperty": "location",
            "locationTemplate": null, "geofenceTemplate": null, "timeout": 60000, "authorization": null
          },
          "persistence": { "maxDaysToPersist": 7, "maxRecordsToPersist": -1, "extras": {} },
          "app": { "stopOnTerminate": true, "startOnBoot": false },
          "notification": {
            "title": null, "text": "Location tracking is active", "smallIcon": "drawable/lt_ic_notification",
            "largeIcon": null, "color": null, "priority": "default", "channelId": "location_tracking",
            "channelName": "Location tracking", "actions": []
          },
          "geofence": { "initialTriggerEntry": true },
          "logger": { "logLevel": "info", "logMaxDays": 3 },
          "backgroundPermissionRationale": { "title": null, "message": null, "positiveAction": null,
                                             "negativeAction": null },
          "locationProvider": "auto"
        }
    """

    /** A runtime state where every field differs from its default. */
    val RUNTIME = RuntimeState(
        enabled = true,
        trackingMode = TrackingMode.GEOFENCES,
        isMoving = true,
        odometer = 1532.4,
        activity = ActivitySample(ActivityType.IN_VEHICLE, 92),
        lastLocation = Fixtures.location(elapsedRealtimeNanos = 86_400_123_000_000L, provider = "fused", isMock = true),
        lastRecordAt = 1_790_417_730_456L,
        lastRecordElapsed = 86_400_123L,
        lastRecordBootCount = 42,
        lastHeartbeatAt = 1_790_417_700_000L,
        trackingStartedAt = 1_790_410_000_000L,
        providerState = ProviderState(
            enabled = true,
            gps = true,
            network = false,
            permission = PermissionLevel.WHEN_IN_USE,
            accuracy = AccuracyLevel.APPROXIMATE,
            backend = ProviderKind.HMS,
        ),
        didReady = true,
    )
}
