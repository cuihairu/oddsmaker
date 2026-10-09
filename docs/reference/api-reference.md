# Oddsmaker Control Service API Reference

Oddsmaker is a single-company, multi-game analytics and risk-control platform. The control service manages games, environments, storage profiles, API keys, tracking plans, risk rules, users, role assignments, and audit logs.

Oddsmaker does not model `Organization` or `Tenant` as target business resources. The core business boundary is `game_id + environment`, while physical routing is controlled by `storage_profile`.

This page is a curated index, not the complete surface — every controller also serves an OpenAPI document at `GET /v3/api-docs` (Swagger UI at `/swagger-ui.html`). Resource groups not detailed below are listed under "Other resource groups" near the end.

## Authentication

Most endpoints accept a bearer token from `POST /api/auth/login`; bootstrap/admin-only endpoints (user creation, key issuance, key policy) accept `x-admin-token`:

```http
Authorization: Bearer YOUR_TOKEN
x-admin-token: YOUR_ADMIN_TOKEN
```

## Resources

### Games

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/games` | Create a game |
| GET | `/api/games` | List games |
| GET | `/api/games/{gameId}` | Get game details |
| PUT | `/api/games/{gameId}` | Update a game |
| DELETE | `/api/games/{gameId}` | Delete or archive a game |
| POST | `/api/games/{gameId}/publish` | Move a game to live status |
| POST | `/api/games/{gameId}/unpublish` | Move a live game to maintenance |

### Environments

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/games/{gameId}/environments` | Create an environment |
| GET | `/api/games/{gameId}/environments` | List environments |
| GET | `/api/games/{gameId}/environments/{environmentName}` | Get environment config |
| PUT | `/api/games/{gameId}/environments/{environmentName}` | Update environment config |
| DELETE | `/api/games/{gameId}/environments/{environmentName}` | Delete an environment |

Recommended environments: `dev`, `staging`, `prod`.

An environment is a logical lifecycle stage, not a synonym for a dedicated database.

### Storage Profiles

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/storage-profiles` | Create a storage routing profile |
| GET | `/api/storage-profiles` | List storage profiles |
| GET | `/api/storage-profiles/{profileId}` | Get storage profile details |
| PUT | `/api/storage-profiles/{profileId}` | Update routing backends or isolation strategy |
| DELETE | `/api/storage-profiles/{profileId}` | Delete a storage profile |

### API Keys

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/api-keys` | Create an API key bound to `gameId + environmentId` |
| GET | `/api/api-keys?gameId=&environmentId=` | List API keys |
| GET | `/api/api-keys/{keyId}` | Get API key details |
| PUT | `/api/api-keys/{keyId}` | Update limits or policy bindings |
| DELETE | `/api/api-keys/{keyId}` | Delete an API key |
| POST | `/api/keys` | Legacy compatible create endpoint |
| GET | `/api/keys?gameId=&environmentId=` | Legacy compatible query endpoint |
| GET | `/api/keys/{keyId}` | Legacy compatible detail endpoint |
| PUT | `/api/keys/{keyId}/policy` | Legacy compatible policy update endpoint |
| DELETE | `/api/keys/{keyId}` | Legacy compatible delete endpoint |

Key types:

- `client`: public write-only key for client SDKs.
- `server`: server-to-server key with one-time secret material for HMAC.
- `admin`: internal service key.

### Tracking Plans

Plans are scoped to a game (not per environment). A plan is activated or deactivated; there is no separate publish/rollback endpoint.

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/games/{gameId}/tracking-plans` | Create a plan (draft) |
| GET | `/api/games/{gameId}/tracking-plans` | List plans for a game |
| GET | `/api/games/{gameId}/tracking-plans/active` | Get the active plan |
| GET | `/api/games/{gameId}/tracking-plans/environment/{environmentId}` | Get the active plan for an environment |
| GET | `/api/games/{gameId}/tracking-plans/{trackingPlanId}` | Get plan details |
| PUT | `/api/games/{gameId}/tracking-plans/{trackingPlanId}` | Update a draft plan |
| DELETE | `/api/games/{gameId}/tracking-plans/{trackingPlanId}` | Delete a plan |
| POST | `/api/games/{gameId}/tracking-plans/{trackingPlanId}/activate` | Activate a plan |
| POST | `/api/games/{gameId}/tracking-plans/{trackingPlanId}/deactivate` | Deactivate a plan |
| POST/GET | `/api/games/{gameId}/tracking-plans/{trackingPlanId}/events` | Register / list events in the plan |
| POST | `/api/games/{gameId}/tracking-plans/{trackingPlanId}/properties` | Register a property definition |

### Experiments

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/experiments` | Create an experiment |
| GET | `/api/experiments?gameId=&environmentId=&environment=&status=` | List experiments |
| GET | `/api/experiments/{id}` | Get experiment details |
| PUT | `/api/experiments/{id}` | Update an experiment |
| DELETE | `/api/experiments/{id}` | Delete an experiment |
| POST | `/api/experiments/{id}/publish` | Start an experiment |
| POST | `/api/experiments/{id}/pause` | Pause an experiment |
| GET | `/api/experiments/{id}/assign?userId=&deviceId=` | Deterministic variant assignment |
| POST | `/api/experiments/{id}/metrics` | Receive a metrics snapshot |
| POST | `/api/experiments/{id}/metrics/aggregate` | Aggregate metrics over a window |
| GET | `/api/experiments/{id}/results` | Results with lift/CI/p-value/SRM |

