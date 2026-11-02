import Foundation
import Darwin

enum TrackingClock {
    static func elapsedMilliseconds() -> Double {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return Double(mach_continuous_time()) * Double(info.numer) / Double(info.denom) / 1e6
    }
}
