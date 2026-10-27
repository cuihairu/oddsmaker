# Oddsmaker Ingestion API

Path: `POST /v1/batch`

## Headers

- `x-api-key`: required, bound to a `game_id + environment`.
- `x-signature`: optional, used only by the Server SDK; client SDKs must not hold the secret.
- `content-type`: `application/json` array or `application/x-ndjson`.
- `content-encoding`: `gzip` recommended.

## Limits

- The decompressed request body is ≤ 1MB by default.
- A single serialized event is ≤ 64KB by default.
- No event count limit; constrained by the 1MB request body and 64KB single-event byte limits.
- The specific limits are delivered by the control plane's API key policy.

## Event Fields

Required (schema validation rejects on any missing field, `invalid_schema`):

- `event_id`: UUIDv7/ULID recommended.
- `game_id`: game ID.
- `environment`: `dev`, `staging`, or `prod`.
- `event_name`: `category:subject:action` recommended.
- `device_id`
- `ts_client`: epoch milliseconds or ISO8601.

`event_type` is not required: when omitted, the gateway infers it from event-name keywords (risk/experiment/ad/progression/session/error/resource/user/design), and if none match it is classified as `business`; an explicitly declared value takes precedence.

Commonly used:

- `user_id`
- `player_id`
- `character_id`
- `session_id`
- `platform`
- `app_version`
- `sdk_version`
- `country`
- `props`

Game fields:

- `server_id`
- `guild_id`
- `match_id`
- `level_id`
- `game_mode`
- `difficulty`
- `progression_path`

Monetization fields:

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

Risk fields:

- `risk_context`
- `device_fingerprint`
- `client_integrity`

Event contract v2 fields (authoritatively backfilled by the gateway, written after schema validation passes and before publishing):

- `event_version`: backfilled as `1` by default.
- `event_origin`: backfilled as `gateway` by default (received directly by the gateway, no SDK declaration).
- `source`: `client|server`. When omitted, backfilled by the key tier (SERVER key → `server`, others → `client`); `system`/`derived` are platform-internal reserved tiers — declaring them from an access key rejects the entire event.
- `trust_level`: `LOW|HIGH|COMPUTED`, always derived from `source` (client → LOW, server/system → HIGH, derived → COMPUTED); sender-declared values are not trusted; declaring a value higher than the derived tier also rejects the entire event.

The reason for these self-escalation rejections is `trust_escalation` (details: `source_escalation` / `trust_level_escalation`). Downstream Kafka/ClickHouse/Flink all see the backfilled events; v1 events do not carry these fields, preserving backward compatibility.

## Request Example

NDJSON:

```json
{"event_id":"01JE2E0001","game_id":"game_demo","environment":"prod","event_type":"progression","event_name":"progression:level:start","device_id":"d1","player_id":"p1001","level_id":"level_1","ts_client":1730000000000}
{"event_id":"01JE2E0002","game_id":"game_demo","environment":"prod","event_type":"progression","event_name":"progression:level:complete","device_id":"d1","player_id":"p1001","level_id":"level_1","ts_client":1730000001000}
```

## Response Body

```json
{
  "accepted": ["01J..."],
  "rejected": [{"event_id": "01J...", "reason": "invalid_schema"}],
  "sampled_out": 0,
  "duplicates": 0,
  "next_hint_ms": 3000
}
```

`sampled_out` is the number of events dropped by the per-environment sampling rate (deterministic bucketing by `device_id`); `duplicates` is the number of SDK retries absorbed idempotently by `event_id` — both are counted independently outside the `accepted` semantics, and duplicate events are still returned as `accepted`.

## Error Response

```json
{
  "code": "too_many_requests",
  "message": "rate limited",
  "request_id": "f6d1..."
}
```

`request_id` is also returned in the `x-request-id` response header.

## Idempotency and Deduplication

- `event_id` should be unique within `game_id + environment`.
- The gateway claims the idempotency slot after schema validation passes: SDK retries with the same `event_id` are silently absorbed (counted as `duplicates`, still returned as `accepted`, not republished, and not sent to the DLQ).
- Flink enrich/dedup deduplicates by `event_id` (7-day TTL).

## Validation and Governance

- The schema is defined jointly by JSON Schema + Avro.
- The Tracking Plan governs event names, field dictionaries, enums, and cardinality limits.
- PII policy is enforced at the gateway: deny, mask, coarse, drop.
- Props can be controlled by allowlist; nesting depth and array length are limited.
- Violating events go to the DLQ and can trigger risk events.

## Signature Specification

Used only by the Server SDK:

```text
x-signature: t=TIMESTAMP, s=hex(hmacSha256(secret, t + "." + body))
```

The gateway validates a 5-minute time window by default. Client SDKs must not use HMAC.

## Common Error Codes

- `invalid_api_key`: invalid API key.
- `api_key_scope_mismatch`: the API key does not match the `game_id/environment` in the event (event-level rejection).
- `signature_not_supported`: a client-tier key carried `x-signature` (client SDKs must not hold the secret).
- `invalid_signature`: invalid Server SDK HMAC signature.
- `signature_expired`: HMAC time window exceeded (default ±300 seconds).
- `replay_detected`: the same signature (t+s) was submitted again; replay blocked.
- `too_many_requests`: rate limit hit.
- `payload_too_large`: the request body or a single event exceeds the limit.
- `invalid_timestamp`: event timestamp deviates too far from server time (default ±24h, configurable).
- `invalid_schema`: a single event does not conform to the schema.
- `unknown_event`: the event name is not in the event surface of the ACTIVE schema in that key's scope (when `rejectUnknownEvents` is enabled; the dev environment is exempt).
- `trust_escalation`: v2 field self-escalation (declared `source`/`trust_level` is higher than the key tier).
- `pii_blocked`: hit the PII block.
- `blocked`: hit the risk-control blacklist hard block.
- `environment_unavailable`: the event's environment is not enabled (HTTP 503).
- `internal_error`: internal server error.