**Public endpoint** (no authentication):
| GET | `/api/config/{gameId}/{environment}` | Get running experiment configs for SDK |

**Fields**:
- `environmentId`: internal environment ID (alternative to `environment`)
- `environment`: logical environment name like `dev`/`staging`/`prod` (alternative to `environmentId`)

### PII And Sampling Policies

There is no standalone PII-policy resource. Ingest policy is bound per API key and updated through the key policy endpoint:

| Method | Endpoint | Description |
|---|---|---|
| PUT | `/api/keys/{keyId}/policy` | Set PII handling (`piiEmail`/`piiPhone`/`piiIp`), props allowlist, deny/mask keys, and rate limits (`rpm`, `ipRpm`) |

The gateway enforces these fields on `/v1/batch`. See the [collect API](/zh/reference/api) for the enforcement semantics.

### Risk Rules

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/risk-rules` | Create a risk rule |
| GET | `/api/risk-rules?gameId=&environmentId=&status=` | List rules (multi-condition, paginated) |
| GET | `/api/risk-rules/{ruleId}` | Get risk rule details |
| PUT | `/api/risk-rules/{ruleId}` | Update a rule |
| DELETE | `/api/risk-rules/{ruleId}` | Delete a rule |
| POST | `/api/risk-rules/{ruleId}/enable` | Enable a rule |
| POST | `/api/risk-rules/{ruleId}/disable` | Disable a rule |

The risk-job picks up enabled rules from `GET /api/risk-dashboard/rules/{gameId}` on a timer (60s default), so rule changes apply without restarts.

### Block Lists

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/block-lists/check?gameId=&targetType=&targetValue=` | Check if target is blocked |
| GET | `/api/block-lists/active/{gameId}` | List active blocks for a game |
| GET | `/api/block-lists/{blockId}` | Get block details |
| POST | `/api/block-lists` | Add a block |
| POST | `/api/block-lists/{blockId}/unblock` | Remove a block |
| POST | `/api/block-lists/batch-unblock` | Batch remove blocks |
| POST | `/api/block-lists/from-risk-case/{riskCaseId}` | Create block from risk case |
| GET | `/api/block-lists/stats/{gameId}` | Get block statistics |
| GET | `/api/block-lists/search/{gameId}?query=` | Search blocks |
| GET | `/api/block-lists/by-type/{gameId}/{targetType}` | List blocks by type |

**Target types**: `device_id`, `user_id`, `player_id`, `ip`, `ip_range`, `account_id`

**Block types**: `HARD` (complete block), `SOFT` (feature restriction), `TEMPORARY` (time-limited), `SHADOW` (silent restriction)

**Block categories**: `fraud`, `cheating`, `abuse`, `tos_violation`, `security`

### Flink Jobs

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/flink-jobs` | Create a Flink job |
| GET | `/api/flink-jobs/{jobId}` | Get job details |
| GET | `/api/flink-jobs/game/{gameId}` | List jobs for a game |
| GET | `/api/flink-jobs/running/{gameId}` | List running jobs |
| POST | `/api/flink-jobs/{jobId}/deploy` | Deploy job to Flink cluster |
| POST | `/api/flink-jobs/{jobId}/stop` | Stop a running job |
| POST | `/api/flink-jobs/{jobId}/refresh` | Refresh job status from the Flink cluster |
| GET | `/api/flink-jobs/stats/{gameId}` | Get job statistics |
| GET | `/api/flink-jobs/{jobId}/config` | Get job configuration |
| GET | `/api/flink-jobs/{jobId}/rules` | Get associated risk rules |
| POST | `/api/flink-jobs/{jobId}/metrics` | Update job metrics |

**Job types**: `RISK_EVALUATION`, `FRAUD_DETECTION`, `ANOMALY_DETECTION`, `REALTIME_AGGREGATION`, `PATTERN_MATCHING`

**Job statuses**: `DRAFT`, `DEPLOYING`, `RUNNING`, `STOPPING`, `STOPPED`, `FAILED`, `PAUSED`

### Risk Dashboard

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/risk-dashboard/overview/{gameId}?since=` | Get risk overview statistics |
| GET | `/api/risk-dashboard/trends/{gameId}?since=&intervalHours=` | Get risk trends over time |
| GET | `/api/risk-dashboard/high-risk-targets/{gameId}?since=&limit=` | Get high-risk targets list |
| GET | `/api/risk-dashboard/rule-performance/{gameId}` | Get rule performance metrics |
| GET | `/api/risk-dashboard/block-stats/{gameId}` | Get block statistics |
| GET | `/api/risk-dashboard/job-stats/{gameId}` | Get Flink job statistics |
| GET | `/api/risk-dashboard/dashboard/{gameId}?since=` | Get complete dashboard data |
| GET | `/api/risk-dashboard/rules/{gameId}` | Active rules for a game (read by the risk-job) |
| GET | `/api/risk-dashboard/recent-cases/{gameId}?limit=` | Get recent risk cases |
| GET | `/api/risk-dashboard/review-queue-stats/{gameId}` | Get review queue statistics |

