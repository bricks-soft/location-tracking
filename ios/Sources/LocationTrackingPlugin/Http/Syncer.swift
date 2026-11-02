import Foundation

final class Syncer {
    let transport = HTTPTransport()
    private let queue: DispatchQueue
    private let store: TrackingStore
    private let config: TrackingConfig
    private let emit: (String, TrackingJSON) -> Void
    private var callbacks: [(Result<[TrackingJSON], Error>) -> Void] = []
    private var uploaded: [TrackingJSON] = []
    private var reducedBatch: Int?
    private var busy = false
    private var closed = false
    private var pendingAutomatic = false
    private var pendingRecovery = false
    private var changedUntil: Date?
    private var rejectedPriority = Set<String>()
    private var passFailure: (Error, TrackingJSON, Int, Double?)?
    private enum Renewal { case renewed(TrackingJSON), changed, failed(Error) }
    var connectivityKnown = false
    private var priorityOnly = false
    private var generation = 0
    private var shortLivedToken: String?
    private var failures = 0
    private var retryTimer: DispatchWorkItem?
    var connected = true
    var cellular = false
    var tracking = false
    private let priority = Set(["heartbeat", "tracking_start", "tracking_stop", "providerchange"])
    init(queue: DispatchQueue, store: TrackingStore, config: TrackingConfig, emit: @escaping (String, TrackingJSON) -> Void) {
        self.queue = queue; self.store = store; self.config = config; self.emit = emit
        failures = Int((try? store.load("uploadWait"))?.number("failures") ?? 0)
    }
    static func needsRefresh(_ auth: TrackingJSON, now: Double, shortLivedToken: String? = nil) -> Bool {
        let token = auth.text("accessToken")
        if token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        let expires = auth.number("expires", -1)
        if expires <= 0 { return false }
        return now >= expires || (now >= expires - 60000 && token != shortLivedToken)
    }
    func close() {
        guard !closed else { return }
        closed = true
        retryTimer?.cancel()
        cancel()
        transport.close()
    }
    func cancel() {
        generation += 1
        pendingAutomatic = false
        transport.cancel()
        if busy { finish(.failure(TrackingError("NETWORK_ERROR", "Upload cancelled"))) }
    }
    func connectivity(connected: Bool, cellular: Bool) {
        let recovered = connectivityKnown && !self.connected && connected
        connectivityKnown = true
        self.connected = connected
        self.cellular = cellular
        if recovered && busy { pendingRecovery = true }
        if recovered, let wait = try? store.load("uploadWait"), wait.text("reason") == "server" {
            try? store.save("uploadWait", [:])
            failures = 0
            retryTimer?.cancel()
        }
        automatic()
    }
    func automatic(completion: ((Result<[TrackingJSON], Error>) -> Void)? = nil) {
        guard !closed else { completion?(.failure(TrackingError("NETWORK_ERROR", "Syncer is closed"))); return }
        if busy {
            pendingAutomatic = true
            if let completion { callbacks.append(completion) }
            return
        }
        guard connectivityKnown && connected else { completion?(.success([])); return }
        if let until = changedUntil, until > Date() { schedule(until.timeIntervalSince1970 * 1000); completion?(.success([])); return }
        changedUntil = nil
        let records: [TrackingJSON]
        do { records = try store.locations() }
        catch { completion?(.failure(error)); return }
        guard !records.isEmpty else { completion?(.success([])); return }
        let http = config.value.object("http")
        guard !http.text("url").isEmpty else { completion?(.success([])); return }
        let hasPriority = records.contains { priority.contains($0.text("event")) }
        if hasPriority { run(priorityOnly: cellular && http.flag("disableAutoSyncOnCellular"), completion: completion ?? { _ in }); return }
        guard http.flag("autoSync", true), !(cellular && http.flag("disableAutoSyncOnCellular")) else { completion?(.success([])); return }
        if let wait = try? store.load("uploadWait"), wait.number("at") > Date().timeIntervalSince1970 * 1000,
           wait.text("reason") != "authorization" || wait.text("authKey") == UploadBackoff.authKey(http) { schedule(wait.number("at")); completion?(.success([])); return }
        let interval = tracking ? http.number("syncInterval") : 0
        let threshold = http.number("autoSyncThreshold")
        if interval > 0, let oldest = records.first {
            let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let age = Date().timeIntervalSince(formatter.date(from: oldest.text("recorded_at")) ?? .distantPast)
            if age >= 0 && age < interval && !(threshold > 0 && Double(records.count) >= threshold) { schedule(Date().timeIntervalSince1970 * 1000 + (interval - age) * 1000); completion?(.success([])); return }
        } else if Double(records.count) < max(1, threshold) { completion?(.success([])); return }
        run(completion: completion ?? { _ in })
    }
    func run(priorityOnly: Bool = false, completion: @escaping (Result<[TrackingJSON], Error>) -> Void) {
        guard !closed else { completion(.failure(TrackingError("NETWORK_ERROR", "Syncer is closed"))); return }
        guard !config.value.object("http").text("url").isEmpty else { completion(.failure(TrackingError("NO_URL", "No http.url configured"))); return }
        callbacks.append(completion)
        if busy { if !priorityOnly { self.priorityOnly = false }; return }
        self.priorityOnly = priorityOnly
        busy = true; uploaded = []; reducedBatch = nil; rejectedPriority = []; passFailure = nil
        next()
    }
    private func next() {
        do {
            let http = config.value.object("http")
            let limit = http.flag("batchSync") ? max(1, Int(http.number("maxBatchSize", 100))) : 1
            let size = reducedBatch.map { min(limit, $0) } ?? limit
            let chunk = priorityOnly ? Array(try store.locations().filter { priority.contains($0.text("event")) && !rejectedPriority.contains($0.text("uuid")) }.prefix(size)) : try store.locations(limit: size)
            if chunk.isEmpty {
                if let failure = passFailure { fail(failure.0, http: failure.1, status: failure.2, retryAfter: failure.3, authorizationFailure: false) }
                else {
                    if !priorityOnly { failures = 0; try store.save("uploadWait", [:]) }
                    finish(.success(uploaded))
                }
                return
            }
            upload(chunk, http: http, attempt: 0, refreshed: false)
        } catch { finish(.failure(error)) }
    }
    private func upload(_ records: [TrackingJSON], http: TrackingJSON, attempt: Int, refreshed: Bool) {
        let captured = generation
        let explicitAuthorization = http.object("headers").keys.contains { $0.lowercased() == "authorization" }
        if config.secretsUnavailable && !explicitAuthorization {
            emit("authorization", ["success": false, "status": 0, "error": "Stored authorization is unavailable; set http.authorization again"])
            fail(TrackingError("HTTP_ERROR", "Stored authorization is unavailable; set http.authorization again"), http: http, status: 401, retryAfter: nil)
            return
        }
        guard UploadBackoff.authKey(http) == UploadBackoff.authKey(config.value.object("http")) else { credentialsChanged(); return }
        let auth = explicitAuthorization ? [:] : http.object("authorization")
        if !auth.isEmpty && !auth.text("refreshUrl").isEmpty && !refreshed && Self.needsRefresh(auth, now: Date().timeIntervalSince1970 * 1000, shortLivedToken: shortLivedToken) {
            refresh(http) { result in
                guard self.generation == captured else { return }
                switch result {
                case .renewed(let fresh): self.upload(records, http: fresh, attempt: attempt, refreshed: true)
                case .changed: self.credentialsChanged()
                case .failed(let error):
                    if !auth.text("accessToken").isEmpty { self.upload(records, http: http, attempt: attempt, refreshed: true) }
                    else { self.fail(error, http: http, status: 401, retryAfter: nil) }
                }
            }
            return
        }
        do {
            guard let url = URL(string: http.text("url")), ["http", "https"].contains(url.scheme ?? "") else { throw TrackingError("INVALID_ARGUMENT", "Invalid upload URL") }
            var headers = http["headers"] as? [String: String] ?? [:]
            if !auth.text("accessToken").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { headers = headers.filter { $0.key.lowercased() != "authorization" }; headers["Authorization"] = "Bearer " + auth.text("accessToken") }
            let body = try BodyBuilder.build(records, http: http)
            transport.send(url: url, method: http.text("method", "POST"), headers: headers, body: body, timeout: http.number("timeout", 60000)) { result in
                self.queue.async {
                    guard self.generation == captured, self.busy else { return }
                    let response = try? result.get()
                    let status = response?.status ?? 0
                    self.emit("http", ["success": response?.success ?? false, "status": status, "responseText": response.map { String(decoding: $0.body, as: UTF8.self) } ?? "Network request failed", "uuids": records.map { $0.text("uuid") }])
                    if response?.success == true {
                        do {
                            try self.store.transaction { for record in records { try self.store.delete(record.text("uuid")) } }
                            self.uploaded += records
                            self.next()
                        } catch { self.finish(.failure(error)) }
                        return
                    }
                    if UploadBackoff.authKey(http) != UploadBackoff.authKey(self.config.value.object("http")) {
                        self.credentialsChanged(); return
                    }
                    if status == 401 && !refreshed && !auth.isEmpty {
                        self.refresh(http) { renewal in
                            guard self.generation == captured else { return }
                            switch renewal {
                            case .renewed(let fresh): self.upload(records, http: fresh, attempt: attempt, refreshed: true)
                            case .changed: self.credentialsChanged()
                            case .failed(let error): self.fail(error, http: http, status: 401, retryAfter: nil)
                            }
                        }
                        return
                    }
                    if status == 413 && records.count > 1 { self.reducedBatch = max(1, records.count / 2); self.next(); return }
                    let retryAfter = (status == 429 || status == 503) ? UploadBackoff.retryAfter(response?.retryAfter, maximum: http.number("maxRetryAfter", 3600)) : nil
                    if (status == 0 || status == 429 || status >= 500), attempt < 3 {
                        let delay = UploadBackoff.retryDelay(attempt, random: Double.random(in: 0..<1))
                        if (retryAfter ?? 0) <= delay {
                            self.queue.asyncAfter(deadline: .now() + delay / 1000) {
                                guard self.generation == captured, self.busy else { return }
                                self.upload(records, http: http, attempt: attempt + 1, refreshed: refreshed)
                            }
                            return
                        }
                    }
                    self.reject(records, error: TrackingError(status == 0 ? "NETWORK_ERROR" : "HTTP_ERROR", status == 0 ? "Network request failed" : "Upload failed: HTTP \(status)"), http: http, status: status, retryAfter: retryAfter)
                }
            }
        } catch { reject(records, error: error, http: http, status: -1, retryAfter: nil) }
    }
    private func refresh(_ http: TrackingJSON, completion: @escaping (Renewal) -> Void) {
        let auth = http.object("authorization")
        guard let url = URL(string: auth.text("refreshUrl")), ["http", "https"].contains(url.scheme ?? "") else { emit("authorization", ["success": false, "status": 0, "error": "Token refresh is not configured"]); completion(.failed(TrackingError("HTTP_ERROR", "Token refresh is not configured"))); return }
        var payload = auth.object("refreshPayload")
        for (key, value) in payload { if let text = value as? String { payload[key] = text.replacingOccurrences(of: "{refreshToken}", with: auth.text("refreshToken")) } }
        do {
            var headers = auth["refreshHeaders"] as? [String: String] ?? [:]
            let body: Data
            if auth.text("refreshPayloadEncoding", "json") == "form" {
                var components = URLComponents()
                components.queryItems = payload.map { URLQueryItem(name: $0.key, value: String(describing: $0.value)) }
                body = Data((components.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B").utf8)
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            } else { body = try jsonData(payload) }
            let captured = generation
            transport.send(url: url, method: "POST", headers: headers, body: body, timeout: http.number("timeout", 60000), responseLimit: nil) { result in
                self.queue.async {
                    guard captured == self.generation else { return }
                    guard UploadBackoff.authKey(http) == UploadBackoff.authKey(self.config.value.object("http")) else { completion(.changed); return }
                    let response = try? result.get()
                    let json = response.flatMap { try? jsonObject($0.body) }
                    var event: TrackingJSON = ["success": false, "status": response?.status ?? 0]
                    if let json { event["response"] = json }
                    do {
                        guard let response, let json else { throw TrackingError("HTTP_ERROR", "Token refresh failed") }
                        let access = json.text("accessToken", json.text("access_token"))
                        guard response.success, !access.isEmpty else { throw TrackingError("HTTP_ERROR", "Token refresh failed") }
                        var next = auth
                        next["accessToken"] = access
                        next["refreshToken"] = json.text("refreshToken", json.text("refresh_token", auth.text("refreshToken")))
                        var expires = json.number("expires", json.number("expires_at", -1))
                        if expires > 0 && expires < 1e12 { expires *= 1000 }
                        if expires <= 0 && json.number("expires_in") > 0 { expires = Date().timeIntervalSince1970 * 1000 + json.number("expires_in") * 1000 }
                        next["expires"] = expires
                        try self.config.apply(["http": ["authorization": next]], reset: false)
                        if expires > 0 && expires - Date().timeIntervalSince1970 * 1000 <= 60000 { self.shortLivedToken = access }
                        event["success"] = true
                        self.emit("authorization", event)
                        completion(.renewed(self.config.value.object("http")))
                    } catch {
                        event["error"] = "Token refresh failed"
                        self.emit("authorization", event)
                        completion(.failed(error))
                    }
                }
            }
        } catch {
            emit("authorization", ["success": false, "status": 0, "error": "Token refresh failed"])
            completion(.failed(error))
        }
    }
    private func credentialsChanged() {
        retryTimer?.cancel()
        let current = config.value.object("http")
        let hasReplacement = current["authorization"] is TrackingJSON || current.object("headers").keys.contains { $0.lowercased() == "authorization" }
        if hasReplacement {
            changedUntil = Date().addingTimeInterval(10)
            schedule(changedUntil!.timeIntervalSince1970 * 1000)
        }
        pendingAutomatic = false
        finish(.failure(TrackingError("HTTP_ERROR", "Authorization changed; records retained")))
    }
    private func reject(_ records: [TrackingJSON], error: Error, http: TrackingJSON, status: Int, retryAfter: Double?) {
        let usesJWT = !http.object("headers").keys.contains { $0.lowercased() == "authorization" } && !http.object("authorization").isEmpty
        if status != 0 && !(status == 401 && usesJWT) && (!priorityOnly || passFailure != nil) {
            if passFailure == nil { passFailure = (error, http, status, retryAfter) }
            if records.allSatisfy({ priority.contains($0.text("event")) }) { rejectedPriority.formUnion(records.map { $0.text("uuid") }) }
            priorityOnly = true
            next()
        } else { fail(error, http: http, status: status, retryAfter: retryAfter, authorizationFailure: status == 401 && usesJWT) }
    }
    private func fail(_ error: Error, http: TrackingJSON, status: Int, retryAfter: Double?, authorizationFailure: Bool? = nil) {
        let authFailure = authorizationFailure ?? (status == 401)
        failures += 1
        let delay = authFailure ? 900000 : UploadBackoff.serverWait(failures, random: Double.random(in: 0..<1), retryAfter: retryAfter, minimum: tracking ? http.number("syncInterval") * 1000 : 0)
        let at = Date().timeIntervalSince1970 * 1000 + delay
        do { try store.save("uploadWait", ["at": at, "reason": authFailure ? "authorization" : "server", "failures": failures, "authKey": UploadBackoff.authKey(http)]) }
        catch { finish(.failure(error)); return }
        schedule(at)
        finish(.failure(error))
    }
    private func schedule(_ at: Double) {
        retryTimer?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.automatic() }
        retryTimer = item
        queue.asyncAfter(deadline: .now() + max(0.1, at / 1000 - Date().timeIntervalSince1970), execute: item)
    }
    private func finish(_ result: Result<[TrackingJSON], Error>) {
        busy = false
        let waiting = callbacks; callbacks = []
        waiting.forEach { $0(result) }
        if pendingRecovery {
            if let wait = try? store.load("uploadWait"), wait.text("reason") == "server" { try? store.save("uploadWait", [:]); failures = 0 }
            pendingRecovery = false
        }
        let requested = pendingAutomatic
        pendingAutomatic = false
        if requested { queue.async { [weak self] in self?.automatic() } }
        else if case .success = result { queue.async { [weak self] in self?.automatic() } }
    }
}
