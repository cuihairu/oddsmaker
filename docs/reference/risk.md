# Risk API

> 🌐 Language: **English** · 中文 → [risk.zh.md](risk.zh.md)

Risk control management API for risk rules, case management, and real-time risk assessment.

## Endpoint List

| Method | Endpoint | Description | Permission |
|------|------|------|------|
| POST | `/api/risk-rules` | Create a risk rule | `risk_rule:create` |
| GET | `/api/risk-rules` | Get the rule list (paginated, supports conditions such as gameId/status) | `risk_rule:read` (validated when gameId is present) |
| GET | `/api/risk-rules/{id}` | Get rule details | `risk_rule:read` |
| PUT | `/api/risk-rules/{id}` | Update a rule | `risk_rule:update` |
| DELETE | `/api/risk-rules/{id}` | Delete a rule | `risk_rule:delete` |
| POST | `/api/risk-rules/{id}/enable` | Enable a rule | `risk_rule:update` |
| POST | `/api/risk-rules/{id}/disable` | Disable a rule | `risk_rule:update` |

Risk cases (RiskCase) have no independent create/delete API — cases are created automatically by the risk pipeline. Query and disposition entry points:

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/games/{gameId}/risk-cases` | Case list (`status`/`riskLevel` filters; `ruleId`/`disposition` are strategy-lab sample drill-down filters — filtered in memory and truncated within the window of the most recent 2000 cases, `limit` defaults to 100 with a max of 500, newest first) |
| GET | `/api/games/{gameId}/risk-cases/{caseId}` | Case details (evidence/context JSON parsed into an object; on parse failure the raw text is returned; `subjectRiskScore` is a snapshot of the subject's cumulative score — reads the latest row of CH `risk_scores` for the case's target; downgrades to null if not yet scored or CH is unavailable, without blocking review) |
| POST | `/api/games/{gameId}/risk-cases/{caseId}/unblock` | Manual unblock (false-positive disposition; requires `BLOCK` already executed and not yet released; linkedly releases the blocklist records created by this case) |
| GET | `/api/games/{gameId}/risk-lab/rule-stats` | Strategy lab rule review aggregation: one row per rule (case count, false-positive/confirmed-fraud/insufficient-evidence/not-reviewed buckets, false-positive rate with denominator = dispositioned, average review duration, most recent case); rules with zero cases also produce a row; aggregation runs within the most recent 5000 cases |
| POST | `/api/games/{gameId}/risk-lab/replay` | Strategy lab dry-run replay: body `{samples:[{eventId?,amount?,features?}],ruleIds?}`, 1–500 samples scored immediately without persistence; THRESHOLD is strict greater-than threshold, FEATURE conditions are all-AND (fail-closed on missing values), scored per event with semantics aligned to the online pure function; FREQUENCY/VELOCITY/RATIO/DUPLICATE_RECEIPT/AD_REWARD/PATTERN are marked `needsStreaming` and skipped, ANOMALY/MACHINE_LEARNING are marked `notCovered`, and invalid conditions are marked `invalid`; within the same ruleType only the online rule with the highest riskScore is effective (`effectiveHits` converges, `matchedRuleIds` retains the per-rule raw hits) |
| GET | `/api/games/{gameId}/risk-lab/sample-sets` | Strategy lab sample set list (metadata only: name/sampleCount/createdAt etc., no sample contents) |
| POST | `/api/games/{gameId}/risk-lab/sample-sets` | Create a sample set: body `{name,description?,samples}`, name unique per game, samples 1–500 validated the same way as dry-run replay (immutable once archived — delete and recreate), create/delete audited; authorization `risk:manage` |
| GET | `/api/games/{gameId}/risk-lab/sample-sets/{id}` | Sample set details (includes parsed `samples`, for loading into replay) |
| DELETE | `/api/games/{gameId}/risk-lab/sample-sets/{id}` | Delete a sample set (audited); delete and recreate when replacing; authorization `risk:manage` |
| GET | `/api/games/{gameId}/risk-scores?subjectType=&subjectId=` | Latest snapshot of the subject's cumulative risk score (B6 boundary closed): reads CH `risk_scores` (`ORDER BY updated_at DESC LIMIT 1`, correct both before and after ReplacingMergeTree merges), `reasons` parsed as `[{ruleId,contribution}]`; returns `found:false` if never scored; throws `CH_UNAVAILABLE` if CH is not configured; authorization `game:read` |
| GET | `/api/risk-dashboard/recent-cases/{gameId}` | Recent case list |
| GET/POST | `/api/review-queue/...` | Manual review flow (assign, claim, complete, escalate) |
| POST | `/api/block-lists/{blockId}/unblock` | Unblock by block record ID (case-triggered blocks are also in the blocklist table) |

The case review console page is `/risk-cases` (list filtering + detail dialog + false-positive unblock; details include a subject cumulative score row — the target subject's latest cumulative score in CH `risk_scores`, the number of rule contributions, and the snapshot time; the row is hidden if not yet scored), with list/detail authorization `game:read` and unblock authorization `risk:manage`. The strategy lab console page is `/risk-lab` (rule review aggregation + click a rule row to drill down into case samples, disposition chips filter; the dry-run replay panel supports batch dry-run scoring of sample JSON, and the "sample sets" section archives the current samples by name, then loads and fills back in to change rules and compare replay results), with page authorization `game:read` and sample set write/delete `risk:manage`.

## Rule Categories (category)

| Category | Description |
|------|------|
| `PAYMENT` | Payment risk |
| `BEHAVIOR` | Behavioral anomaly |
| `ACCOUNT` | Account risk |
| `DEVICE` | Device clustering |
| `NETWORK` | Network/IP anomaly |
| `AUTOMATION` | Scripts and cheats |
| `COLLUSION` | Organized fraud |
| `ECONOMY` | Economy system anomaly |

Rule types (ruleType), 7 kinds: `THRESHOLD`, `FREQUENCY`, `PATTERN`, `VELOCITY`, `RATIO`, `ANOMALY`, `MACHINE_LEARNING`.

## Risk Levels

| Level | Description |
|------|------|
| `LOW` | Low risk |
| `MEDIUM` | Medium risk |
| `HIGH` | High risk |
| `CRITICAL` | Critical risk |

The level does not directly determine the disposition action; disposition is determined by the rule's `actionType`, `riskScore`, `triggerThreshold`, and other fields.

## Create a Risk Rule

The request body uses `RiskRuleEntity` fields. Note that `ruleConditions` is a JSON string (not a nested object), and `category`/`riskLevel`/`actionType` are uppercase enums:

```http
POST /api/risk-rules
Content-Type: application/json
Authorization: Bearer {token}

