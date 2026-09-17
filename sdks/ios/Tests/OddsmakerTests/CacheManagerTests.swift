import Foundation
import XCTest
@testable import Oddsmaker

/// CacheManager 直测(独立 suite UserDefaults,不碰 Oddsmaker 单例)。
final class CacheManagerTests: XCTestCase {

    private var ud: UserDefaults!
    private var cm: CacheManager!

    override func setUp() {
        super.setUp()
        ud = UserDefaults(suiteName: "cache-manager-tests")
        for k in ud.dictionaryRepresentation().keys { ud.removeObject(forKey: k) }
        cm = CacheManager(userDefaults: ud)
    }

    // ---------- 实验配置缓存 ----------

    func testExperimentsRoundTripAndTtl() {
        XCTAssertNil(cm.getCachedExperiments(gameId: "g1", environment: "prod"))   // 无缓存

        let fresh = Date().timeIntervalSince1970
        ud.set("\(Int(fresh))\n[{\"id\":\"e1\"}]", forKey: "oddsmaker_experiments_g1_prod")
        XCTAssertEqual(String(data: cm.getCachedExperiments(gameId: "g1", environment: "prod")!, encoding: .utf8)!,
                       "[{\"id\":\"e1\"}]")   // TTL 内命中

        ud.set("\(Int(fresh - 400))\nold", forKey: "oddsmaker_experiments_g1_prod")
        XCTAssertNil(cm.getCachedExperiments(gameId: "g1", environment: "prod", ttlSec: 300))   // 过期

        ud.set("abc\nold", forKey: "oddsmaker_experiments_g1_prod")
        XCTAssertNil(cm.getCachedExperiments(gameId: "g1", environment: "prod"))   // 坏时间戳

        ud.set("single-segment", forKey: "oddsmaker_experiments_g1_prod")
        XCTAssertNil(cm.getCachedExperiments(gameId: "g1", environment: "prod"))   // 无换行分隔
    }

    func testCacheAndClearExperiments() {
        cm.cacheExperiments(Data("[{\"id\":\"x\"}]".utf8), gameId: "g1", environment: "prod")
        let stored = ud.string(forKey: "oddsmaker_experiments_g1_prod")!
        XCTAssertTrue(stored.contains("\n"), stored)
        let nl = stored.firstIndex(of: "\n")!
        XCTAssertEqual(String(stored[stored.index(after: nl)...]), "[{\"id\":\"x\"}]")
        XCTAssertTrue(stored[..<nl].count >= 10)   // 时间戳(秒级 10 位)

        cm.clearExperimentsCache(gameId: "g1", environment: "prod")
        XCTAssertNil(ud.string(forKey: "oddsmaker_experiments_g1_prod"))
    }

    // ---------- 用户属性缓存 ----------

    func testUserPropsRoundTripAndCorrupt() {
        XCTAssertNil(cm.getCachedUserProps(userId: "u1"))
        cm.cacheUserProps(["tier": "gold", "level": 7, "nested": ["a": 1]], userId: "u1")
        let props = cm.getCachedUserProps(userId: "u1")!
        XCTAssertEqual(props["tier"] as? String, "gold")
        XCTAssertEqual(props["level"] as? Int, 7)
        XCTAssertEqual((props["nested"] as? [String: Any])?["a"] as? Int, 1)

        ud.set("{not json", forKey: "oddsmaker_user_props_u1")
        XCTAssertNil(cm.getCachedUserProps(userId: "u1"))   // 坏 JSON

        cm.cacheUserProps(["ok": 1], userId: "u1")
        cm.clearUserPropsCache(userId: "u1")
        XCTAssertNil(cm.getCachedUserProps(userId: "u1"))
    }

    // ---------- 会话缓存 ----------

