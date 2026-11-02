import Foundation
import CryptoKit

struct UploadBackoff {
    static func jittered(_ base: Double, random: Double) -> Double { floor(base * (0.8 + 0.4 * random)) }
    static func retryDelay(_ index: Int, random: Double) -> Double { jittered([2000, 4000, 8000][index], random: random) }
    static func serverWait(_ failures: Int, random: Double, retryAfter: Double?, minimum: Double) -> Double {
        max(jittered(min(30000 * pow(2, Double(max(0, min(20, failures - 1)))), 900000), random: random), retryAfter ?? 0, minimum)
    }
    static func retryAfter(_ text: String?, maximum: Double, now: Date = Date()) -> Double? {
        guard let text else { return nil }
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let seconds = Double(cleaned), seconds >= 0 { return min(seconds, maximum) * 1000 }
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = TimeZone(secondsFromGMT: 0)
        format.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        guard let date = format.date(from: cleaned) else { return nil }
        return min(max(0, date.timeIntervalSince(now)), maximum) * 1000
    }
    static func authKey(_ http: TrackingJSON) -> String {
        if http.object("headers").keys.contains(where: { $0.lowercased() == "authorization" }) { return "explicit-header" }
        let auth = http.object("authorization")
        return SHA256.hash(data: Data((auth.text("accessToken") + "\0" + auth.text("refreshToken")).utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }
}
