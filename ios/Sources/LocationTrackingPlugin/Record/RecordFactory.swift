import Foundation
import CoreLocation
import UIKit

struct RecordFactory {
    static func make(event: String, fix: CLLocation?, extras: TrackingJSON, odometer: Double, provider: TrackingJSON, network: String) -> TrackingJSON {
        var coords: Any = NSNull()
        if let fix {
            coords = ["latitude": fix.coordinate.latitude, "longitude": fix.coordinate.longitude, "accuracy": fix.horizontalAccuracy,
                      "altitude": fix.verticalAccuracy >= 0 ? fix.altitude as Any : NSNull(), "altitude_accuracy": fix.verticalAccuracy >= 0 ? fix.verticalAccuracy as Any : NSNull(),
                      "speed": fix.speed >= 0 ? fix.speed as Any : NSNull(), "speed_accuracy": fix.speedAccuracy >= 0 ? fix.speedAccuracy as Any : NSNull(),
                      "heading": fix.course >= 0 ? fix.course as Any : NSNull(), "heading_accuracy": fix.courseAccuracy >= 0 ? fix.courseAccuracy as Any : NSNull()]
        }
        let readings = DeviceReadings.shared.snapshot(event: event)
        var wire: TrackingJSON = ["uuid": UUID().uuidString.lowercased(), "event": event, "timestamp": fix.map { iso($0.timestamp) } as Any? ?? NSNull(), "recorded_at": iso(),
                "elapsed_realtime_ms": continuousMilliseconds(), "boot_count": -1, "is_moving": false, "odometer": odometer,
                "mock": fix?.sourceInformation?.isSimulatedBySoftware ?? false, "coords": coords, "activity": ["type": "unknown", "confidence": 0],
                "battery": readings.battery,
                "backend": "corelocation", "extras": extras, "provider": provider, "network": network, "device": readings.device]
        if extras.isEmpty { wire.removeValue(forKey: "extras") }
        if readings.device.isEmpty { wire.removeValue(forKey: "device") }
        return wire
    }
    static func continuousMilliseconds() -> Double {
        TrackingClock.elapsedMilliseconds()
    }
}
