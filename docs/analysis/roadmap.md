# Oddsmaker Game Analytics Platform Product Roadmap

> 🌐 Language: **English** · 中文 → [roadmap.md](roadmap.md)

Goal: build a multi-game real-time analytics and risk control platform usable inside a single game company. The platform is not shared across multiple companies, and Organization/Tenant is not used as a business model.

> **Status (2026-09)**: All Phase capabilities in this document have been delivered (including A/B experiments + SRM, Crash collection and symbolication, pLTV/churn/risk scoring, Remote Config/LiveOps); the implementation details are tracked in the active tracker `todo.md` at the repo root; P7 competitive gap convergence is complete (see the release notes [release-notes/v0.2.0.md](../release-notes/v0.2.0.md)), and P8 MMP attribution is a conditionally approved item that is currently on hold. This document is retained as a product roadmap retrospective and is no longer updated with progress.

## Current Judgment

Foundation already in place:

- SDK → Gateway → Kafka → Flink → ClickHouse main pipeline.
- Web/Android/iOS/Unity SDK prototypes.
- Basic rate limiting, PII, Schema and HMAC capabilities.
- Flink enrichment, session, retention and funnel job skeletons.
- Basic Superset dashboards.

Problems that must be corrected:

- Mixed-up concepts of `Project`, `tenant_id`, `app_id`, `Organization`.
- The multi-tenant direction does not fit the single game company private deployment scenario.
- Insufficient game-specific event fields.
- Risk control is not a first-class module.
- The client-side HMAC secret poses a security risk.

## Phase 1: Model Unification and Security Fixes

Goal: change the platform's primary subject to `game_id + environment`.

Deliverables:

- Event contract v1: `game_id`, `environment`, `event_type`, `event_name`.
- ClickHouse new tables partitioned by `(game_id, environment, event_date)`.
- Gateway accepts legacy fields and outputs the new model.
- Client SDKs remove the HMAC secret.
- Server SDK retains HMAC.
- Documentation and API no longer present multi-tenancy as a target capability.

## Phase 2: Single-Company Multi-Game Control Plane

Goal: serve multiple games and multiple team roles inside one company.

Deliverables:

- Game CRUD.
- Environment CRUD.
- API Key management, bound to `(game_id, environment)`.
- Tracking Plan management.
- PII/sampling/rate-limiting policies.
- Company-wide RBAC: `global | game | environment` scopes.
- Audit logs.

Not doing:

- Organization CRUD.
- Tenant plans.
- Cross-company Row Policy.
- Multi-tenant billing.

## Phase 3: Game-Specific Analytics

Goal: evolve from a generic telemetry platform into a true game analytics platform.

Deliverables:

- Standard events: session, user, business, resource, progression, design, error, ad.
- Identity Merge: merging of device, account, player and character.
- N-Day and Rolling retention.
- Configurable N-step funnels.
- IAP and ad revenue aggregation.
- Level pass rates, fail rates and attempt counts.
- Virtual economy source/sink and inflation monitoring.

## Phase 4: Risk Control Action Pipeline

Goal: identify cheating, payment fraud, ad anomalies and economy anomalies in real time, and output actionable dispositions.

Deliverables:

- RiskRule management.
- Gateway hard blocks for blacklist, replay, rate-limit breach and illegal environment.
- Flink risk job.
- ClickHouse `risk_events`, `risk_scores`.
- Risk control dashboard.
- Webhook to the game server.
- Review queue and disposition audit.

Key rules:

- High-frequency events and scripted behavior.
- Duplicate receipts and abnormal payments.
- Multi-device, multi-IP and remote-location logins.
- Abnormal growth of virtual currency.
- Abnormal ad rewards.
- Abnormal level durations and battle results.

## Phase 5: Experiments, Crash and Intelligence

Deliverables:

- A/B experiment configuration, bucketing, target events, SRM and significance.
- Crash/Error collection, symbolication and stack hash aggregation.
- Cohort LTV and pLTV.
- Churn prediction.
- Risk scoring model.
- Remote Config and LiveOps integration.

## Success Metrics

- Ingestion endpoint P95 < 100ms.
- Events queryable P50 < 5s.
- Risk hit output P95 < 10s.
- Common metric queries P95 < 2s.
- Supports multiple games per company, tens of millions of DAU per game.
- Risk policy hits are explainable, auditable and replayable.

## Next Steps

Do Phase 1 immediately: unify `game_id + environment`. Until this foundation is unified, do not continue extending Organization, multi-tenant or Project-related capabilities.