**Dashboard components**:
- Overview: Total cases, high-risk cases, pending reviews, active blocks
- Trends: Time-series data for cases, blocks, and reviews
- High-risk targets: Most frequently flagged targets
- Rule performance: Trigger counts, block rates, efficiency metrics
- Block stats: Active blocks by type, category, and scope
- Job stats: Flink job status and throughput metrics

### Webhooks

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/webhooks/game/{gameId}` | List webhook configurations for a game |
| GET | `/api/webhooks/configs/{configId}` | Get webhook configuration details |
| GET | `/api/webhooks/logs/{configId}` | Get webhook delivery logs |
| GET | `/api/webhooks/stats/{gameId}` | Get webhook statistics |
| POST | `/api/webhooks/test/{configId}` | Test webhook endpoint |
| POST/PUT/DELETE | `/api/webhooks/game/{gameId}/configs` | Manage webhook configurations |

**Webhook authentication types**: `none`, `basic`, `bearer`, `api_key` (values outside this set are rejected at config time)

**Per-config timeout**: `timeoutSeconds` (1–300, default 30) drives the read timeout of the delivery HTTP client; out-of-range values are clamped, omitted → global default (`oddsmaker.webhook.read-timeout-ms`). The connect timeout is always global.

**Webhook statuses**: `ACTIVE`, `INACTIVE`, `PAUSED`, `FAILED`

**Event types**: `risk_case`, `block`, `alert`, `review`

**Delivery statuses**: `PENDING`, `SENDING`, `SUCCESS`, `FAILED`, `RETRYING`, `TIMEOUT`, `CANCELLED`

### Review Queue

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/review-queue/game/{gameId}` | Get review queue for a game |
| GET | `/api/review-queue/pending/{gameId}` | Get pending review items |
| GET | `/api/review-queue/high-priority/{gameId}?minPriority=` | Get high-priority items |
| POST | `/api/review-queue/{queueItemId}/assign` | Assign reviewer to item |
| POST | `/api/review-queue/{queueItemId}/claim` | Claim item for review |
| POST | `/api/review-queue/{queueItemId}/start` | Start review process |
| POST | `/api/review-queue/{queueItemId}/complete` | Complete review with disposition |
| POST | `/api/review-queue/{queueItemId}/escalate` | Escalate item to higher authority |
| POST | `/api/review-queue/{queueItemId}/cancel` | Cancel review |
| GET | `/api/review-queue/reviewer/{reviewer}` | Get reviewer's assigned items |
| GET | `/api/review-queue/stats/{gameId}` | Get queue statistics |

**Review statuses**: `PENDING`, `ASSIGNED`, `CLAIMED`, `IN_REVIEW`, `COMPLETED`, `ESCALATED`, `CANCELLED`

**Dispositions**: `confirmed_fraud`, `confirmed_benign`, `inconclusive`, `needs_investigation`

**Queue types**: `default`, `high_priority`, `fraud`, `cheating`

**Categories**: `fraud`, `cheating`, `abuse`, `tos_violation`, `suspicious`

### Reports

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/reports` | Create a custom report |
| GET | `/api/reports/{reportId}` | Get report details |
| GET | `/api/reports/game/{gameId}` | List reports for a game |
| GET | `/api/reports/published/{gameId}` | List published reports |
| POST | `/api/reports/{reportId}/publish` | Publish a report |
| POST | `/api/reports/{reportId}/execute` | Execute a report |
| GET | `/api/reports/{reportId}/executions` | Get report execution history |
| GET | `/api/reports/{reportId}/stats` | Get report statistics |
| GET | `/api/reports/overview/{gameId}` | Get game report overview |
| GET | `/api/reports/search/{gameId}?query=` | Search reports |
| GET | `/api/reports/popular/{gameId}` | Get popular reports |
| GET | `/api/reports/recent/{gameId}` | Get recently run reports |

**Report types**: `CUSTOM`, `TEMPLATE`, `SYSTEM`, `SCHEDULED`, `ADHOC`

**Report statuses**: `DRAFT`, `PUBLISHED`, `SCHEDULED`, `ARCHIVED`, `DELETED`

**Execution statuses**: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `TIMEOUT`

**Chart types**: `line`, `bar`, `pie`, `table`, `heatmap`

**Report categories**: `analytics`, `retention`, `funnel`, `revenue`, `risk`

### Cohorts

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/cohorts` | Create a cohort |
| GET | `/api/cohorts/{cohortId}` | Get cohort details |
| GET | `/api/cohorts/game/{gameId}` | List cohorts for a game |
| GET | `/api/cohorts/completed/{gameId}` | List completed cohorts |
| POST | `/api/cohorts/{cohortId}/calculate` | Calculate cohort analysis |
| GET | `/api/cohorts/{cohortId}/results` | Get cohort results |
| GET | `/api/cohorts/stats/{gameId}` | Get cohort statistics |
| GET | `/api/cohorts/search/{gameId}?query=` | Search cohorts |
| GET | `/api/cohorts/recent/{gameId}` | Get recent cohorts |

**Cohort types**: `ACQUISITION`, `BEHAVIORAL`, `CUSTOM`, `SEGMENTED`

