import XCTest
@testable import Oddsmaker

final class ConsistencyTests: XCTestCase {
    func testAssignVariantDeterministic() {
        let variants: [(name: String, weight: Int)] = [("A", 50), ("B", 50)]
        let keys = ["u1", "u2", "u3", "device123", "device456"]
        let r1 = keys.map { Oddsmaker.assignVariant(expId: "exp_consistency", salt: "s", variants: variants, key: $0) }
        let r2 = keys.map { Oddsmaker.assignVariant(expId: "exp_consistency", salt: "s", variants: variants, key: $0) }
        XCTAssertEqual(r1, r2)
    }

    func testAssignVariantOverflowMatchesServer() {
        // 与服务端 ExperimentSplitterTest 同向量（跨端一致锚定）：Int64 精确求和未折叠时
        // 同一主体与服务端落位不同变体；统一为 int32 折叠 + 无符号取模 + 兜底末变体。
        // 原 UInt32(sum) 截断在 sum≥2^32 时分裂、低 32 位为 0 时除零 trap
        let MAX = Int(Int32.max)
        // [2, MAX]:sum 折叠为负,u1 的 h=507466947 越过前缀和 → 兜底 last（原实现返回 first）
        let two: [(name: String, weight: Int)] = [("control", 2), ("treatment", MAX)]
        XCTAssertEqual("treatment", Oddsmaker.assignVariant(expId: "exp", salt: "salt", variants: two, key: "u1"))
        // 3×MAX:sum 折叠 2147483645 > 0（合法可创建形态）,h=507466949 < MAX → control
        let three: [(name: String, weight: Int)] = [("control", MAX), ("treat-a", MAX), ("treat-b", MAX)]
        XCTAssertEqual("control", Oddsmaker.assignVariant(expId: "exp", salt: "salt", variants: three, key: "u1"))
        XCTAssertEqual("control", Oddsmaker.assignVariant(expId: "exp", salt: "salt", variants: three, key: "u8"))
        // [MAX, MAX, 2]:sum 折叠恰为 0,取模无定义 → 兜底 last 不 trap（原实现除零崩溃）
        let zero: [(name: String, weight: Int)] = [("control", MAX), ("treat-a", MAX), ("treat-b", 2)]
        XCTAssertEqual("treat-b", Oddsmaker.assignVariant(expId: "exp", salt: "salt", variants: zero, key: "u1"))
    }
}