{
  "gameId": "game_abc123",
  "name": "高频支付检测",
  "category": "PAYMENT",
  "riskLevel": "HIGH",
  "ruleType": "THRESHOLD",
  "ruleConditions": "{\"event_type\": \"purchase\", \"amount\": \">1000\", \"frequency\": \">10/hour\"}",
  "actionType": "BLOCK",
  "blockDuration": 1440
}
```

**Response:**
```json
{
  "id": "rule_abc123",
  "gameId": "game_abc123",
  "name": "高频支付检测",
  "category": "PAYMENT",
  "riskLevel": "HIGH",
  "ruleType": "THRESHOLD",
  "actionType": "BLOCK",
  "status": "ACTIVE",
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## Risk Cases

Cases are written to `risk_cases` by the risk pipeline, with these key fields:

```json
{
  "id": "rc_abc123",
  "caseNumber": "CASE_1730000000000",
  "riskRuleId": "rule_abc123",
  "gameId": "game_abc123",
  "targetType": "user_id",
  "targetId": "user_123",
  "riskLevel": "HIGH",
  "riskScore": 85,
  "status": "BLOCK",
  "actionTaken": "BLOCK",
  "executionStatus": "EXECUTED",
  "executedAt": "2024-01-01T00:00:00Z",
  "disposition": "confirmed_fraud"
}
```

`targetType` takes `user_id` / `device_id` / `player_id` / `ip`; `status` is the decision status (hierarchical forward progression `OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED`, OPEN does not jump directly to RESOLVED); `executionStatus` takes `PENDING` / `EXECUTED` / `FAILED` / `CANCELLED` / `APPEALED`. `caseNumber` is generated by the risk pipeline, formatted as `CASE_` + a millisecond timestamp (epoch millis, e.g. `CASE_1730000000000`).

## Unblock

There are two unblock paths: by block record ID via the blocklist API; by case via `POST /api/games/{gameId}/risk-cases/{caseId}/unblock` (on the case side, still-active blocklist records created by that case are released in a linked action, and a case-level audit log is recorded). By block record ID:

```http
POST /api/block-lists/{blockId}/unblock
Content-Type: application/json
Authorization: Bearer {token}

{
  "reason": "误报，用户行为正常"
}
```

## Real-Time Risk Assessment

Risk assessment is executed in real time by the Flink risk-job: it consumes the event stream and matches against the rules pushed by Control (pulled every 60 seconds). On a hit, it writes `risk_events` / `risk_scores` (subject cumulative score, single-writer on the assessment side) to ClickHouse and sends the event back to Control (Kafka `oddsmaker.risk_events`); Control's RiskEventConsumer writes `risk_cases` according to the decision state machine (Decision precedes Action: disposition actions for illegal transitions are rejected), and links the blocklist, review queue, and Webhook, archiving dispositions to `risk_actions`. BLOCK-level actions require the input event to have `trust_level=HIGH` (a dual-gate with identical semantics on both the risk-job and Control sides; non-HIGH fails closed and downgrades to REVIEW).

## Risk Dashboard

Under `/api/risk-dashboard`, read-only aggregations are provided by `gameId` for trends, rule hits, block statistics, high-risk targets, recent cases, and review queue statistics, plus `/api/risk-metrics` outputs distributions by severity and action type.
