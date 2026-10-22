# 05 - Implementation Roadmap

> 🌐 Language: **English** · 中文 → [05-roadmap.md](05-roadmap.md)

Roadmap goal: first get the model right, then add game analytics and risk control. Every phase must be independently releasable.

> **Status (2026-09)**: P0–P4 have all been delivered (event contract v1, control plane, risk control enforcement pipeline, monetization and intelligence); the actual delivery details are authoritative in the repository root `todo.md` — P5 reporting dashboards, P6 experiments/Crash/intelligence, and P7 competitive gap convergence are all complete (release notes in [release-notes/v0.2.0.md](../release-notes/v0.2.0.md)), and P8 MMP attribution is conditionally approved and on hold. This document is kept as a design-period roadmap retrospective and is no longer updated with progress.

## Phase Overview

| Phase | Duration | Goal | Deliverables |
|---|---|---|---|
| P0 | 2 weeks | Remove multi-tenancy, unify naming, fix security | `game_id + environment` target contract, client-side HMAC removal, migration plan |
| P1 | 3-4 weeks | Single-company multi-game control plane | Game/Environment/API Key/Role/Policy APIs |
| P2 | 4-6 weeks | Game eventization and core analytics | Events v1, SDK typed API, retention, funnels, Identity |
| P3 | 4-6 weeks | Risk control enforcement pipeline | Risk control rules, risk job, risk_events, alerting and enforcement |
| P4 | 6-8 weeks | Monetization and intelligent analytics | LTV, ads, experiment statistics, Crash, prediction |

## P0 Remove Multi-Tenancy and Fix Security

### P0.1 Define the Target Fields

Tasks:

- Change the target event contract to `game_id + environment`.
- Documentation, schema, and ClickHouse DDL no longer use `tenant_id`.
- The compatibility layer is allowed to read old fields for a short period:
  - `project_id -> game_id`
  - `app_id -> game_id + environment` resolution
  - `tenant_id` ignored or used only for historical migration mapping

Acceptance:

- New documentation does not treat `Organization/Tenant` as the target architecture.
- The new schema and DDL use `game_id + environment` as the primary key boundary.

### P0.2 Remove Client-Side HMAC

Tasks:

- Android/iOS/Unity/Web SDKs are not allowed to configure an HMAC secret.
- The Server SDK keeps HMAC.
- The Gateway distinguishes between client keys and server keys.

Acceptance:

- Client requests contain only the public `x-api-key`.
- No client SDK contains `secret` or `HmacSHA256` signing logic.

### P0.3 Design the Code Migration Compatibility Layer

Tasks:

- The Gateway parses old fields and emits the new event model.
- Publish Avro/JSON Schema v1.
- Create a new normalized event table in ClickHouse, without destructively altering the old table in place.
- Flink jobs support consuming from the new topic.

Acceptance:

- Old SDKs can keep reporting.
- New SDKs can report `game_id + environment` directly.
- Old and new data can be unified at the query layer.

## P1 Single-Company Multi-Game Control Plane

### P1.1 Game and Environment Management

Tasks:

- Keep `Game` and `GameEnvironment`.
- Remove `Organization` from the target APIs.
- Game fields focus on: name, type, platform, timezone, status, default currency, data retention.
- Environment fields focus on: `dev/staging/prod`, sampling, rate limiting, schema policy, risk control policy.

Acceptance:

- Games and environments can be created.
- Each environment can independently configure API Keys, Tracking Plans, PII, and risk control rules.

### P1.2 API Key Management

Tasks:

- API Keys are bound to `(game_id, environment)`.
- Key types: `client`, `server`, `admin`.
- client keys may only write events.
- server keys may write server-side events, payment verification, and risk control feedback.

Acceptance:

- The Gateway automatically fills in or validates `game_id + environment` based on the key.
- Cross-game and cross-environment writes are rejected.

### P1.3 In-Company RBAC

Tasks:

