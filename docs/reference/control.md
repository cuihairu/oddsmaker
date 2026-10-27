# Oddsmaker Control Plane API (Target Model)

The control plane manages a single company's games, environments, API Keys, Tracking Plans, PII policies, risk policies, user permissions, and audit logs.

There is no longer an Organization/Tenant API design. Company information belongs to deployment configuration and is not treated as a business resource.

The environment design uses:

- `Game`: business object
- `Environment`: logical release stage
- `StorageProfile`: cluster configuration and capacity planning

**Database architecture** (current implementation): PostgreSQL metadata database + shared ClickHouse tables, with event and aggregate tables partitioned by `(game_id, environment, month)`. "Database per game" is the target form, expressed by the storage profile's `isolationStrategy` (`shared` / `prod_isolated` / `dedicated`); the physical database split is not yet wired up.

```
PostgreSQL（元数据）
├─ games / environments / api_keys
├─ users / roles / user_role_assignments
├─ tracking_plans / risk_rules / risk_features / block_lists / audit_logs
└─ storage_profiles ...

ClickHouse（共享表，按 game_id + environment + 月 分区）
├─ events
├─ risk_events / risk_scores / risk_actions
└─ ...
```

## Field Naming Conventions

- `gameId` / `environmentId`: internal identifiers used by the control plane API
- `game_id` / `environment`: logical names used by the event protocol (e.g. `prod`, `staging`)
- API Keys are bound to `gameId + environmentId` (internal IDs)
- **Event ingestion**: `game_id` and `environment` are required event fields and also the ClickHouse partition keys (not "the table level means they don't need to be stored")
- **New `server_id`**: optional field, recommended for MMORPGs, used to distinguish different servers/regions

## Core Resources

### Game

```http
POST /api/games
GET /api/games
GET /api/games/{gameId}
PUT /api/games/{gameId}
DELETE /api/games/{gameId}
POST /api/games/{gameId}/publish
POST /api/games/{gameId}/unpublish
```

Example:

```json
{
  "id": "game_demo",
  "name": "Demo Game",
  "genre": "RPG",
  "platforms": ["MOBILE", "PC"],
  "defaultTimezone": "Asia/Shanghai",
  "defaultCurrency": "USD"
}
```

The `genre` enum is uppercase (RPG/STRATEGY/ACTION/.../RACING/OTHER, 13 categories in total); `platforms` takes the values `WEB/MOBILE/PC/CONSOLE/VR/AR` (no android/ios); the timezone field name is `defaultTimezone`. After successful creation, the three environments `dev` / `staging` / `prod` are created automatically.

### Environment

```http
POST /api/games/{gameId}/environments
GET /api/games/{gameId}/environments
GET /api/games/{gameId}/environments/{environmentName}
PUT /api/games/{gameId}/environments/{environmentName}
DELETE /api/games/{gameId}/environments/{environmentName}
```

Recommended default environments: `dev`, `staging`, `prod`.

Environment-level configuration: enabled status, sampling toggle and sampling rate (the gateway returns 503 for non-active environments). Data retention is driven by the environment's/game's `dataRetentionDays` to set ClickHouse table TTLs (`RetentionEnforcementService`, precedence environment ?? game ?? default); the storage profile does not participate in retention.

### Storage Profile

```http
POST /api/storage-profiles
GET /api/storage-profiles
GET /api/storage-profiles/{profileId}
PUT /api/storage-profiles/{profileId}
DELETE /api/storage-profiles/{profileId}
```

The Storage Profile is responsible for deciding:

- Kafka cluster / topic namespace
- ClickHouse backend
- Redis backend
- Archive bucket
- isolation strategy: `shared | prod_isolated | dedicated`

### API Key

```http
POST /api/api-keys
GET /api/api-keys?gameId=&environmentId=
GET /api/api-keys/{keyId}
PUT /api/api-keys/{keyId}
DELETE /api/api-keys/{keyId}
```

Legacy routes kept for compatibility:

```http
POST /api/keys
GET /api/keys?gameId=&environmentId=
GET /api/keys/{keyId}
PUT /api/keys/{keyId}/policy
DELETE /api/keys/{keyId}
```

Example:

```json
{
  "gameId": "game_demo",
  "environmentId": "game_demo_prod",
  "name": "android-client",
  "keyType": "client",
  "rateLimit": {
    "rpm": 10000,
    "ipRpm": 300
  }
}
```

