# Oddsmaker Android SDK（Kotlin）

特性
- 批量发送：默认 5s 或 50 条；`application/x-ndjson`；自动 `gzip`
- 离线容错：失败回退本地队列（SharedPreferences 持久化）
- 会话管理：默认 30 分钟闲置切新 session
- 鉴权：仅 `x-api-key`（客户端 SDK 不支持 HMAC，避免 secret 泄漏）

快速开始
```kotlin
val sdk = Oddsmaker(
  context,
  Oddsmaker.Options(
    apiKey = "pk_test_example",
    endpoint = "http://10.0.2.2:8080", // 模拟器访问本机网关
    gameId = "game_demo",
    environment = "prod",
    debug = true
  )
)
sdk.track("level_start", mapOf("level" to 1))
sdk.setUserId("u1")
sdk.setUserProps(mapOf("channel" to "organic"))
sdk.expose("paywall", "B")
sdk.revenue(9.99, "USD", mapOf("sku" to "noads"))
sdk.flush()
```

集成
- 该目录为独立 Android Library 项目：`sdks/android`
- 用 Android Studio 打开 `sdks/android` 作为工程或将模块导入到你的 App 中
- 依赖：Kotlin 1.9.24、Android Gradle Plugin 8.5.1、OkHttp 4.12.0

注意
- 客户端 SDK 仅支持 `x-api-key` 鉴权；HMAC 仅用于 Server SDK（服务端到服务端，secret 不下发）。
- 队列持久化为 NDJSON 字符串；重启后会恢复未发送事件并保留 `props`。
- 可按需接入 WorkManager/Connectivity 监听以优化离线重试。
