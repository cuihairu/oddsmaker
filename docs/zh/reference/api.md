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

必填（schema 校验缺一即拒，`invalid_schema`）：

- `event_id`: 推荐 UUIDv7/ULID。
- `game_id`: 游戏 ID。
- `environment`: `dev`、`staging` 或 `prod`。
- `event_name`: 建议 `category:subject:action`。
- `device_id`
- `ts_client`: epoch 毫秒、epoch 微秒或 ISO8601。

`event_type` 不是必填：缺省时网关按事件名关键词推断（risk/experiment/ad/progression/session/error/resource/user/design），都匹配不上归为 `business`；显式声明时优先采信。

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

事件契约 v2 字段（网关权威回填，schema 校验通过后、发布前写入）：

- `event_version`: 缺省回填 `1`。
- `event_origin`: 缺省回填 `gateway`（网关直收，无 SDK 声明）。
- `source`: `client|server`。缺省时按 key 档位回填（SERVER key→`server`，其余→`client`）；`system`/`derived` 是平台内部保留档，接入 key 声明即整事件拒绝。
- `trust_level`: `LOW|HIGH|COMPUTED`，一律按 source 推导（client→LOW，server/system→HIGH，derived→COMPUTED），发送方声明的值不采信；声明高于推导档也整事件拒绝。

以上自抬拒绝的 reason 是 `trust_escalation`（明细 `source_escalation` / `trust_level_escalation`）。下游 Kafka/ClickHouse/Flink 看到的都是回填后的事件；v1 事件不携带这些字段，向后兼容。

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
  "sampled_out": 0,
  "duplicates": 0,
  "next_hint_ms": 3000
}
```

`sampled_out` 是按环境采样率被丢弃的事件数（按 device_id 确定性分桶）；`duplicates` 是 `event_id` 幂等吸收的 SDK 重试数——两者都计入 `accepted` 语义之外的独立计数，重复事件仍回 `accepted`。

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
- Gateway 在 schema 校验通过后占用幂等位：同一 `event_id` 的 SDK 重试静默吸收（计 `duplicates`，仍回 `accepted`，不重复发布、不进 DLQ）。
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
- `api_key_scope_mismatch`: API Key 与事件中的 `game_id/environment` 不一致（事件级拒绝）。
- `signature_not_supported`: 客户端档 key 携带了 `x-signature`（客户端 SDK 不允许持有 secret）。
- `invalid_signature`: Server SDK HMAC 签名无效。
- `signature_expired`: HMAC 时间窗超出（默认 ±300 秒）。
- `replay_detected`: 同一签名（t+s）重复提交，重放拦截。
- `too_many_requests`: 命中限流。
- `payload_too_large`: 请求体或单事件超过上限。
- `invalid_timestamp`: 事件时间戳偏离服务器时间过远（默认 ±24h，可配）。
- `invalid_schema`: 单个事件不符合 schema。
- `trust_escalation`: v2 字段自抬（source/trust_level 声明高于 key 档位）。
- `pii_blocked`: 命中 PII 阻断。
- `blocked`: 命中风控黑名单硬拦截。
- `environment_unavailable`: 事件所属环境未启用（HTTP 503）。
- `internal_error`: 服务端内部错误。
