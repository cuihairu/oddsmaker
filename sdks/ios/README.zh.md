# Pit iOS SDK（Swift Package）

集成
- 在 Xcode 中：File -> Add Packages -> 选择本地文件夹 `sdks/ios`（或将其作为 SPM 依赖）

用法
```swift
import Pit

var opts = PitOptions(apiKey: "pk_test_example",
                           endpoint: URL(string: "http://localhost:8080")!,
                           tenantId: "org_demo",
                           appId: "game_demo__prod")
opts.debug = true
Pit.shared.initSDK(opts)

Pit.shared.setUserId("u1")
_ = Pit.shared.track("level_start", props: ["level": 3])
_ = Pit.shared.expose("paywall", variant: "B")
_ = Pit.shared.revenue(amount: 9.99, currency: "USD", props: ["sku": "noads"])
Pit.shared.flush()

// 实验配置（可选）
// 读取缓存（默认 5 分钟 TTL），并在后台刷新
Pit.shared.fetchExperimentsCached(controlURL: URL(string: "http://localhost:8085")!, tenantId: "org_demo", appId: "game_demo__prod") { data in
    // data 为 JSON（二进制）；可解析后用于分流/曝光
}
// 自动刷新，回调更新
let timer = Pit.shared.startExperimentsAutoRefresh(controlURL: URL(string:"http://localhost:8085")!, tenantId: "org_demo", appId: "game_demo__prod", intervalSec: 300) { data in
    // 解析并在合适时机更新
}
// 停止：timer.invalidate()
```

特性
- 批量发送（默认 5s 或 50 条），NDJSON；可选 gzip（Compression）
- 离线容错：队列持久化到 Caches 目录（NDJSON 文件）
- 会话：默认 30 分钟闲置切换 session_id
- 鉴权：仅 `x-api-key`（客户端 SDK 不支持 HMAC，避免 secret 反编译泄漏）
- 实验配置：内置缓存（UserDefaults，默认 TTL 5 分钟）与自动刷新辅助方法

注意
- 需要 iOS 13+（CryptoKit/Compression）
- 生产项目建议将队列持久化到 Application Support 并配合后台传输策略
- 网关默认接受 `ts_client` 为毫秒整数或 ISO 字符串（已放宽 JSON Schema）