**Cohort statuses**: `PENDING`, `CALCULATING`, `COMPLETED`, `FAILED`, `ARCHIVED`

**Analysis types**: `retention`, `engagement`, `revenue`, `churn`

**Time units**: `day`, `week`, `month`

### Data Export

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/exports` | Create an export job |
| GET | `/api/exports/{exportJobId}` | Get export job details |
| GET | `/api/exports/user/{userId}` | List user's export jobs |
| GET | `/api/exports/game/{gameId}` | List export jobs for a game |
| POST | `/api/exports/{exportJobId}/process` | Process export job |
| POST | `/api/exports/{exportJobId}/cancel` | Cancel export job |
| GET | `/api/exports/stats/{gameId}` | Get export statistics |
| GET | `/api/exports/user-stats/{userId}` | Get user export statistics |

**Export types**: `events`, `users`, `sessions`, `reports`, `risk_cases`

**Export formats**: `csv`, `excel`, `json`, `parquet`

**Export statuses**: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELLED`, `EXPIRED`

**Compression types**: `none`, `gzip`, `zip`

### Integrations

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/integrations` | Create an integration |
| GET | `/api/integrations/{integrationId}` | Get integration details |
| GET | `/api/integrations/game/{gameId}` | List integrations for a game |
| GET | `/api/integrations/game/{gameId}/type/{type}` | List integrations by type |
| PUT | `/api/integrations/{integrationId}` | Update an integration |
| POST | `/api/integrations/{integrationId}/verify` | Verify integration connection |
| POST | `/api/integrations/{integrationId}/enable` | Enable an integration |
| POST | `/api/integrations/{integrationId}/disable` | Disable an integration |
| DELETE | `/api/integrations/{integrationId}` | Delete an integration |
| GET | `/api/integrations/{integrationId}/logs` | Get integration call logs |
| GET | `/api/integrations/stats/{gameId}` | Get integration statistics |
| GET | `/api/integrations/{integrationId}/call-stats` | Get integration call statistics |
| POST | `/api/integrations/{integrationId}/trigger` | Trigger an integration call |
| POST | `/api/integrations/game/{gameId}/trigger-batch` | Trigger multiple integrations |

**Integration types**: `SLACK`, `DISCORD`, `EMAIL_SMTP`, `EMAIL_SES`, `EMAIL_SENDGRID`, `PAYMENT_STRIPE`, `PAYMENT_PAYPAL`, `AUTH_OAUTH`, `AUTH_SAML`, `WEBHOOK`, `CUSTOM`, `ALERT_PAGERDUTY`, `ALERT_DATADOG`, `ANALYTICS_GA`

**Auth types**: `NONE`, `API_KEY`, `BEARER_TOKEN`, `BASIC_AUTH`, `OAUTH2`, `HMAC`, `MUTUAL_TLS`

**Integration statuses**: `ACTIVE`, `INACTIVE`, `VERIFYING`, `FAILED`, `DISABLED`

**Event types**: `risk_case`, `block`, `alert`, `review`, `system`

### Rate Limits and Quotas

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/rate-limits` | Create a rate limit rule |
| GET | `/api/rate-limits/{ruleId}` | Get rate limit details |
| GET | `/api/rate-limits/game/{gameId}` | List rate limits for a game |
| PUT | `/api/rate-limits/{ruleId}` | Update a rate limit |
| DELETE | `/api/rate-limits/{ruleId}` | Delete a rate limit |
| GET | `/api/rate-limits/stats/{gameId}` | Get rate limit statistics |
| POST | `/api/rate-limits/quotas` | Create a quota |
| GET | `/api/rate-limits/quotas/check` | Check quota availability |
| POST | `/api/rate-limits/quotas/update-usage` | Update quota usage |
| GET | `/api/rate-limits/quotas/stats/{gameId}` | Get quota statistics |

**Rate limit scopes**: `GLOBAL`, `GAME`, `API_KEY`, `ENDPOINT`, `USER`

**Rate limit algorithms**: `FIXED_WINDOW`, `SLIDING_WINDOW`, `TOKEN_BUCKET`, `LEAKY_BUCKET`

**Window types**: `SECOND`, `MINUTE`, `HOUR`, `DAY`, `WEEK`, `MONTH`

**Resource types**: `EVENTS_PER_DAY`, `EVENTS_PER_MONTH`, `STORAGE_GB`, `API_CALLS_PER_DAY`, `USERS`, `CONCURRENT_SESSIONS`, `REPORTS_PER_MONTH`, `EXPORTS_PER_MONTH`, `CUSTOM_REPORTS`, `INTEGRATIONS`, `RETENTION_DAYS`