> `environmentId` is the environment's internal identifier (e.g. `game_demo_prod`), not the logical name (e.g. `prod`); it is generated automatically when the environment is created.

Key types:

- `client`: client-side event writes, does not participate in HMAC signing (the secret is not used for server-side calls); the creation response still returns the secret field, and callers must not deliver it to clients.
- `server`: server-side event writes and payment verification; the secret is returned once.
- `admin`: used by the control plane or internal services.

### Tracking Plan

```http
POST /api/games/{gameId}/tracking-plans
GET /api/games/{gameId}/tracking-plans
GET /api/games/{gameId}/tracking-plans/{trackingPlanId}
GET /api/games/{gameId}/tracking-plans/active
GET /api/games/{gameId}/tracking-plans/environment/{environmentId}
PUT /api/games/{gameId}/tracking-plans/{trackingPlanId}
POST /api/games/{gameId}/tracking-plans/{trackingPlanId}/activate
POST /api/games/{gameId}/tracking-plans/{trackingPlanId}/deactivate
DELETE /api/games/{gameId}/tracking-plans/{trackingPlanId}
```

Event and property dictionaries hang under the plan: standard CRUD on `.../{trackingPlanId}/events[/{eventDefinitionId}]` and `.../events/{eventDefinitionId}/properties[/{propertyDefinitionId}]`. There is no `publish` / `rollback`—the enabled state is toggled with `activate` / `deactivate`.

### Event Schema (first-class resource as of B7)

Same underlying layer as the tracking plan (same database, same entities), a versioned resource API exposed with EventSchema semantics (B7):

```http
POST /api/games/{gameId}/schemas
GET /api/games/{gameId}/schemas
GET /api/games/{gameId}/schemas/active
GET /api/games/{gameId}/schemas/{schemaId}
PUT /api/games/{gameId}/schemas/{schemaId}
POST /api/games/{gameId}/schemas/{schemaId}/publish?userId=
GET /api/games/{gameId}/schemas/{schemaId}/compatibility
POST /api/games/{gameId}/schemas/{schemaId}/deactivate
DELETE /api/games/{gameId}/schemas/{schemaId}
```

The event and property definition sub-resources are exactly the same shape as the tracking plan's (`.../{schemaId}/events[...]`). The difference from `activate` is that `publish` carries a **compatibility gate**: when the Schema's `compatibility` is not NONE, before publishing it diffs the event set against the most recent ACTIVE baseline version for the same game + same environment—BACKWARD requires `removed` to be empty (old producer events are not rejected by the new version), FORWARD requires `added` to be empty (old consumers do not recognize additions), FULL requires both, NONE performs no check; type/required-flag/importance changes to same-named events are recorded in `changedEvents` as informational items only and do not affect the compatibility verdict. The check is the read-only endpoint `GET .../compatibility`, returning `{schemaId, mode, baselineId, compatible, addedEvents, removedEvents, changedEvents}`; an incompatible publish fails outright with 400 and the Schema stays DRAFT.

New fields as of B7: `compatibility` (default NONE), `piiPolicy` (event-level PII policy JSON), `retentionDays`, `samplingRate`, `ownerId`; the default of `rejectUnknownEvents` is settled as `true`—the event name list of an ACTIVE Schema is delivered to the gateway along with the internal feed (`/internal/api-keys/{key}`) (environment-bound version first, falling back to the global version), and the gateway rejects undefined events as `unknown_event`, with the dev environment exempt. Web console entry: `/schemas` (event Schema page).

**PII precedence chain (environment-level Schema > ApiKey > gateway default)**: the ACTIVE Schema's `piiPolicy` is delivered to the gateway along with the same internal feed (environment-bound version first, falling back to the global version; not delivered when there is no Schema), and the gateway tightens field by field on top of the ApiKey-level policy—for `email`/`phone` (`allow|mask|drop`) and `ip` (`allow|coarse|drop`) it takes the valid value from the higher layer; `denyKeys`/`maskKeys` lists are unioned with the ApiKey level (tightening in any layer takes effect); if the JSON is invalid or a single field value is invalid, that field falls back to the ApiKey level/gateway default (the same stance as the Schema event-side fail-open). The `piiPolicy` contract: `{"email":"allow|mask|drop","phone":"allow|mask|drop","ip":"allow|coarse|drop","denyKeys":["..."],"maskKeys":["..."]}`, all fields optional. The console `/schemas` page provides a policy column display and an editing entry for DRAFT versions (only DRAFT can be edited; ACTIVE goes through the new-version publish flow).

