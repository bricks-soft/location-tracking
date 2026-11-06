import Foundation

enum ConfigDefaults {
    static let value: TrackingJSON = try! jsonObject(Data(#"""
{
  "geolocation": {
    "desiredAccuracy": "high",
    "distanceFilter": 10,
    "locationUpdateInterval": 1000,
    "fastestLocationUpdateInterval": 500,
    "disableElasticity": false,
    "elasticityMultiplier": 1,
    "stationaryRadius": 25,
    "stopTimeout": 5,
    "stopAfterElapsedMinutes": 0,
    "stopOnStationary": false,
    "locationTimeout": 30000,
    "filter": {
      "useKalman": false,
      "trackingAccuracyThreshold": 100,
      "maxImpliedSpeed": 80,
      "odometerAccuracyThreshold": 20,
      "allowIdenticalLocations": false,
      "rejectMockLocations": false
    }
  },
  "activity": {
    "disableMotionActivityUpdates": false,
    "activityRecognitionInterval": 10000,
    "minimumActivityRecognitionConfidence": 75,
    "motionTriggerDelay": 0,
    "disableStopDetection": false
  },
  "heartbeat": {
    "enabled": true,
    "minInterval": 180,
    "maxInterval": 300,
    "uploadKeepAwake": 10
  },
  "http": {
    "url": null,
    "method": "POST",
    "headers": {},
    "params": {},
    "autoSync": true,
    "autoSyncThreshold": 0,
    "syncInterval": 0,
    "batchSync": false,
    "maxBatchSize": 100,
    "disableAutoSyncOnCellular": false,
    "rootProperty": "location",
    "locationTemplate": null,
    "geofenceTemplate": null,
    "timeout": 60000,
    "maxRetryAfter": 3600,
    "authorization": null
  },
  "persistence": {
    "maxDaysToPersist": 7,
    "maxRecordsToPersist": -1,
    "extras": {}
  },
  "app": {
    "stopOnTerminate": true,
    "startOnBoot": false
  },
  "notification": {
    "title": null,
    "largeIcon": null,
    "color": null,
    "text": "Location tracking is active",
    "smallIcon": "drawable/lt_ic_notification",
    "priority": "default",
    "channelId": "location_tracking",
    "channelName": "Location tracking",
    "actions": [],
    "resume": {
      "title": null,
      "enabled": false,
      "text": "Location tracking is paused. Tap to resume.",
      "channelName": "Paused location tracking",
      "delay": 600
    }
  },
  "geofence": {
    "initialTriggerEntry": true
  },
  "logger": {
    "logLevel": "info",
    "logMaxDays": 3
  },
  "backgroundPermissionRationale": {"title": null, "message": null, "positiveAction": null, "negativeAction": null},
  "locationProvider": "auto"
}
"""#.utf8))
}
