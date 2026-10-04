# Oddsmaker Server SDK（Java，零依赖）

服务端可信事件采集 SDK（2.0 批次 **B3**，06 计划书 §3.1/§4.3）。运行时仅用 JDK
（`java.net.http` / `javax.crypto` / `java.util.zip`），可 subtree 拆出独立发布。

## 快速开始

```java
import io.oddsmaker.sdk.Oddsmaker;

Oddsmaker sdk = Oddsmaker.initialize(new Oddsmaker.Config()
    .endpoint("http://gateway:8080")     // gateway-service 基址
    .apiKey("pk_…")                      // SERVER 型 key（持有 secret）
    .secret("sk_…")                      // HMAC 密钥
    .gameId("game_demo")
    .environment("prod"));

sdk.setUser("player-1", Map.of("tier", "gold"));      // 后续事件的默认主体与属性
sdk.track("server.purchase.confirmed", Map.of("amount", 9.99, "currency", "USD"));
sdk.flush();   // 可选：同步清空队列；否则后台按 flushIntervalMs 自动投递
sdk.close();   // 最终 flush 并停泵
```

**前置**：SERVER 型 key 只能发给已启用 server 事件能力的游戏（控制台
`games.server_events_enabled = true`），未启用时发放接口直接拒绝（B3 发放校验）。

## 事件契约（v1）

| 字段 | 来源 |
|---|---|
| `event_id` | SDK 生成 UUID |
| `event_name` | 调用方传入；命名约定 `server.<domain>.<action>`（如 `server.purchase.confirmed`） |
| `ts_client` | SDK 发送时刻（epoch ms） |
| `game_id` / `environment` | Config |
| `device_id` | 显式 userId > setUser userId > Config.deviceId（默认 `server`） |
| `user_id` | 显式 userId > setUser userId |
| `props` | setUser traits 与 track props 合并（同名键显式 props 优先） |

充值/经济类结算**只认 server 事件**；同一业务事实的客户端与服务器事件不去重，
消费方按 `source` 区分（v2 契约字段，B4 落地）。SDK 不发送任何 v2 字段。

## 投递管道：Memory → Disk Queue → Batch → Gzip → HMAC

- **内存队列**：默认 10,000 条；超过后最旧一半溢写磁盘（`queueDir/spill-<seq>.ndjson`）。
- **磁盘溢出**：默认上限 100,000 条，超限删最旧文件并计数丢弃；进程重启后同
  `queueDir` 自动续传（`close()` 不删除未发完的溢写文件）。
- **批量**：默认 100 条/请求（`batchSize`）。
- **压缩**：请求体 gzip（`Content-Encoding: gzip`）。
- **签名**：`x-signature: t=<epoch秒>, s=<hex>`，被签消息 = `t + "." + 压缩后原始字节按
  UTF-8 解码的字符串`——与网关 `HmacFilter` 完全同口径（HMAC 覆盖压缩后请求体）。

失败语义：

| 网关响应 | SDK 行为 |
|---|---|
| 2xx | 批次完成，丢弃 |
| 401 / 403 | 丢弃整批并告警（凭证/签名错误不会自愈，重试无意义） |
| 429 / 5xx / 网络异常 | 整批退回队列，本轮 flush 止步，下次投递续发 |

时间窗：签名 ±300s（网关侧判定），时钟偏移过大的主机需先校时。
防重放：同一签名一次有效（网关 ReplayGuard）；SDK 每次发送取新时间戳、
重试批次重新签名，不触发重放拒绝。

## 网关侧约定（已在仓测试覆盖）

`services/gateway-service` 的 HMAC 正/负臂见
`HmacSignatureWindowTest`（有效签名 200 / 过期 401 / 缺签名 401）与
`HmacFilterCoverageTest`（伪造签名 401 invalid_signature）、`ReplayGuardRotationTest`
（重放 401）。SDK 集成测试（`OddsmakerIntegrationTest`）覆盖队列溢出落盘续传、
验签正/负臂、gzip 管道与可重试失败臂。

## 构建

```bash
# monorepo 内
./gradlew :sdks:server:test

# 独立（subtree 拆出后）
cd sdks/server && gradle test
```