### Experiments

```http
POST /api/experiments
GET /api/experiments?gameId=&environmentId=&environment=&status=
GET /api/experiments/{id}
PUT /api/experiments/{id}
DELETE /api/experiments/{id}
POST /api/experiments/{id}/publish
POST /api/experiments/{id}/pause
```

**Field descriptions**:
- `gameId`: game ID (required)
- `environmentId`: environment internal ID (either this or `environment`)
- `environment`: environment logical name, such as `dev`/`staging`/`prod` (either this or `environmentId`, compatibility field)
- `name`: experiment name
- `status`: experiment status: `draft`/`running`/`paused`
- `salt`: bucketing salt
- `config`: experiment configuration (variants/targeting/metrics)

**Public config endpoint** (no authentication required):
```http
GET /api/config/{gameId}/{environment}
```

Returns the configuration of all running experiments under that game environment, for SDK use.

Tracking Plan manages:

- Event names.
- Event types.
- Required fields.
- Field types.
- Enum values.
- Cardinality limits.
- Compatibility policy.

### PII Policy (Key level)

There is no standalone `/api/pii-policies` resource. PII policies hang on the API Key:

```http
PUT /api/keys/{keyId}/policy
```

```json
{
  "piiEmail": "mask",
  "piiPhone": "drop",
  "piiIp": "coarse",
  "denyKeys": ["password", "credit_card"],
  "maskKeys": ["email", "mobile"],
  "propsAllowlist": ["level", "vip"],
  "rpm": 600,
  "ipRpm": 300
}
```

The Gateway caches per key and enforces deny / mask / coarse / drop.

### Risk Rule

```http
POST /api/risk-rules
GET /api/risk-rules?gameId=&status=&page=&size=
GET /api/risk-rules/{ruleId}
PUT /api/risk-rules/{ruleId}
DELETE /api/risk-rules/{ruleId}
POST /api/risk-rules/{ruleId}/enable
POST /api/risk-rules/{ruleId}/disable
```

There is no `publish` endpoint—enable/disable is done via `enable` / `disable`. Example (fields follow the shape of `RiskRuleEntity`, and `ruleConditions` is a JSON string):

```json
{
  "gameId": "game_demo",
  "name": "收据复用检测",
  "category": "PAYMENT",
  "riskLevel": "HIGH",
  "ruleType": "THRESHOLD",
  "ruleConditions": "{\"receiptHashDistinctUsersGt\": 1}",
  "actionType": "BLOCK",
  "blockDuration": 1440
}
```

Statuses: `DRAFT` / `ACTIVE` / `PAUSED` / `ARCHIVED` / `DEPRECATED`.

Feature rules with `ruleType=FEATURE` (B5): the shape of `ruleConditions` is
`{"features":[{"scope":"SUBJECT","feature":"gold_gain_1h","op":">","value":500000}]}`,
where `scope` takes `SUBJECT` (player/device subject) or `IP`, and conditions are all ANDed; a feature row that has not been produced counts as unsatisfied (no value never evaluates true).
Write-path structural validation: `feature` must be non-empty, `op ∈ {>, >=, <, <=, ==}`, `value` must be numeric, `scope` must be `SUBJECT|IP`;
any violation rejects the whole list (4xx, message starting with `ruleConditions` or `features`); feature names must fall within the
**known feature list** on the risk-job computation side (`service/RiskFeatures` ↔ risk-job `RiskFeatures`, with tests on both sides pinning the same list)—
rules with unknown names are never found in the `risk_features` table and never match on the production side, so the write path rejects them explicitly
(`FEATURE rule references unknown feature(s) …`, delegated decision 2026-10-16).
Feature values come from `risk_features`, written by risk-job feature jobs over sliding windows (the first 6: gold_gain_1h/24h, device_count,
account_count_per_ip, win_rate, event_count_10m); risk-job parses the `features` array when it pulls rules every 60 seconds;
the read path (replay/RuleFetcher) stays tolerant: malformed shapes or unknown feature names skip the whole row, and existing rows are not rewritten.

### User and RoleAssignment