### Health Monitoring

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/health/overview` | Get system health overview |
| GET | `/api/health/checks` | List all health checks |
| GET | `/api/health/checks/{checkName}` | Get health check details |
| POST | `/api/health/checks/{checkName}/run` | Run a health check |
| GET | `/api/health/live` | Liveness probe (public) |
| GET | `/api/health/ready` | Readiness probe (public) |
| GET | `/api/health/metrics/{type}` | Get recent metrics |
| GET | `/api/health/alerts/active` | Get active alerts |
| GET | `/api/health/alerts/stats` | Get alert statistics |
| POST | `/api/health/alerts/{alertId}/acknowledge` | Acknowledge an alert |
| POST | `/api/health/alerts/{alertId}/resolve` | Resolve an alert |

**Health check types**: `DATABASE`, `STORAGE`, `CACHE`, `QUEUE`, `EXTERNAL_API`, `FLINK_CLUSTER`, `KAFKA`, `ELASTICSEARCH`, `SYSTEM_RESOURCE`

**Health statuses**: `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `DOWN`, `UNKNOWN`

**Metric types**: `CPU_USAGE`, `MEMORY_USAGE`, `DISK_USAGE`, `NETWORK_IN`, `NETWORK_OUT`, `DISK_IO_READ`, `DISK_IO_WRITE`, `ACTIVE_CONNECTIONS`, `QUEUE_DEPTH`, `THREAD_COUNT`, `GC_COUNT`, `GC_TIME`, `REQUEST_RATE`, `ERROR_RATE`, `LATENCY_P50`, `LATENCY_P95`, `LATENCY_P99`

**Alert types**: `SYSTEM_DOWN`, `HIGH_CPU`, `HIGH_MEMORY`, `HIGH_DISK`, `SLOW_RESPONSE`, `HIGH_ERROR_RATE`, `QUEUE_BUILDUP`, `CONNECTION_LEAK`, `DATABASE_ISSUE`, `STORAGE_ISSUE`, `EXTERNAL_SERVICE`, `SECURITY_EVENT`, `QUOTA_EXCEEDED`, `ANOMALY_DETECTED`

**Alert severities**: `INFO`, `WARNING`, `ERROR`, `CRITICAL`, `EMERGENCY`

**Alert statuses**: `OPEN`, `ACKNOWLEDGED`, `INVESTIGATING`, `RESOLVED`, `CLOSED`, `SNOOZED`

### System Management

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/system/maintenances` | Create a maintenance window |
| GET | `/api/system/maintenances/active` | Get active maintenance windows |
| GET | `/api/system/maintenances/upcoming` | Get upcoming maintenance windows |
| POST | `/api/system/maintenances/{windowId}/start` | Start a maintenance window |
| POST | `/api/system/maintenances/{windowId}/complete` | Complete a maintenance window |
| POST | `/api/system/maintenances/{windowId}/cancel` | Cancel a maintenance window |
| GET | `/api/system/maintenances/check/{gameId}` | Check if game is in maintenance |
| GET | `/api/system/configs` | Get all system configs |
| GET | `/api/system/configs/public` | Get public configs (no auth) |
| GET | `/api/system/configs/{configKey}` | Get config value |
| PUT | `/api/system/configs/{configKey}` | Set config value |
| GET | `/api/system/features` | Get all feature flags |
| GET | `/api/system/features/enabled` | Get enabled feature flags |
| POST | `/api/system/features` | Create a feature flag (always starts DISABLED; `featureflag:manage`) |
| PUT | `/api/system/features/{flagKey}` | Full-field update (provided-only semantics; status/percentage excluded — use enable/disable/percentage) |
| GET | `/api/system/features/{flagKey}/check` | Check if feature is enabled |
| POST | `/api/system/features/{flagKey}/enable` | Enable a feature |
| POST | `/api/system/features/{flagKey}/disable` | Disable a feature |
| POST | `/api/system/features/{flagKey}/percentage` | Set feature percentage |
| GET | `/api/system/status` | Get system status |

**Maintenance types**: `SCHEDULED`, `EMERGENCY`, `ROLLING`, `PATCHING`

**Maintenance statuses**: `SCHEDULED`, `PENDING`, `IN_PROGRESS`, `PAUSED`, `COMPLETED`, `CANCELLED`, `EXTENDED`

**Impact scopes**: `GLOBAL`, `GAME`, `ENVIRONMENT`, `SERVICE`, `REGION`

**Config types**: `SYSTEM`, `SECURITY`, `PERFORMANCE`, `INTEGRATION`, `NOTIFICATION`, `RETENTION`, `LIMIT`, `FEATURE`, `CUSTOM`

**Feature flag types**: `BOOLEAN`, `PERCENTAGE`, `WHITELIST`, `BLACKLIST`, `CONDITIONAL`

**Feature flag statuses**: `ENABLED`, `DISABLED`, `CONDITIONAL`, `STAGED_ROLLOUT`

### Advanced Security

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/security/mfa/enable` | Enable MFA for user |
| POST | `/api/security/mfa/verify` | Verify and activate MFA |
| POST | `/api/security/mfa/disable` | Disable MFA |
| POST | `/api/security/mfa/validate` | Validate MFA code |
| GET | `/api/security/mfa/user/{userId}` | Get user MFA configs |
| GET | `/api/security/mfa/user/{userId}/enabled` | Check if user has MFA enabled |
| POST | `/api/security/sso/configs` | Create SSO config |
| GET | `/api/security/sso/configs/active` | Get active SSO configs |
| POST | `/api/security/sso/configs/{configId}/activate` | Activate SSO config |
| POST | `/api/security/sso/callback` | SSO login callback |
| POST | `/api/security/sessions/create` | Create security session |
| GET | `/api/security/sessions/validate` | Validate session |
| POST | `/api/security/sessions/{sessionId}/terminate` | Terminate session |
| POST | `/api/security/sessions/user/{userId}/terminate-all` | Terminate all user sessions |
| GET | `/api/security/policies/password` | Get password policies |
| GET | `/api/security/policies/session` | Get session policies |
| GET | `/api/security/policies/mfa` | Get MFA policies |
| GET | `/api/security/policies/mfa/required` | Check if MFA is required |

