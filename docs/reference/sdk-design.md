# SDK Design Specification (Cross-Platform Consistency)

Initialization
- `apiKey`, `endpoint`, `gameId`, `environment`; `deviceId` auto-generated (overridable).
- Batch sending: triggered by default at 5s or 50 events; `application/x-ndjson`; retries/queues on failure.
- Offline resilience: persist on network loss (Web localStorage; Android SharedPreferences; iOS/Unity files).

Client boundary
- Clients only integrate the SDK and do not directly touch Kafka, ClickHouse, admin-console tokens, or HMAC secrets.
- Event reporting only goes through the gateway `POST /v1/batch`, using the client-facing `x-api-key`.
- Experiment configuration can optionally go through the Control Service's read-only config API; the SDK handles local caching, bucketing, and exposure reporting; this remains part of the client SDK capability.

Event API
- `track(eventName, props?)` returns `event_id`
- `setUserId(userId|null)` and `setUserProps(props)`
- `identify(userId, props?)` sets user identity and reports an identity event, triggering the backend to merge `device_id` into `user_id` (supports guest-to-account conversion); the event props include `previous_user_id` and `player_id`
- `setPlayer(playerId|null)` sets the current character ID; subsequent events automatically carry `player_id` in props
- `expose(exp, variant)` standardizes experiment exposure events
- `revenue(amount, currency, props?)`
- `flush()` manual flush

Session
- Foreground/background switches and the idle interval (default 30 minutes) auto-generate `session_id`.

Governance
- PII governance runs at the gateway; the SDK does no local masking; `props` depth ≤ 3 (Android/Unity/iOS enforce truncation, Web does not); the overall ≤ 64KB is the gateway-side single-event limit and is not validated locally by the SDK.
- Internal diagnostic metrics and drop-reason statistics, plus a debug switch.

User identity and bucketing key (unified strategy)
- The bucketing key is passed in explicitly by the integrator; the SDK does not derive `user_id`/`device_id` internally.
- Experiment bucketing hash uses FNV-1a 32-bit, input `"<expId>:<salt>:<userKey>"`, weight is a non-negative integer; kept consistent across platforms (Web/Android/iOS/Unity).
- Default device-identifier generation: Web persists a random ID in `localStorage`; Android uses a random UUID; iOS uses a derivation from `identifierForVendor` (random if unavailable).

Security
- Client SDKs do not support HMAC signing, avoiding distributing the server secret into App/Web packages.
- HMAC/`x-signature` is only for trusted server-to-server calls.
- Content-encoding tolerance: `content-encoding` case and multi-value variants are handled leniently; clients are advised to stay consistent.

Example (Web TS)
```ts
const client = new Oddsmaker({ apiKey, endpoint, gameId, environment });
client.track('level_start', { level: 3 });
client.setUserId('u123');
client.setUserProps({ channel: 'organic' });
client.setPlayer('char_456');
client.identify('u123');   // 游客转账号时触发，后端归并 device→user
client.expose('paywall', 'B');
client.revenue(9.99, 'USD', { sku: 'noads' });
await client.flush();
```
