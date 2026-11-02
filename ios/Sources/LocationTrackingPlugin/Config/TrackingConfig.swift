import Foundation

final class TrackingConfig {
    private(set) var value: TrackingJSON
    private(set) var secretsUnavailable = false
    private let store: TrackingStore
    private let secrets: SecretStore
    init(store: TrackingStore, secrets: SecretStore) throws {
        self.store = store
        self.secrets = secrets
        var warnings: [String] = []
        value = try ConfigSchema.parse(try store.load("config") ?? [:], base: ConfigDefaults.value, lenient: true, warnings: &warnings)
        if (try store.load("authorization"))?.flag("configured") == true {
            var http = value.object("http")
            do {
                guard let auth = try secrets.load() else { throw TrackingError("IO_ERROR", "Stored authorization is missing from Keychain") }
                http["authorization"] = auth
            } catch {
                secretsUnavailable = true
                http["authorization"] = TrackingJSON()
            }
            value["http"] = http
        }
    }
    @discardableResult func apply(_ patch: TrackingJSON, reset: Bool) throws -> [String] {
        var warnings: [String] = []
        var candidate = try ConfigSchema.parse(patch, base: reset ? ConfigDefaults.value : value, warnings: &warnings)
        let clamped = ConfigValidator.clamp(candidate)
        candidate = clamped.0
        warnings += clamped.1
        var heartbeat = candidate.object("heartbeat")
        heartbeat["minInterval"] = max(60, heartbeat.number("minInterval", 180))
        heartbeat["maxInterval"] = max(heartbeat.number("minInterval"), heartbeat.number("maxInterval", 300))
        heartbeat["uploadKeepAwake"] = min(60, max(0, heartbeat.number("uploadKeepAwake", 10)))
        candidate["heartbeat"] = heartbeat
        var notification = candidate.object("notification")
        var resume = notification.object("resume")
        if resume.number("delay", 600) < heartbeat.number("maxInterval") + 60 {
            resume["delay"] = heartbeat.number("maxInterval") + 60
            warnings.append("notification.resume.delay raised to heartbeat.maxInterval + 60")
        }
        notification["resume"] = resume
        candidate["notification"] = notification
        let http = candidate.object("http")
        if let url = http["url"] as? String, !url.isEmpty {
            guard let parsed = URL(string: url), ["https", "http"].contains(parsed.scheme?.lowercased() ?? ""), parsed.host != nil else { throw TrackingError("INVALID_ARGUMENT", "http.url must be an HTTP URL") }
            if parsed.scheme == "http" { warnings.append("http.url uses unencrypted HTTP; iOS App Transport Security may refuse it") }
        }
        for section in ["geolocation", "activity", "heartbeat", "http", "persistence", "logger"] {
            try Self.validateNumbers(candidate.object(section))
        }
        guard ["POST", "PUT", "PATCH"].contains(http.text("method", "POST")), http.number("timeout", 60000) > 0, http.number("maxBatchSize", 100) >= 1 else { throw TrackingError("INVALID_ARGUMENT", "Invalid HTTP method, timeout or batch size") }
        _ = try jsonData(candidate)
        var persisted = candidate
        var persistedHttp = http
        persistedHttp["authorization"] = NSNull()
        persisted["http"] = persistedHttp
        let previousSecrets = value.object("http")["authorization"] as? TrackingJSON
        let nextSecrets = http["authorization"] as? TrackingJSON
        let differentSecrets = try jsonData(previousSecrets ?? [:]) != jsonData(nextSecrets ?? [:])
        let explicitAuthorization = patch.object("http").keys.contains("authorization") || reset
        let changedSecrets = (previousSecrets == nil) != (nextSecrets == nil) || differentSecrets || (secretsUnavailable && explicitAuthorization)
        if changedSecrets { try secrets.save(nextSecrets) }
        do {
            try store.transaction {
                try store.save("config", persisted)
                try store.save("authorization", ["configured": nextSecrets != nil])
            }
        } catch {
            if changedSecrets {
                do { try secrets.save(previousSecrets) }
                catch { throw TrackingError("IO_ERROR", "Configuration write and authorization rollback failed") }
            }
            throw error
        }
        value = candidate
        if explicitAuthorization { secretsUnavailable = false }
        return warnings
    }
    var masked: TrackingJSON {
        var result = value
        var http = result.object("http")
        if var auth = http["authorization"] as? TrackingJSON {
            for key in ["accessToken", "refreshToken"] {
                if let text = auth[key] as? String { auth[key] = Self.mask(text) }
            }
            for key in ["refreshPayload", "refreshHeaders"] {
                var map = auth.object(key)
                for (name, value) in map {
                    if let text = value as? String, !text.contains("{refreshToken}") { map[name] = Self.mask(text) }
                }
                auth[key] = map
            }
            http["authorization"] = auth
        }
        result["http"] = http
        return result
    }
    private static func validateNumbers(_ object: TrackingJSON) throws {
        for (_, value) in object {
            if let number = value as? NSNumber, !number.doubleValue.isFinite || abs(number.doubleValue) >= Double(Int64.max) / 2 { throw TrackingError("INVALID_ARGUMENT", "Configuration number is out of range") }
        }
    }
    private static func mask(_ text: String) -> String { text.count > 8 ? "***" + text.suffix(4) : "***" }
}