**MFA methods**: `TOTP`, `SMS`, `EMAIL`, `HARDWARE_TOKEN`, `BIOMETRIC`, `PUSH`, `BACKUP_CODE`

**MFA statuses**: `DISABLED`, `ENABLED`, `PENDING`, `LOCKED`, `RECOVERY_MODE`

**SSO protocols**: `SAML2`, `OAUTH2`, `OIDC`, `CAS`

**SSO statuses**: `DISABLED`, `ACTIVE`, `TESTING`, `ERROR`

**Session statuses**: `ACTIVE`, `EXPIRED`, `REVOKED`, `TERMINATED`

**Auth methods**: `PASSWORD`, `MFA`, `SSO`, `API_KEY`, `OAUTH`

**Policy types**: `PASSWORD`, `SESSION`, `MFA`, `ACCESS`, `API`

### Data Pipelines

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/pipelines` | Create a pipeline |
| GET | `/api/pipelines/{pipelineId}` | Get pipeline details |
| GET | `/api/pipelines/game/{gameId}` | List pipelines for a game |
| GET | `/api/pipelines/{pipelineId}/jobs` | Get pipeline jobs |
| GET | `/api/pipelines/{pipelineId}/stats` | Get pipeline statistics |
| POST | `/api/pipelines/{pipelineId}/execute` | Execute a pipeline |
| POST | `/api/pipelines/{pipelineId}/activate` | Activate a pipeline |
| POST | `/api/pipelines/{pipelineId}/pause` | Pause a pipeline |
| POST | `/api/pipelines/{pipelineId}/stop` | Stop a pipeline |
| POST | `/api/pipelines/quality-rules` | Create a quality rule |
| GET | `/api/pipelines/quality-rules/game/{gameId}` | List quality rules for a game |
| GET | `/api/pipelines/{pipelineId}/quality-rules` | Get pipeline quality rules |

**Pipeline types**: `BATCH`, `STREAMING`, `HYBRID`, `REALTIME`, `ETL`

**Pipeline statuses**: `DRAFT`, `ACTIVE`, `PAUSED`, `STOPPED`, `FAILED`, `ARCHIVED`

**Job statuses**: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `TIMEOUT`, `RETRYING`

**Quality rule types**: `SCHEMA`, `COMPLETENESS`, `UNIQUENESS`, `ACCURACY`, `CONSISTENCY`, `TIMELINESS`, `VALIDITY`, `RANGE`, `PATTERN`, `REFERENCE`

**Quality severities**: `INFO`, `WARNING`, `ERROR`, `CRITICAL`

### Machine Learning Models

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/ml-models` | Create an ML model |
| GET | `/api/ml-models/{modelId}` | Get model details |
| GET | `/api/ml-models/game/{gameId}` | List models for a game |
| GET | `/api/ml-models/deployed` | List deployed models |
| PUT | `/api/ml-models/{modelId}` | Update model configuration |
| POST | `/api/ml-models/{modelId}/archive` | Archive a model |
| DELETE | `/api/ml-models/{modelId}` | Delete a model |
| POST | `/api/ml-models/{modelId}/training` | Create a training job |
| POST | `/api/ml-models/training/{trainingId}/start` | Start training |
| PUT | `/api/ml-models/training/{trainingId}/progress` | Update training progress |
| POST | `/api/ml-models/training/{trainingId}/complete` | Complete training |
| POST | `/api/ml-models/training/{trainingId}/fail` | Mark training as failed |
| POST | `/api/ml-models/training/{trainingId}/cancel` | Cancel training |
| GET | `/api/ml-models/training/{trainingId}` | Get training job details |
| GET | `/api/ml-models/{modelId}/training` | Get training history |
| POST | `/api/ml-models/{modelId}/deploy` | Deploy a model |
| POST | `/api/ml-models/{modelId}/ab-test` | Configure A/B test |
| DELETE | `/api/ml-models/{modelId}/ab-test` | Stop A/B test |
| POST | `/api/ml-models/predictions` | Record a prediction |
| POST | `/api/ml-models/predictions/{predictionId}/complete` | Complete a prediction |
| POST | `/api/ml-models/predictions/{predictionId}/fail` | Mark prediction as failed |
| POST | `/api/ml-models/predictions/{predictionId}/feedback` | Add prediction feedback |
| GET | `/api/ml-models/predictions/{predictionId}` | Get prediction details |
| GET | `/api/ml-models/{modelId}/predictions` | Get prediction history |
| GET | `/api/ml-models/{modelId}/predictions/range` | Get predictions by time range |
| GET | `/api/ml-models/{modelId}/stats` | Get model statistics |
| GET | `/api/ml-models/stats/global` | Get global ML statistics |
| GET | `/api/ml-models/{modelId}/drift` | Detect model drift |

