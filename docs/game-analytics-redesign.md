# complier Game Analytics Project Redesign

> 🌐 Language: **English** · 中文 → [game-analytics-redesign.zh.md](game-analytics-redesign.zh.md)

Last updated: `2026-06-05`

## 1. Naming and Goal

`complier` is taken from a casino context — a professional role — to express the core intent of this project: not passively storing logs, but continuously observing player behavior, in-game changes, revenue structure, and risk signals, and turning that information into operational decisions.

The current repository has been gradually unified under the `Oddsmaker` name; the early `Pit` codename serves only as historical migration background and is no longer used as a new SDK, Schema, or package name.

The project's goal therefore needs to converge from a "general-purpose event platform" to a "data workbench for game teams":

- Show producers the business results and version changes.
- Show game designers and level designers where players get stuck, where they fail, and whether the economy is balanced.
- Show operations teams the activities, segments, experiments, and recalls.
- Show the technical team crashes, performance, and anomalous behavior.

## 2. Why a Redesign Is Needed

The existing repository is already fairly complete at the infrastructure layer:

- The collection pipeline is complete: `SDK -> Gateway -> Kafka -> Flink -> ClickHouse -> Superset`
- Multi-platform SDKs are taking shape: `Web / Android / iOS / Unity / Server`
- The real-time compute pipeline already exists: enrichment, sessions, retention, funnels
- BI assets have landed: Superset bundle and starter SQL

But the current state still leans toward "platform capability," and it is still 3 things away from a game analytics product:

1. Lack of explicit game semantics: tutorials, levels, virtual economy, ads, shops, and activities have not yet been codified into default models.
2. Lack of role-based workbenches: producers, operations, designers, and analysts do not see a work interface oriented toward their responsibilities.
3. Broken operations pipeline: analysis, segmentation, experiments, configuration distribution, and retrospective review have not yet been strung into a single pipeline.

## 3. Product Direction

### 3.1 Five Core Workbenches

| Workbench | Problem Solved | Essential Metrics | Product Actions Needed |
| --- | --- | --- | --- |
| Growth and UA | Which channels and creatives bring high-quality players | CAC, ROAS, payback period, day-one payer rate | Channel comparison, creative attribution, payback dashboard |
| Onboarding and Progress | Where and why players fall off | Tutorial completion rate, first-level pass rate, level failure rate, average attempt count | Funnel, level heatmaps, failure reason distribution |
| Economy and Monetization | Where revenue comes from and whether the virtual economy is healthy | ARPU, ARPPU, payer conversion rate, ILRD, currency source/sink ratio | Shop analysis, currency water level, gift pack and ad placement effectiveness |
| LiveOps and Experiments | Which configuration, activity, or numerical change is more effective | uplift, retention change, revenue change, activity participation rate | Player segmentation, A/B testing, Remote Config |
| Stability and Risk Control | Whether technical issues and anomalous behavior affect experience and revenue | crash-free users, FPS, startup time, network error rate, cheat flags | Anomaly alerts, version comparison, suspicious player screening |

### 3.2 Preset Event Dictionary

It is recommended to converge SDK and server-side events into a single preset event dictionary:

- Identity and sessions: `install`, `login`, `session_start`, `session_end`
- Onboarding and progress: `tutorial_start`, `tutorial_complete`, `level_start`, `level_fail`, `level_complete`
- Economy system: `currency_source`, `currency_sink`, `item_grant`, `item_consume`
- Monetization: `iap_order`, `ad_impression`, `rewarded_ad_complete`, `webshop_order`
- Social and activities: `guild_join`, `invite_sent`, `event_entry`, `event_reward_claim`
- Stability and risk: `crash`, `fps_drop`, `network_timeout`, `cheat_flag`

Strategically, "preset core events + custom extension fields" is recommended:

- Core fields are fixed, ensuring cross-game comparability.
- Business fields are uniformly encoded into `props_json` in the pipeline, avoiding table structure explosion.
- Amount and resource quantity fields use Decimal/Double semantics, avoiding decimal precision loss for items, virtual currency, and ad revenue.
- Key objects get their own wide tables or materialized views, such as `level_progress`, `economy_flows`, `monetization_daily`.

See [game-event-dictionary.md](game-event-dictionary.md) for the dictionary draft.

## 4. Benchmark Cases

The following examples fall into two categories: mature game analytics products, and open-source or self-hosted platforms worth borrowing from.

### 4.1 Mature Game Analytics Products

