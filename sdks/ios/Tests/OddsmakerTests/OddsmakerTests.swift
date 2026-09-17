import Foundation
import XCTest
@testable import Oddsmaker

/// Oddsmaker 单例 SDK 驱动测试:反复 initSDK + 重置队列文件/UserDefaults。
/// 断言一律走 track → 持久化队列文件 → JSON 回读(不依赖内部状态)。
final class OddsmakerTests: XCTestCase {

    private let sdk = Oddsmaker.shared
    private var errors: [OddsmakerError] = []

    override func setUp() {
        super.setUp()
        TestServer.shared.reset()
        sdk.shutdown()
        errors = []
        // 把 ~/.cache 下本测试的队列文件清空(而非删除):空文件让 initSDK 的
        // restoreQueue 走成功路径,把单例内存队列也重置为空,避免跨用例串扰。
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        var queueNames = ((try? FileManager.default.contentsOfDirectory(atPath: caches.path)) ?? [])
            .filter { $0.hasPrefix("oddsmaker_queue_game1_") }
        queueNames.append(contentsOf: [
            "oddsmaker_queue_game1_prod_dev-test.ndjson",
            "oddsmaker_queue_game1_prod_dev-saved.ndjson",
        ])
        for name in Set(queueNames) {
            try? "".write(to: caches.appendingPathComponent(name), atomically: true, encoding: .utf8)
        }
        for k in ["oddsmaker_device_id_game1_prod", "oddsmaker_experiments_game1_prod"] {
            UserDefaults.standard.removeObject(forKey: k)
        }
    }

    override func tearDown() {
        sdk.shutdown()
        super.tearDown()
    }

    // ---------- helper ----------

    private func makeOpts(endpoint: String = "https://ing.example/",
                          deviceId: String? = "dev-test",
                          flushIntervalSec: TimeInterval = 3600,
                          maxBatch: Int = 50,
                          maxQueueBytes: Int = 512_000,
                          sessionGapSec: TimeInterval = 30 * 60,
                          debug: Bool = false,
                          onError: ((OddsmakerError) -> Void)? = nil) -> OddsmakerOptions {
        var o = OddsmakerOptions(apiKey: "key-1",
                                 endpoint: URL(string: endpoint)!,
                                 gameId: "game1",
                                 environment: "prod")
        o.deviceId = deviceId
        o.flushIntervalSec = flushIntervalSec
        o.maxBatch = maxBatch
        o.maxQueueBytes = maxQueueBytes
        o.sessionGapSec = sessionGapSec
        o.debug = debug
        o.onError = onError ?? { [weak self] in self?.errors.append($0) }
        return o
    }

    private func initSdk(_ opts: OddsmakerOptions? = nil) {
        sdk.initSDK(opts ?? makeOpts())
    }