**Model types**: `CLASSIFICATION`, `REGRESSION`, `CLUSTERING`, `ANOMALY_DETECTION`, `RECOMMENDATION`, `TIME_SERIES`, `NLP`, `CUSTOM`

**Model statuses**: `DRAFT`, `TRAINING`, `EVALUATING`, `DEPLOYED`, `STAGING`, `ARCHIVED`, `FAILED`

**Training statuses**: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `TIMEOUT`

**Prediction statuses**: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `TIMEOUT`, `CACHED`

**Feedback types**: `CORRECT`, `INCORRECT`, `PARTIAL`, `UNKNOWN`

**Model frameworks**: `TENSORFLOW`, `PYTORCH`, `SCIKIT_LEARN`, `XGBOOST`, `LIGHTGBM`, `ONNX`, `CUSTOM`

### Developer Tools

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/developer/sdk-keys` | Create an SDK key |
| GET | `/api/developer/sdk-keys/{keyId}` | Get SDK key details |
| GET | `/api/developer/sdk-keys/game/{gameId}` | List SDK keys for a game |
| PUT | `/api/developer/sdk-keys/{keyId}` | Update SDK key configuration |
| POST | `/api/developer/sdk-keys/{keyId}/suspend` | Suspend an SDK key |
| POST | `/api/developer/sdk-keys/{keyId}/activate` | Activate an SDK key |
| POST | `/api/developer/sdk-keys/{keyId}/revoke` | Revoke an SDK key |
| DELETE | `/api/developer/sdk-keys/{keyId}` | Delete an SDK key |
| GET | `/api/developer/sdk-keys/{publicKey}/validate` | Validate an SDK key |
| POST | `/api/developer/sdk-versions` | Create an SDK version |
| GET | `/api/developer/sdk-versions/{versionId}` | Get SDK version details |
| GET | `/api/developer/sdk-versions/platform/{platform}` | List versions for a platform |
| GET | `/api/developer/sdk-versions/platform/{platform}/latest` | Get latest version for a platform |
| POST | `/api/developer/sdk-versions/{versionId}/release` | Release a version |
| POST | `/api/developer/sdk-versions/{versionId}/deprecate` | Deprecate a version |
| POST | `/api/developer/sdk-versions/{versionId}/retire` | Retire a version |
| POST | `/api/developer/sdk-versions/{versionId}/download` | Record a download |
| POST | `/api/developer/telemetry-configs` | Create a telemetry config |
| GET | `/api/developer/telemetry-configs/{configId}` | Get telemetry config details |
| GET | `/api/developer/telemetry-configs/game/{gameId}` | List telemetry configs for a game |
| GET | `/api/developer/telemetry-configs/effective` | Get effective telemetry config |
| PUT | `/api/developer/telemetry-configs/{configId}` | Update telemetry config |
| POST | `/api/developer/telemetry-configs/{configId}/activate` | Activate telemetry config |
| POST | `/api/developer/telemetry-configs/{configId}/deactivate` | Deactivate telemetry config |
| POST | `/api/developer/telemetry-configs/{configId}/archive` | Archive telemetry config |
| DELETE | `/api/developer/telemetry-configs/{configId}` | Delete telemetry config |
| GET | `/api/developer/stats` | Get SDK statistics |

**SDK platforms**: `WEB`, `ANDROID`, `IOS`, `UNITY`, `UNREAL`, `REACT_NATIVE`, `FLUTTER`, `Cocos2d`, `SERVER`, `CUSTOM`

**SDK key statuses**: `ACTIVE`, `SUSPENDED`, `EXPIRED`, `REVOKED`

**Delivery modes**: `REALTIME`, `BATCH`, `HYBRID`

**Version statuses**: `DRAFT`, `TESTING`, `BETA`, `RELEASED`, `DEPRECATED`, `RETIRED`

**Change types**: `MAJOR`, `MINOR`, `PATCH`, `HOTFIX`

**Config types**: `EVENT_DELIVERY`, `BATCH`, `COMPRESSION`, `ENCRYPTION`, `RETRY`, `OFFLINE`, `MONITORING`, `CUSTOM`

**Config statuses**: `DRAFT`, `ACTIVE`, `INACTIVE`, `ARCHIVED`

### Users And Role Assignments

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/users` | List users |
| POST | `/api/users` | Create a user |
| GET | `/api/users/{userId}` | Get user details |
| PUT | `/api/users/{userId}` | Update a user |
| GET | `/api/users/{userId}/role-assignments` | List a user's role assignments |
| POST | `/api/users/{userId}/role-assignments` | Assign a role (`roleId`, optional `gameId`, `environment`) |
| DELETE | `/api/users/{userId}/role-assignments?roleId=&gameId=&environment=` | Revoke a role assignment |

`user_role_assignments` is the only write path for permissions; `users.roles` is a display column.

Scopes: `global`, `game`, `environment` (environment scope requires a gameId).

Built-in roles (seeded in `V0.2.3`, the `roles` table is the single source of truth): `operator` (global, all permissions), `game_admin`, `analyst`, `marketing`, `finance`, `developer`, `viewer`, `qa`. There is no `owner` or `risk_admin` role.