```http
GET /api/users
POST /api/users
GET /api/users/{userId}
PUT /api/users/{userId}
GET /api/users/{userId}/role-assignments
POST /api/users/{userId}/role-assignments
DELETE /api/users/{userId}/role-assignments?roleId=&gameId=&environment=
```

Authorization request body:

```json
{ "roleId": "role_analyst", "gameId": "game_demo", "environment": null }
```

If both `gameId` / `environment` are absent, the scope is global. Permission scopes: `global` / `game` / `environment`.

Roles (8 seeded in V0.2.3; the `roles` table is the storage location; there is no `owner` / `risk_admin`):

- `operator` (GLOBAL, full permissions)
- `game_admin` (GAME, inherits operator)
- `analyst`, `marketing`, `finance`, `developer` (GAME, inherit game_admin)
- `viewer` (ENVIRONMENT, inherits analyst), `qa` (ENVIRONMENT, inherits developer)

### Audit Log

```http
GET    /api/audit-logs?page=&size=&sortBy=&sortDir=          # audit:read
GET    /api/audit-logs/user/{userId}                         # audit:read
GET    /api/audit-logs/resource/{resourceType}/{resourceId}  # audit:read
GET    /api/audit-logs/action/{action}                       # audit:read
GET    /api/audit-logs/status/{status}                       # audit:read
GET    /api/audit-logs/time-range?start=&end=                # audit:read
GET    /api/audit-logs/game/{gameId}                         # audit:read
GET    /api/audit-logs/search?query=                         # audit:read
GET    /api/audit-logs/{logId}                               # audit:read
GET    /api/audit-logs/failed                                # audit:sensitive
GET    /api/audit-logs/auth                                  # audit:sensitive
GET    /api/audit-logs/sensitive                             # audit:sensitive
GET    /api/audit-logs/statistics?days=                      # audit:sensitive
DELETE /api/audit-logs/cleanup?daysToKeep=                   # audit:manage
```

The response is the audit entity emitted directly (no DTO isolation layer); besides the persisted columns it includes the **derived-at-read** `eventType`—no new column is stored; the determination = `resourceType` × `action` × `details.action` (`AuditLogService` writes keys such as metadata's `action` into `details`, and `gameId` / `environment` are promoted to standalone columns):

| `eventType` | Determination |
|-------------|------|
| `RISK_POLICY_CHANGED` | `risk_rule` × change-type actions (create/update/delete/enable/disable/activate/deactivate) |
| `API_KEY_ROTATED` | `api_key` × update × `details.action=auto_rotate` (written by the auto-rotation executor) |
| `DATA_EXPORTED` | action `EXPORT` (resource-independent) |
| `PERMISSION_CHANGED` | grant_role / revoke_role / grant_permission / revoke_permission, or `user` × bare `GRANT` |
| `EXPERIMENT_PUBLISHED` | `experiment` × update × `details.action=publish` |
| `OTHER` | everything else (including invalid JSON in `details`, missing `action` key, non-string `action`) |

Must be audited (per what the code actually writes):

- API Key creation and disabling; the rotation endpoint corresponding to "API Key rotation" in the checklist does not exist (currently only create / update-policy / delete).
- Tracking Plan deletion; audit writes for publish / rollback (activate / deactivate) are not implemented—currently only deletion has auditing.
- PII policy changes.
- Risk rule publishing, disabling, and threshold changes.
- User authorization changes.
- Risk enforcement actions.

## Gateway Integration

The Gateway currently pulls and caches from the control plane (`/internal/api-keys` and other internal endpoints):

- key context: `game_id`, `environment`, tier (client/server/admin), and enabled status
- PII / props policies and rate limits (`rpm` / `ipRpm`)

On the risk side: rules are pulled by the Flink risk-job once every 60 seconds; block lists go through batch validation. After risk-job hit events flow back through Kafka, RiskEventConsumer adjudicates and persists them (`risk_cases` adjudication state machine OPEN → REVIEW/ALERT/MARK → THROTTLE/BLOCK → RESOLVED, Decision before Action; illegal transitions are refused and archived as decision_rejected; BLOCK-level actions require the input event's trust_level=HIGH—non-HIGH fails closed and degrades to REVIEW). The Tracking Plan is not yet wired into the Gateway's real-time validation chain (schema validation currently uses a built-in JSON Schema), and the cache is in-process with a short TTL.
