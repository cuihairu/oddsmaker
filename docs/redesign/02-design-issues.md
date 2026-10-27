# 02 - Design Issue List

Graded by severity: P0 must be fixed immediately, P1 affects core capabilities, P2 is an enhancement item.

## P0-1 The Target Architecture Should Not Continue to Do Multi-Tenancy

The current documentation and part of the code design the old scheme as a multi-company shared platform, with `Organization/Tenant` as the core entity.

Problems:

- A normal game company doing a private deployment will not share the same set of business tenants with other companies.
- `tenant_id` pollutes the event model, ClickHouse partitioning, Flink keys, SDK parameters, and BI queries.
- Multi-tenancy introduces complexity such as cross-tenant permissions, quotas, billing, Row Policy, and migration, without any benefit for the target scenario.

Correct approach:

- The company serves as the deployment boundary, not as a business primary key.
- Remove `tenant_id/org_id/Organization` from the target model.
- Use `game_id + environment` as the primary boundary for events, configuration, queries, and permissions.

## P0-2 Four Sets of Concepts `Project/Game/App/Tenant` Are Mixed Together

The current repository simultaneously contains:

- Old model: `ProjectEntity`, `project_id`
- New model: `Organization → Game → Environment`
- Event model: `tenant_id + app_id`
- Documentation model: many places still describe project-level or tenant-level isolation

Risks:

- SDKs do not know whether to pass `project_id`, `tenant_id`, or `game_id`.
- The partitioning semantics of Flink jobs and ClickHouse tables are unstable.
- Control plane APIs are hard to maintain.

Correct approach:

- Deprecate `Project` and `App` as core business terms.
- Unify event fields as `game_id` and `environment`.
- Bind API Keys to `(game_id, environment)`.
- Old fields are allowed only in the compatibility layer, and marked with a migration deadline.

## P0-3 The Client-Side HMAC Secret Is a Security Anti-Pattern

Client SDKs should not hold an HMAC secret. Android/iOS/Unity/Web can all be decompiled or debugged, and once the secret leaks, events can be forged.

Correct approach:

- Client SDKs use only the public `api_key`.
- Only the Server SDK may use `secret_key + HMAC`.
- Client-side forgery risk is handled through rate limiting, schema validation, risk control, device fingerprinting, receipt verification, and anomaly detection.

## P0-4 Risk Control Is Missing as a First-Class Pipeline

> **Status update (2026-10)**: this issue was closed in a later batch (the six-layer risk control pipeline and state machine are live: risk_events/risk_scores/risk_actions, the decision-first state machine, and the BLOCK trust gate); kept as the assessment at that time.

Currently there are only rate limiting, PII, and "anomaly detection" concepts, with no complete risk control enforcement pipeline.

Must be added:

- Pre-collection validation: API Key, environment, signature, time window, event body size, field whitelist.
- Real-time risk identification: high-frequency events, scripted behavior, abnormal payments, receipt reuse, multi-device credential stuffing, multi-IP logins, emulator/Root/Jailbreak.
- Risk outputs: `risk_events`, `risk_scores`, `risk_actions`.
- Enforcement actions: mark, down-weight, block, route to manual review, Webhook notification to the game server.
- Audit trail: every policy hit is explainable and reviewable.

## P1-5 The Event Model Is Not Game-Oriented Enough

The current fields lean toward generic tracking and lack key fields of the game industry.

Must be added:

- Player: `player_id`, `character_id`, `server_id`, `guild_id`
- Progression: `level_id`, `progression_path`, `difficulty`, `attempt_num`
- Match: `match_id`, `game_mode`, `result`, `score`
- Monetization: `order_id`, `product_id`, `receipt_hash`, `ad_network`, `ad_placement`
- Virtual economy: `virtual_currency`, `virtual_amount`, `flow_type`, `item_id`
- Risk control: `risk_context`, `client_integrity`, `ip_hash`, `device_fingerprint`

## P1-6 Retention and Funnel Capabilities Are Insufficient

Problems:

- Retention supports only fixed offsets.
- Funnels have only two steps.
- No segmented retention, Rolling Retention, paying cohorts, or channel cohorts.

Correct approach:

- Retention supports both `nday` and `rolling` definitions.
- Funnels support N steps, ordered/unordered, time windows, and slicing by audience.
- All computations are grouped by `game_id + environment`.

## P1-7 Identity Merge Is Missing

> **Status update (2026-10)**: delivered (since V0.3.2) — explicit Identity Merge is live (secondary merged into primary, tombstoning + audit); kept as the assessment at that time.

The typical identity chain in games is: device guest → platform account → game character → server character.

Correct approach:

- Create an `identities` table.
- Support merging `device_id → account_id → player_id → character_id`.
- Retention, LTV, and risk control all prioritize the merged stable player key.

## P1-8 Payments and Ads Lack Trustworthy Verification

Risks:

- Clients can forge IAP.
- Ad callbacks may be duplicated or forged.
- Revenue metrics and LTV get polluted.

Correct approach:

- IAP events must support server-side receipt verification.
- Ad revenue should preferably connect to server-side callbacks or aggregated reports.
- `order_id/receipt_hash/ad_impression_id` must participate in deduplication.
- Risk control needs to identify abnormal payments, refunds, sandbox orders, and duplicate callbacks.

## P2-9 The Control Plane Is Over-Weighted but Not Focused on Real Usage Flows

SaaS features such as organizations, plans, and tenant upgrades are not needed.

The control plane should focus on:

- Game management
- Environment management
- API Key management
- Tracking Plan
- PII policies
- Risk control rules
- User roles
- Alert configuration
- Audit logs

## P2-10 The Boundary Between BI and Real-Time Dashboards Is Unclear

Superset is suitable for exploratory analysis, not for all real-time operations and risk control alerting.

Correct approach:

- Keep Superset as the BI tool.
- Develop a lightweight Dashboard in-house for today's real-time metrics and risk control alerts.
- Redis + ClickHouse aggregate tables support second-level or minute-level refresh.