    func testSessionRoundTripExpiryAndCorrupt() {
        XCTAssertNil(cm.getCachedSession(gameId: "g1", environment: "prod"))

        let start = Date().timeIntervalSince1970
        cm.cacheSession("sess-1", startTime: start, gameId: "g1", environment: "prod")
        let s = cm.getCachedSession(gameId: "g1", environment: "prod")!
        XCTAssertEqual(s.sessionId, "sess-1")
        XCTAssertEqual(s.startTime, start, accuracy: 0.001)

        cm.cacheSession("sess-old", startTime: start - 4000, gameId: "g1", environment: "prod")
        XCTAssertNil(cm.getCachedSession(gameId: "g1", environment: "prod", maxAgeSec: 300))   // 过期

        ud.set("only-id", forKey: "oddsmaker_session_g1_prod")
        XCTAssertNil(cm.getCachedSession(gameId: "g1", environment: "prod"))   // 单段

        ud.set("sess-x\nabc", forKey: "oddsmaker_session_g1_prod")
        XCTAssertNil(cm.getCachedSession(gameId: "g1", environment: "prod"))   // 坏时间戳

        cm.cacheSession("sess-2", startTime: start, gameId: "g1", environment: "prod")
        cm.clearSessionCache(gameId: "g1", environment: "prod")
        XCTAssertNil(cm.getCachedSession(gameId: "g1", environment: "prod"))
    }

    // ---------- 设备ID缓存 ----------

    func testDeviceIdRoundTrip() {
        XCTAssertNil(cm.getCachedDeviceId(gameId: "g1", environment: "prod"))
        cm.cacheDeviceId("dev-9", gameId: "g1", environment: "prod")
        XCTAssertEqual(cm.getCachedDeviceId(gameId: "g1", environment: "prod"), "dev-9")
        XCTAssertEqual(ud.string(forKey: "oddsmaker_device_id_g1_prod"), "dev-9")
    }

    // ---------- 通用缓存 ----------

    func testGenericCacheWithoutTtl() {
        XCTAssertNil(cm.getCachedData(forKey: "k"))
        cm.cacheData(Data("hello".utf8), forKey: "k")
        XCTAssertEqual(String(data: cm.getCachedData(forKey: "k")!, encoding: .utf8), "hello")
        XCTAssertEqual(ud.string(forKey: "oddsmaker_k"), "hello")   // 无 ttl 直存

        cm.removeCache(forKey: "k")
        XCTAssertNil(cm.getCachedData(forKey: "k"))
    }

    func testGenericCacheWithTtl() {
        let fresh = Date().timeIntervalSince1970
        ud.set("\(Int(fresh))\nfresh-body", forKey: "oddsmaker_kt")
        XCTAssertEqual(String(data: cm.getCachedData(forKey: "kt", ttlSec: 300)!, encoding: .utf8), "fresh-body")

        ud.set("\(Int(fresh - 400))\nstale", forKey: "oddsmaker_kt")
        XCTAssertNil(cm.getCachedData(forKey: "kt", ttlSec: 300))   // 过期

        ud.set("abc\nstale", forKey: "oddsmaker_kt")
        XCTAssertNil(cm.getCachedData(forKey: "kt", ttlSec: 300))   // 坏时间戳

        ud.set("one-segment", forKey: "oddsmaker_kt")
        XCTAssertNil(cm.getCachedData(forKey: "kt", ttlSec: 300))   // 单段

        cm.cacheData(Data("t".utf8), forKey: "kt", ttlSec: 300)   // 写入走 ttl 分支
        XCTAssertTrue(ud.string(forKey: "oddsmaker_kt")!.contains("\n"))
    }

    // ---------- 前缀与 clearAll ----------

    func testCustomPrefixAndClearAllFiltersByPrefix() {
        let custom = CacheManager(userDefaults: ud, prefix: "myprefix")
        custom.cacheData(Data("v".utf8), forKey: "k")
        XCTAssertEqual(ud.string(forKey: "myprefix_k"), "v")
        XCTAssertNil(cm.getCachedData(forKey: "k"))   // 默认前缀互不可见

        cm.cacheData(Data("a".utf8), forKey: "x1")
        cm.cacheExperiments(Data("[]".utf8), gameId: "g", environment: "p")
        ud.set("keep-me", forKey: "unrelated_key")
        cm.clearAll()
        XCTAssertNil(ud.string(forKey: "oddsmaker_x1"))
        XCTAssertNil(ud.string(forKey: "oddsmaker_experiments_g_p"))
        XCTAssertEqual(ud.string(forKey: "unrelated_key"), "keep-me")   // 非前缀 key 保留
        XCTAssertEqual(ud.string(forKey: "myprefix_k"), "v")            // 其他前缀不受影响
    }
}