### Audit Logs

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/audit-logs?gameId=&environmentId=&actor=&action=&from=&to=` | Filtered search |
| GET | `/api/audit-logs/user/{userId}` | By actor |
| GET | `/api/audit-logs/resource/{resourceType}/{resourceId}` | By resource |
| GET | `/api/audit-logs/action/{action}` | By action |
| GET | `/api/audit-logs/status/{status}` | By result status |
| GET | `/api/audit-logs/time-range?from=&to=` | By time range |
| GET | `/api/audit-logs/game/{gameId}` | By game |
| GET | `/api/audit-logs/failed` | Failed operations |
| GET | `/api/audit-logs/auth` | Login/logout/failed-login records |
| GET | `/api/audit-logs/sensitive` | Sensitive-permission reads (requires `audit:sensitive`) |
| GET | `/api/audit-logs/search?query=` | Keyword search |
| GET | `/api/audit-logs/statistics` | Aggregate statistics |
| GET | `/api/audit-logs/{logId}` | Single record |
| DELETE | `/api/audit-logs/cleanup` | Purge old records |

### Other Resource Groups

All of the following are implemented and exposed through the same auth model; see `/v3/api-docs` for per-endpoint parameters:

| Group | Base path | What it covers |
|---|---|---|
| Analytics | `/api/analytics` | Revenue / ads / sessions / performance / social overviews, ARPU, by-platform, by-network, trends, crashes |
| Announcements | `/api/games/{gameId}/announcements` | Ops announcement lifecycle (draft/schedule/publish/offline) |
| Block lists | `/api/block-lists` | Blocked targets, unblock, stats (see section above) |
| Cohorts | `/api/cohorts` | Cohort definitions, calculation, results |
| Crash metrics | `/api/crash-metrics` | Crash aggregation and symbolication (`/{gameId}/symbolicate`) |
| Dashboards | `/api/games/{gameId}/dashboards` | Custom dashboard layouts and widgets |
| Dimension sync | `/api/dimensions` | `/sync-status` heartbeat from the dimension-sync agent |
| Event export | `/api/games/{gameId}/events-export` | Raw event export jobs (daily JSONL + manifest) |
| Funnels | `/api/funnels` | Funnel definitions and results |
| Identities | `/api/identities` | Identity lookup (by device/player/user/identifier) + explicit merge (`POST /{gameId}/merge`, `privacy:manage`; secondary absorbed into primary, tombstoned, audited) |
| Event inspector | `/api/inspector` (control) and gateway `/v1/inspector/recent` | Recent raw events for debugging |
| Integrations | `/api/integrations` | Slack/Discord/email/payment/webhook integrations |
| LTV metrics | `/api/ltv-metrics` | LTV forecast queries |
| Mail | `/api/games/{gameId}/mails` | Player mail with attachments and claim receipts |
| Alert email channel | `/api/games/{gameId}/alert-email-config` (+`/test`) | Per-game alert email recipients; best-effort delivery (skipped when SMTP unconfigured) |
| Metric alerts | `/api/games/{gameId}/alert-rules`, `/api/games/{gameId}/alerts` | Threshold alert rules and alert instances |
| ML artifacts | `/api/ml-artifacts` | Versioned model artifact registry (register/list/active) |
| Online metrics | `/api/online-metrics` | Real-time online player counts |
| Payment funnel | `/api/payment-metrics` | Payment funnel metrics |
| Player data | `/api/player-data/{playerId}/...` | Cross-game profile, payments, login logs (permission-filtered) |
| Player erasure | `/api/privacy/erasure-requests` | GDPR-style erasure request lifecycle |
| Player exports | `/api/player-exports` | Player data export jobs |
| Prediction metrics | `/api/prediction-metrics` | churn / risk-score / pltv / propensity batch scoring + refresh |
| Rate limits | `/api/rate-limits` | Per-game rate limit and quota rules |
| Redeem codes | `/api/games/{gameId}/redeem-batches` | Redeem code batches, redemption, history |
| Remote config | `/api/games/{gameId}/remote-configs` | Key-value remote config versions |
| Retention | `/api/retention/enforcements` | Retention enforcement runs |
| Retention metrics | `/api/retention-metrics` | N-day / rolling retention queries |
| Risk metrics | `/api/risk-metrics` | Trend / rule-hits / severity / action distributions |
| Roles | `/api/roles` | Role CRUD, permission binding (roles table is the source of truth) |
| Segments | `/api/games/{gameId}/segments` | User segment definitions, compute, members |
| Symbol mappings | `/api/symbols` | Crash symbolication regex rules |
| System | `/api/system` | Maintenance windows, system configs, feature flags (see section above) |
| Tracking plans | `/api/games/{gameId}/tracking-plans` | See section above |
| Users | `/api/users` | See section above |
| Webhooks | `/api/webhooks` | See section above |

## Response Format

```json
{
  "code": 200,
  "message": "Success",
  "data": {},
  "timestamp": "2026-06-18T12:00:00Z",
  "traceId": "abc123"
}
```

## Error Codes

| Code | Description |
|---|---|
| 400 | Bad request |
| 401 | Unauthenticated |
| 403 | Permission denied |
| 404 | Resource not found |
| 409 | Conflict |
| 429 | Rate limited |
| 500 | Internal error |
