# 03 - Reference Project Trade-Offs

> 🌐 Language: **English** · 中文 → [03-open-source-reference.md](03-open-source-reference.md)

This chapter discusses only which capabilities Oddsmaker should borrow. **Do not copy the SaaS multi-tenant model of open-source analytics products**.

## 1. Reference Matrix

> **Conclusion:** all six references follow the rule "borrow the capabilities, do not transplant the form" — analytics/governance/experiment capabilities are absorbed item by item, while SaaS multi-tenant models are kept out of this repository entirely; item-by-item verdicts and landing points are in §5.

| Project | Worth Borrowing | Should Not Copy |
|---|---|---|
| PostHog | ClickHouse event analytics, Feature Flag, A/B, Session Replay ideas | Org/Project/Team multi-tenant model |
| Countly | Mobile and game analytics, plugin architecture, Crash, Remote Config | Application isolation approach designed for multi-customer SaaS |
| GameAnalytics | Game event Taxonomy, core game metrics | Black-box SaaS product form |
| Snowplow | Schema Registry, Tracking Plan, event governance | Complex multi-pipeline enterprise CDP abstractions |
| Mixpanel | Identity Merge, user profiles, funnels | Generic product-analytics-first event definitions |
| Statsig/GrowthBook | Experiment bucketing, SRM, significance, Feature Flag | Multi-organization SaaS management model |

## 2. The Right Trade-Offs for Oddsmaker

Oddsmaker is a privately deployed or single-company internal platform and should absorb:

- GameAnalytics' game event classification.
- PostHog's full-stack event analytics architecture.
- Snowplow's Schema governance.
- Mixpanel's Identity Merge.
- Statsig/GrowthBook's experiment statistics.
- Countly's Crash, Remote Config, and plugin boundary.

Oddsmaker should not absorb:

- Multi-company tenant isolation.
- SaaS plans and tenant upgrades.
- Cross-company Row Policy.
- Tenant quotas designed for noisy neighbors.
- Complex organization-hierarchy sales models.

## 3. Standard Game Event Taxonomy

> **Conclusion:** the nine categories share the same origin as GameAnalytics' seven major categories plus a risk control extension (`risk`), and are already enforced in the Tracking Plan as the industry's common language.

| Type | Purpose | Examples |
|---|---|---|
| `session` | Session start, end, heartbeat | `session_start`, `session_end` |
| `user` | Player attributes and account state | `user_login`, `user_bind_account` |
| `business` | Real-currency revenue | `business_purchase_success` |
| `resource` | Virtual economy source/sink | `resource_gold_source`, `resource_gem_sink` |
| `progression` | Levels, quests, onboarding | `progression_level_complete` |
| `design` | Custom gameplay events | `design_gacha_draw` |
| `error` | Crashes, exceptions, client errors | `error_crash_fatal` |
| `ad` | Ad impressions, clicks, rewards | `ad_rewarded_complete` |
| `risk` | Risk control signals and enforcement | `risk_payment_receipt_reused` |

## 4. Risk Control Reference Principles

Risk control cannot rely on offline reports alone. Game scenarios need real-time and near-real-time capabilities:

- Cheat detection: scripted level farming, abnormal resource growth, anomalous battle results.
- Account security: credential stuffing, logins from multiple locations, device anomalies, emulator farms.
- Payment risk control: duplicate receipts, sandbox orders, refunds, large amounts in a short time.
- Economy risk control: virtual currency source/sink imbalance, abnormal gifting, trade laundering.
- Ad risk control: abnormal impression/click/reward, device farms.

General patterns worth borrowing:

- The Gateway does request-level hard blocking.
- Flink does stateful rules and short-window detection.
- ClickHouse does retrospective analysis.
- Redis holds short-term counters and blocklists/allowlists.
- The Control Service manages rules, thresholds, actions, and audit.

## 5. Reference Analysis (Item-by-Item Verdicts and Landing Points)

> **Conclusion:** most of the borrowable items from the six references have landed (event classification, Schema governance, experiment statistics, Flags/experiments, Crash/Remote Config, explicit Identity Merge); Session Replay was re-judged as not applicable after competitive analysis; the non-applicable items are all concentrated in the SaaS multi-tenant form.

| Verdict | Reference Item | Why | Landing Point in This Repository |
|------|--------|--------|----------|
| Referenceable | PostHog: ClickHouse event analytics / Feature Flag / A/B | Full-stack event analytics architecture aligned with the self-hosted form | Landed: ClickHouse event main pipeline + `FeatureFlagsView` + `ExperimentsView` (B8 experiment formalization) |
| Not applicable | PostHog: Session Replay (originally noted in the matrix as "ideas worth borrowing") | Re-judged after competitive analysis as not pursued: client-side recording SDK storage is an order of magnitude higher, and the privacy side backfires against the PII governance commitment | Not implemented; `competitive-analysis.md` §4 is authoritative (this table's re-judgment is the reconciliation) |
| Referenceable | Countly: Crash / Remote Config / plugin boundary | A proven capability combination aimed at mobile and games | Landed: `CrashView` (fingerprint normalization + crash rate by version), `FeatureFlagsView` (configuration delivery); the plugin architecture is used only as a module-boundary reference |
| Referenceable | GameAnalytics: game event Taxonomy | The game-native classification is the industry's common language | Landed: P3 nine event categories (§3 table) + B7 EventSchema/Tracking Plan enforcement |
| Referenceable | Snowplow: Schema Registry / Tracking Plan / event governance | Governance is the prerequisite for trustworthy analytics | Landed: EventSchema as a first-class resource (B7: compatibility / PII policy / retention / sampling / owner + versioned releases and compatibility checks) |
| Worth borrowing | Mixpanel: Identity Merge / profiles / funnels | A unified subject definition is the prerequisite for cross-platform attribution | Landed: subject fallback chain (player_id → user_id → device_id) runs through reporting and risk control; explicit Identity Merge (`POST /api/identities/{gameId}/merge`, secondary merged into primary: link reattachment/conflict revocation, identity_id reverse lookup chain, scalar backfill and count merging, tombstoning + audit, `privacy:manage` gate; IdentityConsumer skips rebuilding for merge tombstones, preserving human decisions) |
| Referenceable | Statsig/GrowthBook: bucketing / SRM / significance | The correctness baseline of experiment statistics | Landed: FNV-1a 32-bit deterministic bucketing (`ExperimentSplitter.hash32`) + z/t tests + chi-square SRM (B8), Audience/Guardrail/Decision are fielded |
| Not applicable | The SaaS multi-tenant form shared by all six (Org/Project/Team, plans and upgrades, cross-company Row Policy, tenant quotas, organization sales hierarchies) | Single-company deployment is the product boundary; isolation revolves only around `game_id + environment` | Not done (this chapter's §2 negative list + the 06 plan's domain model) |
| Referenceable | §4 general risk control patterns (Gateway hard blocking / Flink stateful / ClickHouse retrospective / Redis counters / Control rule audit) | Layered interception + stateful detection + auditable enforcement is the mature division of a risk control pipeline | Landed isomorphically: gateway pre-validation, risk-job (B5 feature layer), ClickHouse risk_events, blocklists via control internal endpoint + gateway local cache (`BlockListClient`, TTL 15s, no Redis dependency), counters implemented in-gateway-process, RiskRule/RiskCase (B6) |

## 6. Architecture Conclusion

Oddsmaker's target paradigm is:

**Single-company multi-game control plane + game event Taxonomy + real-time risk control stream processing + ClickHouse analytics storage + governable Tracking Plan**

Core partitioning and permissions should all revolve around `game_id + environment`, not `tenant_id`.
