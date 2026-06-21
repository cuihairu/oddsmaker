# Oddsmaker 采集 API

路径：`POST /v1/batch`

## Headers

- `x-api-key`: 必填，绑定到一个 `game_id + environment`。
- `x-signature`: 可选，仅 Server SDK 使用，客户端 SDK 不允许持有 secret。
- `content-type`: `application/json` 数组或 `application/x-ndjson`。
- `content-encoding`: 推荐 `gzip`。

## 限制

- 请求体解压后默认 ≤ 1MB。
- 单事件序列化后默认 ≤ 64KB。
- 单次 batch 默认 ≤ 500 条。
- 具体限制由控制面的 API Key 策略下发。

## 事件字段

必填：

- `event_id`: 推荐 UUIDv7/ULID。
- `game_id`: 游戏 ID。
- `environment`: `dev`、`staging` 或 `prod`。
- `event_type`: `session|user|business|resource|progression|design|error|ad|experiment|risk`。
- `event_name`: 建议 `category:subject:action`。
- `device_id`
- `ts_client`: epoch 毫秒、epoch 微秒或 ISO8601。

常用：

- `user_id`
- `player_id`
- `character_id`
- `session_id`
- `platform`
- `app_version`
- `sdk_version`
- `country`
- `props`

游戏字段：

- `server_id`
- `guild_id`
- `match_id`
- `level_id`
- `game_mode`
- `difficulty`
- `progression_path`

商业化字段：

- `order_id`
- `product_id`
- `revenue_amount`
- `revenue_currency`
- `receipt_hash`
- `virtual_currency`
- `virtual_amount`
- `flow_type`
- `item_id`
- `ad_network`
- `ad_placement`
- `ad_format`
- `ad_impression_id`

风控字段：

- `risk_context`
- `device_fingerprint`
- `client_integrity`

## 请求示例

NDJSON：

```json
{"event_id":"01JE2E0001","game_id":"game_demo","environment":"prod","event_type":"progression","event_name":"progression:level:start","device_id":"d1","player_id":"p1001","level_id":"level_1","ts_client":1730000000000}
{"event_id":"01JE2E0002","game_id":"game_demo","environment":"prod","event_type":"progression","event_name":"progression:level:complete","device_id":"d1","player_id":"p1001","level_id":"level_1","ts_client":1730000001000}
```

## 响应体

```json
{
  "accepted": ["01J..."],
  "rejected": [{"event_id": "01J...", "reason": "invalid_schema"}],
  "next_hint_ms": 3000
}
```

## 错误响应

```json
{
  "code": "too_many_requests",
  "message": "rate limited",
  "request_id": "f6d1..."
}
```

`request_id` 同时在响应头 `x-request-id` 返回。

## 幂等与去重

- `event_id` 应在 `game_id + environment` 内唯一。
- Gateway 不做最终去重，重复上报仍可返回 accepted。
- Flink enrich/dedup 按 `game_id + environment + event_id` 去重。

## 校验与治理

- Schema 由 JSON Schema + Avro 共同定义。
- Tracking Plan 控制事件名、字段字典、枚举和 cardinality 上限。
- PII 策略在 Gateway 执行：deny、mask、coarse、drop。
- Props 可按 allowlist 控制；嵌套层级和数组长度受限。
- 违规事件进入 DLQ，并可触发风控事件。

## 签名规范

仅 Server SDK 使用：

```text
x-signature: t=TIMESTAMP, s=hex(hmacSha256(secret, t + "." + body))
```

Gateway 默认校验 5 分钟时间窗。客户端 SDK 不应使用 HMAC。

## 常见错误码

- `invalid_api_key`: API Key 无效。
- `key_scope_mismatch`: API Key 与事件中的 `game_id/environment` 不一致。
- `invalid_signature`: Server SDK HMAC 签名无效。
- `signature_expired`: HMAC 时间窗超出。
- `too_many_requests`: 命中限流。
- `payload_too_large`: 请求体超过上限。
- `invalid_schema`: 单个事件不符合 schema。
- `pii_blocked`: 命中 PII 阻断。
- `risk_blocked`: 命中风控硬拦截。
- `internal_error`: 服务端内部错误。