| Project | Reference Value | What It Teaches `complier` | Official Link |
| --- | --- | --- | --- |
| GameAnalytics | A typical game-specific analytics SaaS covering preset dashboards, funnels, cohorts, progression, monetization, health, benchmark | We should build game-specific semantics in by default, not just offer an event table and SQL editor | [Overview](https://docs.gameanalytics.com/) / [AnalyticsIQ Overview](https://docs.gameanalytics.com/products-and-features/analytics-iq/overview/) |
| GameAnalytics Monetization | Supports IAP, ad ILRD, and webshop, with emphasis on purchase validation | Monetization analysis must cover IAP, ads, and Webshop simultaneously, and include revenue credibility validation | [Monetization](https://docs.gameanalytics.com/products-and-features/analytics-iq/monetization) / [Purchase Validation](https://docs.gameanalytics.com/advanced-tracking/purchase-validation) |
| Unity Analytics | Officially emphasizes core metrics, A/B testing, push, audiences, and funnels | Analysis should not stop at dashboards; it should link with segmentation, reach, and experiments | [Overview](https://docs.unity.com/en-us/analytics) / [Funnels](https://docs.unity.com/en-us/analytics/funnels/funnels) / [Audiences](https://docs.unity.com/en-us/analytics/audiences/audiences) |
| Unity Game Overrides | A/B testing and configuration release are directly connected, with statistical significance | The follow-up experiment module should be built directly on configuration distribution capability, not as a standalone reporting tool | [A/B testing](https://docs.unity.com/en-us/game-overrides/ab-testing) |
| PlayFab | Emphasizes PlayStream events, real-time event handling, player segmentation, and LiveOps | Worth borrowing the idea that "events are LiveOps infrastructure," rather than splitting analysis and operations apart | [What is PlayFab](https://learn.microsoft.com/en-us/gaming/playfab/get-started/what-is-playfab) / [PlayStream overview](https://learn.microsoft.com/en-us/gaming/playfab/data-analytics/ingest-data/playstream-overview) / [Player segments](https://learn.microsoft.com/en-us/gaming/playfab/player-progression/player-data/player-segments) |

One thing needs special mention about PlayFab: Microsoft's current official documentation states that `PlayFab Insights Management` will be retired on `2026-03-31`, and recommends moving to `Data Connections` for performance and cost management. Therefore it is more suitable as a reference for "events and operations as one," and not as the analytics backend form we should copy.

Reference:

- [What is PlayFab Insights](https://learn.microsoft.com/en-us/gaming/playfab/data-analytics/legacy/insights/overview)

### 4.2 Open-Source or Self-Hosted References

| Project | Reference Value | What It Teaches `complier` | Official Link |
| --- | --- | --- | --- |
| Countly | Self-hosted, plugin-based, Write API, multi-platform SDK, dashboards, alerts, Remote Config, permission management | Suitable as a form reference for a "private analytics product," but note the Lite/Enterprise licensing boundary | [Countly GitHub](https://github.com/Countly/countly-server) / [How Countly Works](https://support.countly.com/hc/en-us/articles/900004373266-How-Countly-Works) |
| PostHog | Puts product analytics, feature flags, experiments, and warehouse in one workspace | Worth borrowing the experience of "doing analysis and experiments on the same data," but not recommended as the primary storage for high-throughput game events | [PostHog GitHub](https://github.com/posthog/posthog) / [PostHog](https://posthog.com/) |
| GrowthBook | Emphasizes warehouse-native experimentation, transparent SQL metrics, lightweight SDK, and local evaluation | Our experiment module should directly reuse the ClickHouse metric system rather than building another data island | [GrowthBook Docs](https://docs.growthbook.io/) / [GrowthBook GitHub](https://github.com/growthbook/growthbook) |
| ClickHouse | Officially lists gaming analytics and telemetry as explicit scenarios, emphasizing high-concurrency queries, high-throughput writes, and OTel support | Continuing to use ClickHouse as the core fact layer is reasonable; what is needed going forward is stronger game-themed datasets | [Gaming Analytics](https://clickhouse.com/industries/gaming) / [ClickHouse](https://clickhouse.com/clickhouse) |

## 5. Design Trade-offs

Drawing on the cases above, `complier` is recommended to adopt the following set of trade-offs:

### 5.1 What to Borrow

- Borrow `GameAnalytics` and `Unity Analytics`'s preset game dashboard capability.
- Borrow `PlayFab`'s event-first and LiveOps thinking.
- Borrow `Countly`'s self-built, self-hosted, plugin-based management capability.
- Borrow `PostHog` and `GrowthBook`'s "analysis + experiments" integrated experience.
- Borrow `ClickHouse`'s high-throughput real-time analytics foundation.

### 5.2 What Not to Copy

- Do not be just a general-purpose tracking and SQL platform; that loses game product differentiation.
- Do not lock experiment statistics into a separate service; experiments must reuse the unified metric definition.
- Do not completely separate analysis and operations; the end user needs "once a problem is seen, action can be taken immediately."
- Do not over-rely on a single cloud vendor's managed analytics surface, especially capabilities that already have a deprecation history.

## 6. Recommended Implementation Path

### Phase 1: Game Semantics Codification

- Codify the preset event dictionary and parameter spec.
- Add `level`, `economy`, `shop`, `ads`, and `liveops` themed tables or materialized views.
- Provide game event helpers in the SDK, not just exposing the raw track API.

### Phase 2: Three Frontline Workbenches

- Onboarding and progress workbench
- Economy and monetization workbench
- LiveOps and experiments workbench

These three modules are closest to the daily work of producers, designers, and operations, and have the highest priority.

### Phase 3: Segmentation, Policy, and Experiments Linked

- Player segmentation service
- Remote Config or configuration override capability
- Experiment traffic splitting, metric attribution, and results retrospective

### Phase 4: Technical Health and Anomaly Detection

- crash, FPS, network, startup time
- Cheat and anomaly behavior detection
- Real-time alerts for operations and technical teams

## 7. Suggested Landing Points in the Repository

So that this redesign can map to existing code, it is recommended to prioritize changes to these directories going forward:

- `schema/avro/`: add game preset event schemas
- `schema/sql/clickhouse/`: add game-themed detail tables and aggregate views
- `jobs/flink/`: turn levels, economy, ads, and experiment exposure into independent jobs or operators
- `services/control-service/`: add segmentation, experiment, and configuration management APIs
- `bi/superset/`: rebuild dashboards by workbench, not just pile up generic charts
- `sdks/`: add preset event helpers, experiment exposure, and technical health collection

## 8. One-Sentence Conclusion

The most valuable direction for `complier` is not to become another "tracking platform," but to become a self-built data operating system for game production, operations, and game-design teams: connect progress, economy, monetization, experiments, and technical health on a unified event foundation, and turn those signals into actionable operational moves.