- Global roles: `admin`, `user`.
- Scoped roles: `owner`, `operator`, `analyst`, `developer`, `risk_admin`, `viewer`.
- Scope: `global`, `game`, `environment`.

Acceptance:

- Users can access only the games and environments they are authorized for.
- Risk control policies can be changed only by `risk_admin` or `owner`.

## P2 Game Eventization and Core Analytics

### P2.1 Events v1

Tasks:

- Extend the Avro/JSON Schema.
- The SDK provides a typed API:
  - `session`
  - `user`
  - `business`
  - `resource`
  - `progression`
  - `design`
  - `error`
  - `ad`
  - `risk`
- The Tracking Plan validates event names and properties.

Acceptance:

- Event names follow `category:subject:action`.
- Unregistered fields can be rejected, dropped, or routed to the DLQ according to policy.

### P2.2 Identity Merge

Tasks:

- Add an `identities` table.
- Support merging `device_id/user_id/player_id/character_id`.
- The SDK provides `identify` and `setPlayer`.

Acceptance:

- After a guest converts to an account, retention and LTV are not double-counted.

### P2.3 Retention and Funnels

Tasks:

- Retention supports N-Day and Rolling.
- Funnels support N steps, time windows, and ordered/unordered.
- All aggregations are by `game_id + environment`.

Acceptance:

- A configurable 6-step onboarding funnel.
- D1/D3/D7/D14/D30/D60/D90 retention can be queried.

## P3 Risk Control Enforcement Pipeline

### P3.1 Risk Control Rule Management

Tasks:

- Add a RiskRule API to the Control Service.
- Support threshold, blocklist/allowlist, velocity, sequence, and model rules.
- Rules are published by game environment.

Acceptance:

- `prod` and `staging` can be configured with different rules.
- Every rule change writes an audit log.

### P3.2 Real-Time Risk Control Job

Tasks:

- The Flink risk job consumes `events_validated`.
- Detection of:
  - High-frequency events
  - Abnormal resource source/sink
  - Duplicate receipts
  - Anomalous logins from multiple devices and multiple IPs
  - Abnormal ad rewards
  - Abnormal level completion times
- Output `oddsmaker.risk_events` and ClickHouse `risk_events`.

Acceptance:

- Policy hit latency is less than 10 seconds.
- Each risk event contains rule, subject, score, evidence, and action.

### P3.3 Risk Control Enforcement and Alerting

Tasks:

- The Dashboard displays risk trends, severity levels, and hit rules.
- Webhook notifications to the game server.
- Support block/review/mark/throttle.

Acceptance:

- Payment receipt reuse can be blocked automatically.
- Resource anomalies can alert and enter the review queue.

## P4 Monetization and Intelligent Analytics

### P4.1 LTV and Ad Analytics

Tasks:

- Cohort LTV.
- Unified IAP + Ad revenue.
- Ad impression/click/reward/eCPM.
- Integration of payment and ad risk control.

### P4.2 Experiment Platform

Tasks:

- Experiment configuration, bucketing, target events.
- Significance, confidence intervals, SRM.
- Risk control filters out cheating samples to avoid polluting experiment conclusions.

### P4.3 Crash/Error

Tasks:

- Error events.
- stack hash aggregation.
- dSYM/Proguard mapping/source map management.
- Affected user counts by version, device, and country.

### P4.4 Prediction Models

Tasks:

- Churn prediction.
- pLTV.
- Payment propensity.
- Risk scoring model.

## Success Metrics

- Collection ingress P95 latency < 100ms.
- Events entering ClickHouse P50 < 5s.
- Risk control hit output P95 < 10s.
- Common query metrics P95 < 2s.
- Support single-company multi-game, with tens of millions of DAU per game.
- Event field and Tracking Plan changes are auditable and rollbackable.

## Current Best Next Step

Do P0 first: unify the target fields, schema, DDL, SDK parameters, and documentation on `game_id + environment`. Until this step is done, any subsequent control plane, risk control, or analytics capability will keep being dragged down by the `Project/Tenant/App` confusion.
