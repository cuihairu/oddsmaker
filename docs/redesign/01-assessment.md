# 01 - Current-State Assessment

## 1. Project Positioning Deviation

The current documentation positions the old project as an "enterprise-grade multi-tenant game data analytics platform", but this direction does not match the real private-deployment game company scenario.

The real scenario is usually:

- One company has multiple games.
- Each game has multiple environments: `dev`, `staging`, `prod`.
- Within the same company there are operations, design, data, engineering, customer service, and risk control teams.
- Data does not need to share a single logical tenant with other companies; it only needs in-company isolation by game and environment.

Therefore, the new target positioning should be: **a single-company, multi-game real-time analytics and risk control platform**.

## 2. Current Naming Confusion

> **Status update (2026-10)**: the Project/Organization/Tenant entities have been removed, and `tenant_id`/`org_id` are rejected at the gateway (B1/B4 landed); this section is kept as a pre-refactor assessment record.

Code and documentation simultaneously carry several sets of boundary concepts:

| Concept | Current State | Problem |
|---|---|---|
| `Project` | Still used in the old control plane, some SDKs, and gateway tests | Ambiguous meaning; may refer to a game or to an application |
| `tenant_id` | Used in the new schema and ClickHouse DDL | Implies multi-company sharing, which contradicts the target |
| `Organization/org_id` | Many JPA entities and migrations in the Control Service | Turns the company into a tenant object, adding meaningless complexity |
| `app_id` | Defined as `game_id + environment` | The idea can be kept, but the field name is less explicit than `game_id + environment` |
| `Game` | Entity already exists in the Control Service | Should become the core entity |
| `Environment` | Entity already exists in the Control Service | Should become the core configuration boundary under a game |

The new model must keep only:

- `game_id`
- `environment`
- `api_key`
- `user_id/player_id/device_id`
- `role_scope = global | game | environment`

## 3. Technology Stack Inventory

| Layer | Choice | Assessment |
|---|---|---|
| Collection | Spring Boot 3 WebFlux + Netty | Correct choice for a high-throughput ingress |
| Messaging | Kafka + Apicurio Schema Registry | Suitable for a self-built event pipeline |
| Real-time computation | Flink 1.19 | Suitable for sessions, retention, funnels, and risk control state computation |
| Storage | ClickHouse | The right choice for game events and aggregate queries |
| Metadata | JPA/Hibernate | Can carry game, environment, API Key, and policy configuration |
| Cache | Redis | Suitable for rate limiting, real-time counters, and short-window risk control state |
| BI | Superset | Usable as the initial analytics dashboard |
| SDK | Web/Android/iOS/Unity | Basic shape exists, but fields and security policies need unification |

> **Status update (2026-10)**: the collection layer has been upgraded to Spring Boot 4 (gateway WebFlux / control plane Web MVC, Java 21, Gradle 9.8.1); the remaining choices in this section are still current. The original table is kept as the assessment at that time.

## 4. Current Capability Gaps

> **Status update (2026-10)**: all capability gaps listed in this section have been delivered (identity merge since V0.3.2, N-Day/Rolling retention, configurable funnels, SRM and significance tests); kept as the assessment at that time.

| Capability Area | Current State | New Target |
|---|---|---|
| Basic collection | Gateway → Kafka → Flink → ClickHouse skeleton in place | Keep and unify fields |
| Multi-game management | `GameEntity` exists but is not the sole core | Control plane centered on Game |
| Environment isolation | `GameEnvironmentEntity` exists | Events, configuration, keys, and risk control policies all isolated by environment |
| Multi-tenancy | Over-engineered and directionally wrong | Remove from the target architecture |
| Game event model | Mostly generic event fields | Introduce Session/User/Business/Resource/Progression/Design/Error/Ad |
| Risk control | Only rate limiting, PII, and "anomaly detection" concepts | Build a real-time risk control pipeline with concrete enforcement actions |
| Retention/funnel | Retention with a fixed definition, funnel limited to 2 steps | N-Day/Rolling retention and N-step funnels |
| Identity Merge | Missing | Support merging guest, device, account, and character identities |
| A/B experiments | Mostly exposure events | Configuration, bucketing, SRM, significance, integration with risk control |
| Crash/Error | Only fields or switches | Error events, stack aggregation, affected user counts |

## 5. Correct Designs That Must Be Kept

- The Kafka + Flink + ClickHouse main pipeline.
- `event_id` deduplication and the DLQ bypass.
- Dual JSON Schema/Avro contracts.
- API Key, rate limiting, and PII governance.
- Superset as the initial BI output.

## 6. Directions That Must Be Adjusted

- No longer treat `Organization` as a business entity.
- No longer put `tenant_id` into the event schema, ClickHouse partitioning, or query conditions.
- No longer do cross-company Row Policy, tenant quotas, or tenant billing.
- Model "multiple games within one company" as a first-class capability.
- Make risk control part of the main collection and real-time computation pipeline.
