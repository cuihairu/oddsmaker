# SDK 设计规范（跨端一致）

初始化
- `apiKey`, `endpoint`, `gameId`, `environment`，自动生成 `deviceId`（可覆盖）。
- 批量发送：默认 5s 或 50 条触发；`application/x-ndjson`；失败后重试/回队列。
- 离线容错：断网持久化（Web localStorage；Android SharedPreferences；iOS/Unity 文件）。

客户端边界
- 客户端只集成 SDK，不直接接触 Kafka、ClickHouse、管理后台 token 或 HMAC secret。
- 事件上报只走网关 `POST /v1/batch`，使用面向客户端的 `x-api-key`。
- 实验配置可选走 Control Service 的只读配置接口，SDK 负责本地缓存、分流和曝光上报；这部分仍属于客户端 SDK 能力。

事件接口
- `track(eventName, props?)` 返回 `event_id`
- `setUserId(userId|null)`、`setUserProps(props)`
- `expose(exp, variant)` 标准化实验曝光事件
- `revenue(amount, currency, props?)`
- `flush()` 手动刷新

会话
- 前后台切换与闲置间隔（默认 30 分钟）自动生成 `session_id`。

治理
- 本地 PII 白名单与掩码；`props` 深度≤3，整体≤64KB。
- 内部诊断指标与丢弃原因统计，debug 开关。

用户标识与分流键（统一策略）
- SDK 统一使用 `userKey = user_id ?? device_id` 作为分流/会话/聚合的主键；当 `user_id` 为空时回退到 `device_id`。
- 实验分流哈希采用 FNV-1a 32-bit，输入为 `"<expId>:<salt>:<userKey>"`，权重为非负整数；跨端（Web/Android/iOS/Unity）保持一致性。
- 设备标识默认生成策略：Web `localStorage` 持久化随机 ID；Android 使用随机 UUID；iOS 基于 `identifierForVendor` 的派生（如不可用则随机）。

安全
- 客户端 SDK 不支持 HMAC 签名，避免把服务端 secret 下发到 App/Web 包。
- HMAC/`x-signature` 仅用于可信服务端到服务端调用。
- 内容编码容错：`content-encoding` 大小写与多值变体会被宽松处理；建议客户端保持一致。

示例（Web TS）
```ts
const client = new Oddsmaker({ apiKey, endpoint, gameId, environment });
client.track('level_start', { level: 3 });
client.setUserId('u123');
client.setUserProps({ channel: 'organic' });
client.expose('paywall', 'B');
client.revenue(9.99, 'USD', { sku: 'noads' });
await client.flush();
```
