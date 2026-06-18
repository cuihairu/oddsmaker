# Pit Android SDK（Kotlin）

特性
- 批量发送：默认 5s 或 50 条；`application/x-ndjson`；自动 `gzip`
- 离线容错：失败回退本地队列（SharedPreferences 持久化）
- 会话管理：默认 30 分钟闲置切新 session
- 鉴权：仅 `x-api-key`（客户端 SDK 不支持 HMAC，避免 secret 泄漏）

快速开始
```kotlin
val pt = Pit(
  context,
  Pit.Options(
    apiKey = "pk_test_example",
    endpoint = "http://10.0.2.2:8080", // 模拟器访问本机网关
    tenantId = "org_demo",
    appId = "game_demo__prod",
    debug = true
  )
)
pt.track("level_start", mapOf("level" to 1))
pt.setUserId("u1")
pt.expose("paywall", "B")
pt.revenue(9.99, "USD", mapOf("sku" to "noads"))
pt.flush()
```

集成
- 该目录为独立 Android Library 项目：`sdks/android`
- 用 Android Studio 打开 `sdks/android` 作为工程或将模块导入到你的 App 中
- 依赖：Kotlin 1.9.24、Android Gradle Plugin 8.5.1、OkHttp 4.12.0

注意
- 客户端 SDK 仅支持 `x-api-key` 鉴权；HMAC 仅用于 Server SDK（服务端到服务端，secret 不下发）。
- 队列持久化为 NDJSON 字符串；为简化演示，重启后的反序列化可按需补充（当前只保留原始字符串避免崩溃）。
- 可按需接入 WorkManager/Connectivity 监听以优化离线重试。
