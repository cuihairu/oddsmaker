# SDK 设计规范（跨端一致）

初始化
- `apiKey`, `endpoint`, `projectId`，自动生成 `deviceId`（可覆盖）。
- 批量发送：默认 5s 或 50 条触发；NDJSON+gzip；指数退避重试。
- 离线容错：断网持久化（Web IndexedDB/LocalStorage；Android Room；iOS 文件）。

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
- HMAC 可选，`x-signature` 按时间 + 体组成签名。
 - 网关默认时间窗 300 秒，超时返回 401（`signature_expired`）。
 - 内容编码容错：`content-encoding` 大小写与多值变体会被宽松处理；建议客户端保持一致。

示例（Web TS）
```ts
Pit.init({ apiKey, endpoint, projectId });
Pit.track('level_start', { level: 3 });
Pit.setUserId('u123');
Pit.expose('paywall', 'B');
Pit.revenue(9.99, 'USD', { sku: 'noads' });
await Pit.flush();
```
