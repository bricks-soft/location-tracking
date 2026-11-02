import Foundation

struct ConfigValidator {
    static func clamp(_ candidate: TrackingJSON) -> (TrackingJSON, [String]) {
        var result = candidate
        var warnings: [String] = []
        let minimums: [String: [String: Double]] = [
            "geolocation": ["distanceFilter": 0, "locationUpdateInterval": 0, "fastestLocationUpdateInterval": 0, "elasticityMultiplier": 0, "stationaryRadius": 1, "stopTimeout": 0, "stopAfterElapsedMinutes": 0],
            "activity": ["activityRecognitionInterval": 0, "minimumActivityRecognitionConfidence": 0, "motionTriggerDelay": 0],
            "http": ["maxBatchSize": 1, "autoSyncThreshold": 0, "syncInterval": 0, "maxRetryAfter": 0],
            "persistence": ["maxDaysToPersist": 1, "maxRecordsToPersist": -1], "logger": ["logMaxDays": 1]
        ]
        for (section, fields) in minimums {
            var values = result.object(section)
            for (key, minimum) in fields {
                let current = values.number(key, ConfigDefaults.value.object(section).number(key))
                let maximum = section == "activity" && key == "minimumActivityRecognitionConfidence" ? 100.0 : Double(Int32.max)
                let clamped = min(maximum, max(minimum, current))
                if clamped != current { warnings.append("\(section).\(key) clamped to \(clamped)") }
                values[key] = clamped
            }
            result[section] = values
        }
        for section in ["http", "geolocation"] {
            let key = section == "http" ? "timeout" : "locationTimeout"
            var values = result.object(section)
            if values.number(key) <= 0 {
                values[key] = ConfigDefaults.value.object(section)[key]
                warnings.append("\(section).\(key) raised to its default")
            }
            result[section] = values
        }
        var geo = result.object("geolocation")
        var filter = geo.object("filter")
        for key in ["trackingAccuracyThreshold", "maxImpliedSpeed", "odometerAccuracyThreshold"] { filter[key] = max(0, filter.number(key, ConfigDefaults.value.object("geolocation").object("filter").number(key))) }
        geo["filter"] = filter
        result["geolocation"] = geo
        return (result, warnings)
    }
}
