# Oddsmaker Platform Redesign Plan (Index)

This directory records the new target architecture for the Oddsmaker game analytics platform: **single-company deployment, multi-game management, multi-environment isolation, built-in risk control**.

## Design Boundary

- Oddsmaker is for internal use by a single game company; it is not a public-facing SaaS, nor is it shared by multiple companies.
- The platform has exactly one company context; company information belongs to deployment configuration and never enters event primary keys, partition keys, or the permission model.
- The only business isolation dimensions are `game_id` and `environment`, where `environment` is typically `dev`, `staging`, or `prod`.
- User permissions are granted around games and environments; there is no longer an `Organization/Tenant` design.
- Risk control is a core pipeline, not a late-stage plugin: collection, real-time computation, alerting, enforcement, and audit must all be built in.

## Document Structure

| Document | Content |
|---|---|
| [01-assessment](./01-assessment) | Current-state assessment: the problems of mixed multi-tenancy/Project/Game concepts in the current code and docs |
| [02-design-issues](./02-design-issues) | Key design issues: complexity that must be removed and game capabilities that must be added |
| [03-open-source-reference](./03-open-source-reference) | Reference benchmarking: absorb only the architecture and analytics capabilities, do not copy the SaaS multi-tenant model |
| [04-redesign](./04-redesign) | New architecture: single company with multiple games, event model, data pipeline, risk control system |
| [05-roadmap](./05-roadmap) | Implementation roadmap: first unify the model, then add game analytics and risk control |
| [06-od2-restructure-plan](./06-od2-restructure-plan) | 2.0 restructuring plan: domain model/module boundaries/data contracts fixed in one pass, B1~B10 executable batches |
| [07-b10-data-quality-feature-store](./07-b10-data-quality-feature-store) | B10 final design: definitions and storage plans for five data quality metrics, feature_store dual-write schema, lineage direction records |

## One-Sentence Conclusion

Oddsmaker should not become "a shared analytics SaaS for multiple companies." The more realistic scenario is a game company building or privately deploying the platform itself, using one platform to serve multiple games, multiple environments, and multiple team roles.

## Core Ideas of the New Architecture

**GameAnalytics event taxonomy + ClickHouse real-time analytics + Flink stateful computation + single-company multi-game control plane + built-in risk control pipeline**

- Event primary key (contract declaration): `game_id + environment + event_id`; the gateway idempotency key is actually the single `event_id` key (`ReplayGuard.consumeEventId`), and the ClickHouse `events` table is a MergeTree with no uniqueness constraint
- Query partitioning: `game_id + environment + event_date`
- Permission boundary: user roles bound to games or environments
- Configuration boundary: API Keys, Tracking Plans, sampling, PII, and risk control policies are issued per game environment
- Risk control pipeline: rule/model detection → risk event table → alerting → enforcement actions → audit trail

## P0 Immediate Corrections

1. Remove the `tenant_id`, `Organization`, and `org_id` semantics from the target architecture and unify on `game_id + environment`.
2. Deprecate the `Project` concept to avoid four coexisting naming systems: `Project/Game/App/Tenant`.
3. Client SDKs hold only the public `api_key`; HMAC is allowed only for the Server SDK.
4. Add the risk control pipeline into the Gateway/Flink/ClickHouse design instead of only doing offline reporting.
5. The control plane becomes: games, environments, API Keys, Tracking Plans, risk control policies, user roles.

## Historical Material

The following files are kept as historical assessments and do not represent the new target architecture:

- `oddsmaker_completion_assessment.md`
- `oddsmaker_technical_recommendations.md`
- `oddsmaker_quick_summary.md`
- `ASSESSMENT_INDEX.md`
