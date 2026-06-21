import Foundation
import CryptoKit
import UIKit

public struct OddsmakerOptions {
    public let apiKey: String
    public let endpoint: URL
    public let gameId: String
    public let environment: String
    public var deviceId: String?
    public var flushIntervalSec: TimeInterval = 5
    public var maxBatch: Int = 50
    public var maxQueueBytes: Int = 512_000
    public var sessionGapSec: TimeInterval = 30 * 60
    public var debug: Bool = false

    public init(apiKey: String, endpoint: URL, gameId: String, environment: String) {
        self.apiKey = apiKey
        self.endpoint = endpoint
        self.gameId = gameId
        self.environment = environment
    }
}

public final class Oddsmaker {
    public static let shared = Oddsmaker()
    private init() {}

    private var opts: OddsmakerOptions? = nil
    private var deviceId: String = ""
    private var userId: String? = nil
    private var userProps: [String: Any] = [:]
    private var sessionId: String? = nil
    private var lastActive: TimeInterval = Date().timeIntervalSince1970

    private var timer: Timer? = nil
    private var queue: [Event] = []
    private var queueBytes: Int = 0
    private let queueLock = NSLock()

    private var queuePath: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let o = self.opts
        return caches.appendingPathComponent("oddsmaker_queue_\(o?.gameId ?? "")_\(o?.environment ?? "")_\(deviceId).ndjson")
    }

    public func initSDK(_ options: OddsmakerOptions) {
        self.opts = options
        self.deviceId = options.deviceId ?? Self.loadOrCreateDeviceId(gameId: options.gameId, environment: options.environment)
        self.restoreQueue()
        self.lastActive = Date().timeIntervalSince1970
        self.ensureTimer()
        if options.debug { print("[Oddsmaker] init deviceId=\(deviceId) queued=\(queue.count)") }
        NotificationCenter.default.addObserver(self, selector: #selector(appDidEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(appWillEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    public func setUserId(_ id: String?) { self.userId = id }
    public func setUserProps(_ props: [String: Any]) {
        for (key, value) in props {
            self.userProps[key] = value
        }
    }

    public func track(_ name: String, props: [String: Any]? = nil) -> String {
        guard let o = opts else { return "" }
        let now = Date().timeIntervalSince1970
        rollSession(now)
        let event = buildEvent(name: name, props: props, revenueAmount: nil, revenueCurrency: nil, options: o, now: now)
        enqueue(event, now: now, maxQueueBytes: o.maxQueueBytes)
        return event.event_id
    }

    public func expose(_ exp: String, variant: String) -> String {
        return track("experiment_exposure", props: ["exp": exp, "variant": variant])
    }

    public func revenue(amount: Double, currency: String, props: [String: Any]? = nil) -> String {
        guard let o = opts else { return "" }
        let now = Date().timeIntervalSince1970
        rollSession(now)
        let event = buildEvent(name: "revenue", props: props, revenueAmount: amount, revenueCurrency: currency, options: o, now: now)
        enqueue(event, now: now, maxQueueBytes: o.maxQueueBytes)
        return event.event_id
    }

    public func flush() { DispatchQueue.global().async { self.flushOnce() } }
    public func shutdown() { timer?.invalidate(); timer = nil }

    private func ensureTimer() {
        guard timer == nil, let o = opts else { return }
        timer = Timer.scheduledTimer(withTimeInterval: o.flushIntervalSec, repeats: true) { [weak self] _ in
            self?.flush()
        }
    }

    private func flushOnce() {
        guard let o = opts else { return }
        var slice: [Event] = []
        queueLock.lock()
        if queue.isEmpty { queueLock.unlock(); return }
        let n = min(o.maxBatch, queue.count)
        slice = Array(queue.prefix(n))
        queue.removeFirst(n)
        recalcQueueBytesNoLock()
        persistQueueNoLock()
        queueLock.unlock()

        let ndjson = slice.map { $0.toJsonLine() }.joined(separator: "\n")
        guard let url = URL(string: "/v1/batch", relativeTo: o.endpoint) else {
            queueLock.lock()
            queue.insert(contentsOf: slice, at: 0)
            recalcQueueBytesNoLock()
            persistQueueNoLock()
            queueLock.unlock()
            return
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue(o.apiKey, forHTTPHeaderField: "x-api-key")
        req.setValue("application/x-ndjson", forHTTPHeaderField: "content-type")

        req.httpBody = Data(ndjson.utf8)

        let sem = DispatchSemaphore(value: 0)
        var success = false
        URLSession.shared.dataTask(with: req) { _, resp, err in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            success = err == nil && (200..<300).contains(code)
            if self.opts?.debug == true {
                print("[Oddsmaker] flush code=\(code) count=\(slice.count)")
            }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 15)
        if !success {
            queueLock.lock()
            queue.insert(contentsOf: slice, at: 0)
            recalcQueueBytesNoLock()
            persistQueueNoLock()
            queueLock.unlock()
        }
    }

    private func enqueue(_ event: Event, now: TimeInterval, maxQueueBytes: Int) {
        let est = event.estimateSize()
        queueLock.lock()
        queue.append(event)
        queueBytes += est
        lastActive = now
        let shouldFlush = queueBytes >= maxQueueBytes || queue.count >= (opts?.maxBatch ?? 50)
        persistQueueNoLock()
        queueLock.unlock()
        if opts?.debug == true { print("[Oddsmaker] queued \(event.event_id) \(event.event_name)") }
        if shouldFlush { flush() }
    }

    private func buildEvent(name: String,
                            props: [String: Any]?,
                            revenueAmount: Double?,
                            revenueCurrency: String?,
                            options: OddsmakerOptions,
                            now: TimeInterval) -> Event {
        var merged = userProps
        if let props = props {
            for (key, value) in props {
                merged[key] = value
            }
        }
        if let amount = revenueAmount { merged["amount"] = amount }
        if let currency = revenueCurrency { merged["currency"] = currency }
        return Event(
            event_id: Self.uuidv7(),
            game_id: options.gameId,
            environment: options.environment,
            event_type: Self.inferEventType(name),
            event_name: name,
            user_id: userId,
            device_id: deviceId,
            session_id: sessionId,
            ts_client: Int64((now * 1000.0).rounded()),
            platform: "ios",
            app_version: Bundle.main.infoDictionary?[(kCFBundleVersionKey as String)] as? String,
            country: nil,
            revenue_amount: revenueAmount,
            revenue_currency: revenueCurrency,
            props: merged.isEmpty ? nil : merged
        )
    }

    private func rollSession(_ now: TimeInterval) {
        guard let o = opts else { return }
        if sessionId == nil || now - lastActive > o.sessionGapSec {
            sessionId = Self.uuidv7()
        }
    }

    @objc private func appDidEnterBackground() { flush() }
    @objc private func appWillEnterForeground() { lastActive = Date().timeIntervalSince1970 }

    private func persistQueueNoLock() {
        do {
            let s = queue.map { $0.toJsonLine() }.joined(separator: "\n")
            try s.write(to: queuePath, atomically: true, encoding: .utf8)
        } catch {}
    }

    private func restoreQueue() {
        do {
            let s = try String(contentsOf: queuePath)
            let restored = s.split(separator: "\n")
                .compactMap { Event.fromJsonLine(String($0)) }
            queueLock.lock()
            queue = restored
            recalcQueueBytesNoLock()
            queueLock.unlock()
        } catch {}
    }

    private func recalcQueueBytesNoLock() {
        queueBytes = queue.reduce(0) { $0 + $1.estimateSize() }
    }

    private static func loadOrCreateDeviceId(gameId: String, environment: String) -> String {
        let key = "oddsmaker_device_id_\(gameId)_\(environment)"
        if let existing = UserDefaults.standard.string(forKey: key), !existing.isEmpty { return existing }
        let idfv = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        let hashed = "d_" + String(SHA1hex(idfv).prefix(24))
        UserDefaults.standard.set(hashed, forKey: key)
        return hashed
    }

    private static func SHA1hex(_ s: String) -> String {
        let data = Data(s.utf8)
        var ctx = Insecure.SHA1()
        ctx.update(data: data)
        let digest = ctx.finalize()
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func uuidv7() -> String {
        let ms = UInt64(Date().timeIntervalSince1970 * 1000)
        let ts = String(ms, radix: 16).leftPadding(toLength: 12, withPad: "0")
        let rnd = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let hex = ts + rnd.prefix(20)
        return String(format: "%@-%@-7%@-%@-%@",
                      String(hex.prefix(8)),
                      String(hex.dropFirst(8).prefix(4)),
                      String(hex.dropFirst(13).prefix(3)),
                      String(hex.dropFirst(16).prefix(4)),
                      String(hex.dropFirst(20).prefix(12)))
    }

    private static func inferEventType(_ eventName: String) -> String {
        let name = eventName.lowercased()
        if name.contains("risk") || name.contains("fraud") { return "risk" }
        if name.contains("experiment") { return "experiment" }
        if name.contains("ad_") { return "ad" }
        if name.contains("level") || name.contains("quest") { return "progression" }
        if name.contains("session") { return "session" }
        if name.contains("error") || name.contains("crash") { return "error" }
        return "business"
    }

    private struct Event {
        let event_id: String
        let game_id: String
        let environment: String
        let event_type: String
        let event_name: String
        let user_id: String?
        let device_id: String
        let session_id: String?
        let ts_client: Int64
        let platform: String?
        let app_version: String?
        let country: String?
        let revenue_amount: Double?
        let revenue_currency: String?
        let props: [String: Any]?

        func toJsonLine() -> String { Self.toJson(self) }
        func estimateSize() -> Int { Self.toJson(self).utf8.count + 1 }

        private static func toJson(_ e: Event) -> String {
            var a: [String] = []
            func f(_ k: String, _ v: String) { a.append("\"\(k)\":\"\(escape(v))\"") }
            func n(_ k: String, _ v: Int64) { a.append("\"\(k)\":\(v)") }
            func d(_ k: String, _ v: Double) { a.append("\"\(k)\":\(v)") }
            func o(_ k: String, _ m: [String: Any]) { a.append("\"\(k)\":\(mapToJson(m))") }

            f("event_id", e.event_id)
            f("game_id", e.game_id)
            f("environment", e.environment)
            f("event_type", e.event_type)
            f("event_name", e.event_name)
            if let u = e.user_id { f("user_id", u) }
            f("device_id", e.device_id)
            if let s = e.session_id { f("session_id", s) }
            n("ts_client", e.ts_client)
            if let p = e.platform { f("platform", p) }
            if let v = e.app_version { f("app_version", v) }
            if let c = e.country { f("country", c) }
            if let amount = e.revenue_amount { d("revenue_amount", amount) }
            if let currency = e.revenue_currency { f("revenue_currency", currency) }
            if let pr = e.props, !pr.isEmpty { o("props", pr) }
            return "{" + a.joined(separator: ",") + "}"
        }

        static func fromJsonLine(_ line: String) -> Event? {
            guard let data = line.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let eventId = obj["event_id"] as? String,
                  let gameId = obj["game_id"] as? String,
                  let environment = obj["environment"] as? String,
                  let eventType = obj["event_type"] as? String,
                  let eventName = obj["event_name"] as? String,
                  let deviceId = obj["device_id"] as? String else {
                return nil
            }
            let tsClient: Int64
            if let n = obj["ts_client"] as? NSNumber {
                tsClient = n.int64Value
            } else if let s = obj["ts_client"] as? String, let n = Int64(s) {
                tsClient = n
            } else {
                return nil
            }
            return Event(
                event_id: eventId,
                game_id: gameId,
                environment: environment,
                event_type: eventType,
                event_name: eventName,
                user_id: obj["user_id"] as? String,
                device_id: deviceId,
                session_id: obj["session_id"] as? String,
                ts_client: tsClient,
                platform: obj["platform"] as? String,
                app_version: obj["app_version"] as? String,
                country: obj["country"] as? String,
                revenue_amount: (obj["revenue_amount"] as? NSNumber)?.doubleValue,
                revenue_currency: obj["revenue_currency"] as? String,
                props: obj["props"] as? [String: Any]
            )
        }

        private static func escape(_ s: String) -> String {
            var out = ""
            for scalar in s.unicodeScalars {
                switch scalar {
                case "\\":
                    out += "\\\\"
                case "\"":
                    out += "\\\""
                case "\n":
                    out += "\\n"
                case "\r":
                    out += "\\r"
                case "\t":
                    out += "\\t"
                default:
                    if scalar.value < 0x20 {
                        out += String(format: "\\u%04x", scalar.value)
                    } else {
                        out.unicodeScalars.append(scalar)
                    }
                }
            }
            return out
        }

        private static func mapToJson(_ m: [String: Any], depth: Int = 0) -> String {
            if depth > 2 { return "{}" }
            var parts: [String] = []
            var count = 0
            for (k, v) in m {
                if count >= 50 { break }
                parts.append("\"\(escape(k))\":\(valueToJson(v, depth: depth + 1))")
                count += 1
            }
            return "{" + parts.joined(separator: ",") + "}"
        }

        private static func valueToJson(_ v: Any, depth: Int) -> String {
            if depth > 2 { return "null" }
            switch v {
            case is NSNull: return "null"
            case let s as String: return "\"\(escape(s))\""
            case let b as Bool: return b ? "true" : "false"
            case let n as Int: return String(n)
            case let n as Int64: return String(n)
            case let n as Double: return String(n)
            case let n as Float: return String(n)
            case let d as [String: Any]: return mapToJson(d, depth: depth)
            case let arr as [Any]: return arrayToJson(arr, depth: depth)
            default: return "\"\(escape(String(describing: v)))\""
            }
        }

        private static func arrayToJson(_ arr: [Any], depth: Int) -> String {
            if depth > 2 { return "[]" }
            var out: [String] = []
            let lim = min(50, arr.count)
            for i in 0..<lim {
                out.append(valueToJson(arr[i], depth: depth + 1))
            }
            return "[" + out.joined(separator: ",") + "]"
        }
    }

    private static func hash32(_ s: String) -> UInt32 {
        var h: UInt32 = 0x811c9dc5
        for b in s.utf8 {
            h ^= UInt32(b)
            h &+= (h << 1) &+ (h << 4) &+ (h << 7) &+ (h << 8) &+ (h << 24)
        }
        return h
    }

    public static func assignVariant(expId: String, salt: String?, variants: [(name: String, weight: Int)], key: String) -> String {
        if variants.isEmpty { return "A" }
        let sum = max(1, variants.reduce(0) { $0 + max(1, $1.weight) })
        let h = Int(hash32("\(expId):\(salt ?? ""):\(key)") % UInt32(sum))
        var acc = 0
        for v in variants {
            acc += max(1, v.weight)
            if h < acc { return v.name }
        }
        return variants[0].name
    }

    public func fetchExperiments(controlURL: URL, gameId: String, environment: String, completion: @escaping (Result<Data, Error>) -> Void) {
        var u = controlURL
        u.appendPathComponent("api")
        u.appendPathComponent("config")
        u.appendPathComponent(gameId)
        u.appendPathComponent(environment)
        var req = URLRequest(url: u)
        req.httpMethod = "GET"
        req.setValue("application/json", forHTTPHeaderField: "accept")
        URLSession.shared.dataTask(with: req) { data, _, err in
            if let e = err { completion(.failure(e)); return }
            completion(.success(data ?? Data()))
        }.resume()
    }

    private func expsKey(_ gameId: String, _ environment: String) -> String {
        "oddsmaker_experiments_\(gameId)_\(environment)"
    }

    public func getCachedExperiments(_ gameId: String, _ environment: String) -> Data? {
        if let s = UserDefaults.standard.string(forKey: expsKey(gameId, environment)) {
            let parts = s.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
            if parts.count == 2 { return Data(String(parts[1]).utf8) }
        }
        return nil
    }

    public func fetchExperimentsCached(controlURL: URL, gameId: String, environment: String, ttlSec: TimeInterval = 300, completion: @escaping (Data) -> Void) {
        let now = Date().timeIntervalSince1970
        if let s = UserDefaults.standard.string(forKey: expsKey(gameId, environment)) {
            let parts = s.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
            if parts.count == 2, let ts = TimeInterval(parts[0]), now - ts < ttlSec {
                completion(Data(String(parts[1]).utf8))
                self.fetchExperiments(controlURL: controlURL, gameId: gameId, environment: environment) { res in
                    if case .success(let data) = res {
                        let entry = String(Int(Date().timeIntervalSince1970)) + "\n" + (String(data: data, encoding: .utf8) ?? "")
                        UserDefaults.standard.set(entry, forKey: self.expsKey(gameId, environment))
                    }
                }
                return
            }
        }
        self.fetchExperiments(controlURL: controlURL, gameId: gameId, environment: environment) { res in
            if case .success(let data) = res {
                UserDefaults.standard.set(
                    String(Int(now)) + "\n" + (String(data: data, encoding: .utf8) ?? ""),
                    forKey: self.expsKey(gameId, environment)
                )
                completion(data)
            } else {
                completion(Data())
            }
        }
    }

    @discardableResult
    public func startExperimentsAutoRefresh(controlURL: URL, gameId: String, environment: String, intervalSec: TimeInterval = 300, onUpdate: @escaping (Data) -> Void) -> Timer {
        let t = Timer.scheduledTimer(withTimeInterval: intervalSec, repeats: true) { _ in
            self.fetchExperiments(controlURL: controlURL, gameId: gameId, environment: environment) { res in
                if case .success(let data) = res {
                    UserDefaults.standard.set(
                        String(Int(Date().timeIntervalSince1970)) + "\n" + (String(data: data, encoding: .utf8) ?? ""),
                        forKey: self.expsKey(gameId, environment)
                    )
                    onUpdate(data)
                }
            }
        }
        t.fire()
        return t
    }
}

fileprivate extension String {
    func leftPadding(toLength: Int, withPad: String) -> String {
        if count < toLength {
            return String(repeatElement(Character(withPad), count: toLength - count)) + self
        }
        return self
    }
}