    private func queueFile(deviceId: String = "dev-test") -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("oddsmaker_queue_game1_prod_\(deviceId).ndjson")
    }

    private func seedQueue(_ lines: [String], deviceId: String = "dev-test") {
        try! lines.joined(separator: "\n").write(to: queueFile(deviceId: deviceId),
                                                 atomically: true, encoding: .utf8)
    }

    /// 读持久化队列文件,逐行 JSON 解析。
    private func queueEvents(deviceId: String = "dev-test") -> [[String: Any]] {
        guard let text = try? String(contentsOf: queueFile(deviceId: deviceId), encoding: .utf8) else { return [] }
        return text.split(separator: "\n").compactMap {
            (try? JSONSerialization.jsonObject(with: Data(String($0).utf8))) as? [String: Any]
        }
    }

    private func rawQueue(deviceId: String = "dev-test") -> String {
        (try? String(contentsOf: queueFile(deviceId: deviceId), encoding: .utf8)) ?? ""
    }

    // ---------- deviceId 链 ----------

    func testDeviceIdExplicitSkipsStorage() {
        initSdk()
        XCTAssertNil(UserDefaults.standard.string(forKey: "oddsmaker_device_id_game1_prod"))
        XCTAssertEqual(queueEvents().count, 0)   // 尚未 track,空队列
        sdk.track("x")
        XCTAssertEqual(queueEvents()[0]["device_id"] as? String, "dev-test")
    }

    func testDeviceIdRestoresFromDefaults() {
        UserDefaults.standard.set("dev-saved", forKey: "oddsmaker_device_id_game1_prod")
        sdk.initSDK(makeOpts(deviceId: nil))
        sdk.track("x")
        XCTAssertEqual(queueEvents(deviceId: "dev-saved")[0]["device_id"] as? String, "dev-saved")
    }

    func testDeviceIdGeneratesHashedAndReuses() {
        sdk.initSDK(makeOpts(deviceId: nil))
        let generated = UserDefaults.standard.string(forKey: "oddsmaker_device_id_game1_prod")!
        XCTAssertTrue(generated.range(of: "^d_[0-9a-f]{24}$", options: .regularExpression) != nil, generated)

        // 生成路径的队列文件 setUp 无法预写(随机名):先写空文件再 initSDK,
        // 让 restoreQueue 走成功路径清空内存队列(同时覆盖已存 id 的复用分支)
        try! "".write(to: queueFile(deviceId: generated), atomically: true, encoding: .utf8)
        sdk.initSDK(makeOpts(deviceId: nil))
        XCTAssertEqual(UserDefaults.standard.string(forKey: "oddsmaker_device_id_game1_prod"), generated)
        sdk.track("gen")
        XCTAssertEqual(queueEvents(deviceId: generated)[0]["device_id"] as? String, generated)
    }

    // ---------- 队列恢复 ----------

    func testRestoreQueueKeepsValidDropsCorruptAndIncomplete() {
        let valid = #"{"event_id":"e-ok","game_id":"game1","environment":"prod","event_type":"business","event_name":"buy","device_id":"dev-test","ts_client":9}"#
        let missingField = #"{"game_id":"game1","event_name":"x","device_id":"d","ts_client":1}"#
        seedQueue(["{not json", "", valid, missingField])
        initSdk()
        sdk.track("fresh")
        let events = queueEvents()
        XCTAssertEqual(events.count, 2)   // 坏行/空行/缺字段丢弃,好行 + 新事件
        XCTAssertEqual(events[0]["event_id"] as? String, "e-ok")
        XCTAssertTrue(events[1]["event_id"] as? String != "e-ok")
    }

    func testRestoreQueueParsesStringTimestampAndOptionalFields() {
        // ts_client 字符串数字 + 全可选字段(user_id/session_id/platform/app_version/country/revenue)
        let line = #"{"event_id":"e-full","game_id":"game1","environment":"prod","event_type":"business","event_name":"buy","user_id":"u9","device_id":"dev-test","session_id":"s9","ts_client":"123","platform":"ios","app_version":"9.9","country":"CN","revenue_amount":9.99,"revenue_currency":"USD","props":{"k":"v"}}"#
        seedQueue([line])
        initSdk()
        sdk.track("trigger-rewrite")   // 触发重写持久化,恢复事件的字段原样输出
        let events = queueEvents()
        XCTAssertEqual(events[0]["event_id"] as? String, "e-full")
        XCTAssertEqual(events[0]["ts_client"] as? Int64, 123)   // 字符串时间戳被解析
        XCTAssertEqual(events[0]["user_id"] as? String, "u9")
        XCTAssertEqual(events[0]["session_id"] as? String, "s9")
        XCTAssertEqual(events[0]["app_version"] as? String, "9.9")
        XCTAssertEqual(events[0]["country"] as? String, "CN")
        XCTAssertEqual((events[0]["revenue_amount"] as? NSNumber)?.doubleValue ?? 0, 9.99, accuracy: 1e-9)
        XCTAssertEqual(events[0]["revenue_currency"] as? String, "USD")
        XCTAssertEqual((events[0]["props"] as? [String: Any])?["k"] as? String, "v")
    }

    func testRestoreQueueRejectsBadTimestamp() {
        let line = #"{"event_id":"e-badts","game_id":"game1","environment":"prod","event_type":"b","event_name":"n","device_id":"dev-test","ts_client":"abc"}"#
        seedQueue([line])
        initSdk()
        sdk.track("x")
        XCTAssertEqual(queueEvents().count, 1)   // 坏 ts 行被丢弃
    }

    func testQueuePathIsDirectoryToleratedOnRestoreAndPersist() {
        // 队列路径被目录占位:restoreQueue 读抛、persistQueue 写抛,均被吞掉
        try! FileManager.default.createDirectory(at: queueFile(deviceId: "dev-dir"),
                                                 withIntermediateDirectories: true)
        sdk.initSDK(makeOpts(deviceId: "dev-dir"))
        let id = sdk.track("still ok")
        XCTAssertFalse(id.isEmpty)
    }

    // ---------- track / identify / expose / revenue ----------

    func testTrackCarriesCoreFieldsAndMergedProps() {
        initSdk()
        sdk.setUserId("u1")
        sdk.setUserProps(["tier": "gold"])
        sdk.setPlayer("p9")
        let id = sdk.track("shop_buy", props: ["n": 3])
        XCTAssertTrue(id.range(of: "^[0-9a-fA-F-]{36}$", options: .regularExpression) != nil, id)   // rnd 段来自 UUID().uuidString(大写)
        // uuidv7 布局:version 位 '7',时间戳前 12 hex 接近当前毫秒
        let parts = id.split(separator: "-").map(String.init)
        XCTAssertEqual(parts.map { $0.count }, [8, 4, 4, 4, 12])
        XCTAssertEqual(parts[2].first, "7")
        let ts = UInt64(parts[0] + parts[1], radix: 16) ?? 0
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        XCTAssertLessThan(abs(Int64(ts) - Int64(now)), 10_000)   // 10 秒容差

        let e = queueEvents()[0]
        XCTAssertEqual(e["game_id"] as? String, "game1")
        XCTAssertEqual(e["environment"] as? String, "prod")
        XCTAssertEqual(e["event_type"] as? String, "business")
        XCTAssertEqual(e["event_name"] as? String, "shop_buy")
        XCTAssertEqual(e["user_id"] as? String, "u1")
        XCTAssertEqual(e["device_id"] as? String, "dev-test")
        XCTAssertEqual(e["platform"] as? String, "ios")
        XCTAssertNotNil(e["session_id"])
        XCTAssertTrue((e["ts_client"] as? Int64) ?? 0 > 1_000_000_000_000)
        let props = e["props"] as? [String: Any]
        XCTAssertEqual(props?["tier"] as? String, "gold")
        XCTAssertEqual(props?["player_id"] as? String, "p9")
        XCTAssertEqual(props?["n"] as? Int, 3)
    }

    func testIdentifyBranches() {
        initSdk()
        sdk.setUserId(nil)                    // 无 previous
        sdk.identify("u-a")
        sdk.setPlayer("p1")
        sdk.identify("u-b", props: ["tier": "gold"])   // previous = u-a
        sdk.identify("u-b")                   // 同 id → 无 previous
        let events = queueEvents()
        XCTAssertEqual(events.map { $0["event_name"] as? String },
                       ["$identify", "$identify", "$identify"])
        XCTAssertEqual(events.map { $0["event_type"] as? String },
                       ["identity", "identity", "identity"])
        XCTAssertEqual(events[0]["user_id"] as? String, "u-a")
        XCTAssertNil((events[0]["props"] as? [String: Any])?["previous_user_id"])
        let p1 = events[1]["props"] as? [String: Any]
        XCTAssertEqual(p1?["previous_user_id"] as? String, "u-a")
        XCTAssertEqual(p1?["new_user_id"] as? String, "u-b")
        XCTAssertEqual(p1?["$identify"] as? Bool, true)
        XCTAssertEqual(p1?["player_id"] as? String, "p1")
        XCTAssertEqual(p1?["tier"] as? String, "gold")
        XCTAssertNil((events[2]["props"] as? [String: Any])?["previous_user_id"])
    }

    func testExposeAndRevenue() {
        initSdk()
        sdk.expose("exp1", variant: "B")
        sdk.revenue(amount: 9.99, currency: "USD", props: ["order": "o1"])
        let events = queueEvents()
        XCTAssertEqual(events[0]["event_name"] as? String, "experiment_exposure")
        XCTAssertEqual(events[0]["event_type"] as? String, "experiment")
        XCTAssertEqual((events[0]["props"] as? [String: Any])?["variant"] as? String, "B")
        XCTAssertEqual((events[0]["props"] as? [String: Any])?["exp"] as? String, "exp1")

        XCTAssertEqual(events[1]["event_name"] as? String, "revenue")
        XCTAssertEqual((events[1]["revenue_amount"] as? NSNumber)?.doubleValue ?? 0, 9.99, accuracy: 1e-9)
        XCTAssertEqual(events[1]["revenue_currency"] as? String, "USD")
        let rp = events[1]["props"] as? [String: Any]
        XCTAssertEqual((rp?["amount"] as? NSNumber)?.doubleValue ?? 0, 9.99, accuracy: 1e-9)
        XCTAssertEqual(rp?["currency"] as? String, "USD")
        XCTAssertEqual(rp?["order"] as? String, "o1")
    }

    func testInferEventTypeCoversAllBranches() {
        initSdk()
        let names = ["$identify", "identity_login", "risk_hit", "fraud_check", "experiment_view",
                     "ad_click", "level_up", "quest_done", "session_start", "error_bad", "crash_now", "shop_buy"]
        names.forEach { sdk.track($0) }
        XCTAssertEqual(queueEvents().map { $0["event_type"] as? String },
                       ["identity", "identity", "risk", "risk", "experiment",
                        "ad", "progression", "progression", "session", "error", "error", "business"])
    }

    func testSessionStableWithinGapAndRotatesAfterGap() {
        initSdk()
        sdk.track("a")
        sdk.track("b")
        let s = queueEvents().map { $0["session_id"] as? String }
        XCTAssertEqual(s[0], s[1])   // 30 分钟间隙内同会话

        sdk.initSDK(makeOpts(sessionGapSec: 0))
        // 清空队列文件再 initSDK,让内存队列归零,第二轮断言只含 c/d
        try! "".write(to: queueFile(), atomically: true, encoding: .utf8)
        sdk.initSDK(makeOpts(sessionGapSec: 0))
        sdk.track("c")
        Thread.sleep(forTimeInterval: 0.02)
        sdk.track("d")
        let s2 = queueEvents().map { $0["session_id"] as? String }
        XCTAssertEqual(s2.count, 2)
        XCTAssertNotEqual(s2[0], s2[1])   // gap=0 → 轮换
    }

    // ---------- 序列化边角(经 track → 文件断言) ----------

    func testEscapeAllSpecialChars() {
        initSdk()
        let weird = "a\\b\"c\nd\re\tf\u{01}g"
        sdk.track(weird)
        XCTAssertEqual(queueEvents()[0]["event_name"] as? String, weird)   // JSON 回读还原
    }

    func testPropsValueBranchesAndLimits() {
        final class Gadget {}
        initSdk()
        sdk.track("values", props: [
            "nil": NSNull(),
            "bool": true,
            "int": 42,
            "i64": Int64(77),
            "dbl": 2.5,
            "flt": Float(1.25),
            "str": "s",
            "dict": ["in": "v"],
            "list": [1, "y"],
            "gadget": Gadget(),
            "deep": ["a": ["b": ["c": ["d": 1]]]],   // 第 4 层 → {}
            "deepList": [[[[1]]]],                    // 第 3 层起 → []
            "long": Array(0..<60).map { $0 }          // 50 截断
        ])
        let raw = rawQueue()
        let props = queueEvents()[0]["props"] as? [String: Any]
        XCTAssertTrue(props?["nil"] is NSNull)   // null 序列化回读
        XCTAssertEqual(props?["bool"] as? Bool, true)
        XCTAssertEqual(props?["int"] as? Int, 42)
        XCTAssertEqual(props?["i64"] as? Int, 77)
        XCTAssertEqual((props?["dbl"] as? NSNumber)?.doubleValue ?? 0, 2.5, accuracy: 1e-9)
        XCTAssertEqual((props?["flt"] as? NSNumber)?.doubleValue ?? 0, 1.25, accuracy: 1e-9)
        XCTAssertEqual(props?["str"] as? String, "s")
        XCTAssertEqual((props?["dict"] as? [String: Any])?["in"] as? String, "v")
        let list = props?["list"] as? [Any]
        XCTAssertEqual(list?.count, 2)
        XCTAssertEqual((list?[0] as? NSNumber)?.intValue, 1)
        XCTAssertEqual(list?[1] as? String, "y")
        XCTAssertTrue(raw.contains("\"gadget\":"), raw)   // default 分支:自定义对象 → String(describing:)
        // iOS 版 valueToJson 深层截断输出 "null"(android 版是 {}/[],口径不同)
        XCTAssertTrue(raw.contains("\"deep\":{\"a\":{\"b\":null}}"), raw)
        XCTAssertTrue(raw.contains("\"deepList\":[[null]]"), raw)
        let long = props?["long"] as? [Any]
        XCTAssertEqual(long?.count, 50)
        XCTAssertTrue(!raw.contains(",51,"))
        XCTAssertTrue(!raw.contains(",55,"))
    }

    // ---------- flush / 发送 ----------

    func testAutoFlushOnMaxBatchSendsNdjson() {
        TestServer.shared.enqueue(status: 200)
        sdk.initSDK(makeOpts(endpoint: TestServer.shared.url, maxBatch: 1))
        sdk.track("boot")
        waitUntil { TestServer.shared.requests.count == 1 }
        let req = TestServer.shared.requests[0]
        XCTAssertEqual(req["method"] as? String, "POST")
        XCTAssertEqual(req["path"] as? String, "/v1/batch")
        let headers = req["headers"] as? [String: String]
        XCTAssertEqual(headers?["x-api-key"], "key-1")
        XCTAssertEqual(headers?["content-type"], "application/x-ndjson")
        let body = req["body"] as? String ?? ""
        XCTAssertEqual((body.split(separator: "\n").compactMap {
            (try? JSONSerialization.jsonObject(with: Data(String($0).utf8))) as? [String: Any]
        }.first)?["event_name"] as? String, "boot")
        waitUntil { self.rawQueue().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        XCTAssertTrue(rawQueue().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)   // 发送成功队列清空
        XCTAssertTrue(errors.isEmpty)
    }

    func testAutoFlushOnQueueBytes() {
        TestServer.shared.enqueue(status: 200)
        sdk.initSDK(makeOpts(endpoint: TestServer.shared.url, maxQueueBytes: 1))
        sdk.track("big")
        waitUntil { TestServer.shared.requests.count == 1 }
    }

    func testFlushEmptyQueueSendsNothing() {
        initSdk()
        sdk.flush()
        Thread.sleep(forTimeInterval: 0.3)
        XCTAssertEqual(TestServer.shared.requests.count, 0)
    }

    func testTimerFlushesPeriodically() {
        TestServer.shared.enqueue(status: 200)
        // scheduledTimer 注册在 initSDK 调用线程(本测试线程)的 RunLoop 上:
        // 在同一线程跑 RunLoop 即可让定时器真实触发
        sdk.initSDK(makeOpts(endpoint: TestServer.shared.url, flushIntervalSec: 0.05))
        sdk.track("timer-flush")   // maxBatch 默认 50 → 只入队不立即 flush
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        waitUntil { TestServer.shared.requests.count >= 1 }
        XCTAssertEqual(TestServer.shared.requests.count, 1)   // 队列清空后后续 tick 是 no-op
        let body = TestServer.shared.requests[0]["body"] as? String ?? ""
        let events = body.split(separator: "\n").compactMap {
            (try? JSONSerialization.jsonObject(with: Data(String($0).utf8))) as? [String: Any]
        }
        XCTAssertEqual(events.first?["event_name"] as? String, "timer-flush")
    }

    func testFlushHttpFailureRestoresQueueAndReportsError() {
        TestServer.shared.enqueue(status: 500)
        sdk.initSDK(makeOpts(endpoint: TestServer.shared.url, maxBatch: 1))   // track 即触发 flush
        sdk.track("lost")
        waitUntil { TestServer.shared.requests.count == 1 }
        waitUntil { self.queueEvents().count == 1 }
        XCTAssertEqual(queueEvents()[0]["event_name"] as? String, "lost")
        XCTAssertEqual(errors.count, 1)
        if case .invalidResponse(let code, _)? = errors.first {
            XCTAssertEqual(code, 500)
        } else {
            XCTFail("expected invalidResponse, got \(errors)")
        }
    }

    func testFlushConnectionFailureRestoresQueue() {
        // 127.0.0.1:1 端口必然拒绝连接
        sdk.initSDK(makeOpts(endpoint: "http://127.0.0.1:1", maxBatch: 1))   // track 即触发 flush
        sdk.track("offline")
        waitUntil { self.queueEvents().count == 1 }
        XCTAssertEqual(queueEvents()[0]["event_name"] as? String, "offline")
        waitUntil { !self.errors.isEmpty }   // 还原写盘与错误回调几乎同时,补等
        XCTAssertFalse(errors.isEmpty)
    }

    func testDebugLoggingEnqueueAndFlushSuccess() {
        TestServer.shared.enqueue(status: 200)
        sdk.initSDK(makeOpts(endpoint: TestServer.shared.url, maxBatch: 1, debug: true))
        sdk.track("dbg")
        waitUntil { TestServer.shared.requests.count == 1 }
    }

    func testShutdownIsIdempotent() {
        initSdk()
        sdk.shutdown()
        sdk.shutdown()
    }

    // ---------- 实验配置 ----------

    private func waitForFetch(_ run: (@escaping (Result<Data, Error>) -> Void) -> Void) -> Result<Data, Error>? {
        var result: Result<Data, Error>?
        run { result = $0 }
        waitUntil { result != nil }
        return result
    }

    func testFetchExperimentsSuccessFailureAndConnectionError() {
        TestServer.shared.enqueue(status: 200, body: "[{\"id\":\"e1\"}]")
        TestServer.shared.enqueue(status: 500)
        initSdk()
        let control = URL(string: TestServer.shared.url)!

        let ok = waitForFetch {
            sdk.fetchExperiments(controlURL: control, gameId: "game1", environment: "prod", completion: $0)
        }
        guard case .success(let data)? = ok else { return XCTFail("expected success: \(String(describing: ok))") }
        XCTAssertEqual(String(data: data, encoding: .utf8), "[{\"id\":\"e1\"}]")
        XCTAssertEqual(TestServer.shared.requests.first?["path"] as? String, "/api/config/game1/prod")
        XCTAssertEqual(TestServer.shared.requests.first?["method"] as? String, "GET")

        let bad = waitForFetch {
            sdk.fetchExperiments(controlURL: control, gameId: "game1", environment: "prod", completion: $0)
        }
        guard case .failure(let err)? = bad else { return XCTFail("expected failure") }
        if case OddsmakerError.invalidResponse(500, _) = err {} else {
            XCTFail("expected invalidResponse(500), got \(err)")
        }

        let dead = waitForFetch {
            sdk.fetchExperiments(controlURL: URL(string: "http://127.0.0.1:1")!,
                                 gameId: "g", environment: "p", completion: $0)
        }
        guard case .failure = dead else { return XCTFail("expected failure on dead port") }
    }

    func testGetCachedExperimentsMissHitAndSingleSegment() {
        initSdk()
        XCTAssertNil(sdk.getCachedExperiments("game1", "prod"))
        UserDefaults.standard.set("\(Int(Date().timeIntervalSince1970))\n[{\"id\":\"c\"}]",
                                  forKey: "oddsmaker_experiments_game1_prod")
        XCTAssertEqual(String(data: sdk.getCachedExperiments("game1", "prod")!, encoding: .utf8),
                       "[{\"id\":\"c\"}]")
        UserDefaults.standard.set("single-segment", forKey: "oddsmaker_experiments_game1_prod")
        XCTAssertNil(sdk.getCachedExperiments("game1", "prod"))
    }

    func testFetchExperimentsCachedNoCacheFetchesAndStores() {
        TestServer.shared.enqueue(status: 200, body: "[{\"id\":\"fresh\"}]")
        initSdk()
        var got: Data?
        sdk.fetchExperimentsCached(controlURL: URL(string: TestServer.shared.url)!,
                                   gameId: "game1", environment: "prod") { got = $0 }
        waitUntil { got != nil }
        XCTAssertEqual(String(data: got!, encoding: .utf8), "[{\"id\":\"fresh\"}]")
        let stored = UserDefaults.standard.string(forKey: "oddsmaker_experiments_game1_prod")!
        XCTAssertTrue(stored.contains("\n"))
        XCTAssertTrue(stored.hasSuffix("[{\"id\":\"fresh\"}]"))
    }

    func testFetchExperimentsCachedWithinTtlReturnsCacheAndRefreshesInBackground() {
        TestServer.shared.enqueue(status: 200, body: "[{\"id\":\"bg\"}]")
        initSdk()
        UserDefaults.standard.set("\(Date().timeIntervalSince1970)\n[{\"id\":\"cached\"}]",
                                  forKey: "oddsmaker_experiments_game1_prod")
        var got: Data?
        sdk.fetchExperimentsCached(controlURL: URL(string: TestServer.shared.url)!,
                                   gameId: "game1", environment: "prod", ttlSec: 300) { got = $0 }
        waitUntil { got != nil }
        XCTAssertEqual(String(data: got!, encoding: .utf8), "[{\"id\":\"cached\"}]")   // TTL 内回缓存
        waitUntil {
            UserDefaults.standard.string(forKey: "oddsmaker_experiments_game1_prod")?.contains("bg") == true
        }
        XCTAssertTrue(UserDefaults.standard.string(forKey: "oddsmaker_experiments_game1_prod")!.contains("bg"))
    }

    func testFetchExperimentsCachedStaleOrBadTsRefetches() {
        TestServer.shared.enqueue(status: 200, body: "[{\"id\":\"refetched\"}]")
        TestServer.shared.enqueue(status: 200, body: "[{\"id\":\"ts-not-number\"}]")
        initSdk()
        let control = URL(string: TestServer.shared.url)!

        UserDefaults.standard.set("1000\n[{\"id\":\"old\"}]", forKey: "oddsmaker_experiments_game1_prod")
        var got: Data?
        sdk.fetchExperimentsCached(controlURL: control, gameId: "game1", environment: "prod") { got = $0 }
        waitUntil { got != nil }
        XCTAssertEqual(String(data: got!, encoding: .utf8), "[{\"id\":\"refetched\"}]")

        UserDefaults.standard.set("abc\n[{\"id\":\"old\"}]", forKey: "oddsmaker_experiments_game1_prod")
        var got2: Data?
        sdk.fetchExperimentsCached(controlURL: control, gameId: "game1", environment: "prod") { got2 = $0 }
        waitUntil { got2 != nil }
        XCTAssertEqual(String(data: got2!, encoding: .utf8), "[{\"id\":\"ts-not-number\"}]")
    }

    func testFetchExperimentsCachedFailureCompletesWithEmptyData() {
        sdk.initSDK(makeOpts(endpoint: "http://127.0.0.1:1"))
        var got: Data?
        sdk.fetchExperimentsCached(controlURL: URL(string: "http://127.0.0.1:1")!,
                                   gameId: "game1", environment: "prod") { got = $0 }
        waitUntil { got != nil }
        XCTAssertEqual(got!, Data())   // 失败兜底空数据
    }

    func testAutoRefreshFiresImmediatelyAndSkipsCallbackOnFailure() {
        TestServer.shared.enqueue(status: 200, body: "[{\"id\":\"t1\"}]")
        TestServer.shared.enqueue(status: 500)   // 第二次 fire → 失败 → onUpdate 不调
        initSdk()
        var updates: [Data] = []
        let t = sdk.startExperimentsAutoRefresh(controlURL: URL(string: TestServer.shared.url)!,
                                                gameId: "game1", environment: "prod",
                                                intervalSec: 3600) { updates.append($0) }
        waitUntil { TestServer.shared.requests.count >= 1 }
        waitUntil {
            UserDefaults.standard.string(forKey: "oddsmaker_experiments_game1_prod")?.contains("t1") == true
        }
        waitUntil { updates.count == 1 }
        XCTAssertEqual(String(data: updates[0], encoding: .utf8), "[{\"id\":\"t1\"}]")

        t.fire()   // 手动触发第二次 → 500
        waitUntil { TestServer.shared.requests.count >= 2 }
        Thread.sleep(forTimeInterval: 0.3)
        XCTAssertEqual(updates.count, 1)   // 失败不回调
        t.invalidate()
    }

    func testAssignVariantRules() {
        XCTAssertEqual(Oddsmaker.assignVariant(expId: "e", salt: "s", variants: [], key: "k"), "A")
        XCTAssertEqual(Oddsmaker.assignVariant(expId: "e", salt: "s", variants: [("B", 0)], key: "k"),
                       "B")   // weight<=0 按 1
        let variants: [(name: String, weight: Int)] = [("A", 50), ("B", 50)]
        let pick = Oddsmaker.assignVariant(expId: "exp", salt: "s", variants: variants, key: "u1")
        XCTAssertTrue(["A", "B"].contains(pick))
        XCTAssertEqual(pick, Oddsmaker.assignVariant(expId: "exp", salt: "s", variants: variants, key: "u1"))
        let saltless = Oddsmaker.assignVariant(expId: "exp", salt: nil, variants: variants, key: "u1")
        XCTAssertTrue(["A", "B"].contains(saltless))
    }
}
