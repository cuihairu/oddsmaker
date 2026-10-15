# Oddsmaker Competitive Comparison and Feature Gap Analysis

> 🌐 Language: **English** · 中文 → [competitive-analysis.md](competitive-analysis.md)
> Research date: 2026-09 ｜ Method: web search + official documentation of each product (Unity docs.unity.com / firebase.google.com / gameanalytics.com / learn.microsoft.com PlayFab / posthog.com)
> Use of the conclusions: update `todo.md` roadmap priorities (adding the P7 gap-closure batch) and drive the first landed feature (P7-1 live event inspector).

## 1. Research Subjects

> **Conclusion:** The five products cover three forms — commercial giants (Unity/Google/Microsoft), a vertical free tool (GameAnalytics), and an open-source newcomer (PostHog) — and were aligned against Oddsmaker capability by capability; the gap conclusions directly drove the P7 batch (all now closed; see the status in §6).

Five mature products in the same domain were selected, covering three forms: commercial giants (Unity/Google/Microsoft), a vertical free tool (GameAnalytics), and an open-source newcomer (PostHog):

| Product | Vendor | Form | Positioning | Relationship to this project |
|------|------|------|------|----------------|
| Unity Gaming Services (Analytics + Remote Config + Cloud Diagnostics) | Unity | Commercial SaaS | Unity's built-in game analytics and operations backend | Full-chain benchmarking target for event models, funnels, crashes, and remote config |
| Firebase (Analytics + Crashlytics + Remote Config + A/B Testing) | Google | Commercial SaaS | General-purpose analytics and experimentation platform for mobile/games | Benchmarking target for the experiment release-evaluation flow, DebugView, and App Check device integrity |
| GameAnalytics | GameAnalytics (Moboku) | Free SaaS (freemium) | The standard component for game-native product analytics | Event taxonomy (the blueprint for this project's P3 event typing), real-time views, benchmarks |
| PlayFab | Microsoft | Commercial SaaS | Game backend + LiveOps + all-in-one analytics suite | Benchmarking target for the LiveOps surface (announcements/email/redemption/targeted delivery) |
| PostHog | PostHog | Open source (self-hostable) | Product analytics + experimentation + replay in one | Direct benchmark for the open-source form and the self-hosting demand; flags/experimentation integrated design |

Sources (primary):
- Unity: docs.unity.com (Analytics funnels definition, Standard Events, Remote Config documentation, updated 2026-09)
- Firebase: firebase.google.com/docs (A/B Testing tutorial, Remote Config for Unity pathway, App Check/Play Integrity)
- GameAnalytics: 2026 mobile game monetization tool stack review (undrads.com 2026-09), 2026 retention benchmark report (gamegrowthadvisor.com, D1 22% / 11,600-game sample), official LinkedIn (A/B + Remote Config capabilities)
- PlayFab: learn.microsoft.com (documentation still updated as of 2026-07; some sub-features have deprecation records while the service as a whole remains in operation; no overall retirement announcement)
- PostHog: posthog.com (2026-02 feature flag tool comparison: flags integrated with analytics/experimentation as the first selling point)

## 2. Feature Comparison Matrix

Legend: ✅ Full ｜ 🟡 Partial/prototype ｜ ❌ None ｜ N/A Not applicable (meaningless under a single-company deployment form)

> Note: This matrix is the 2026-09 research snapshot, and the Oddsmaker column reflects the state at that time; the P7 batch (closed 2026-10-04) has since filled in many of the items. For the current status of each item see the reference analysis in §6, and that section plus todo.md prevails.

### 2.1 Data Ingestion and Governance

> **Conclusion:** Ingestion and governance are this repo's strong suit — Schema governance and the PII policy lead all five, and only PostHog is on par on data retention tiering.

| Capability | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| Multi-platform SDK (Web/Android/iOS/Unity/Server) | ✅ (5 platforms, including Server HMAC) | ✅ (mainly the Unity ecosystem) | ✅ | ✅ | ✅ | ✅ (mainly Web/Mobile) |
| Event Schema governance / Tracking Plan | ✅ (JSON Schema + Tracking Plan + property whitelist + cardinality limits) | 🟡 (Standard Events + validation) | 🟡 (advisory events) | 🟡 (strict Standard Events constraints) | ❌ | 🟡 (data management) |
| PII policy (mask/drop/allow, IP coarsening) | ✅ (policy can be overridden per environment) | ❌ | 🟡 | ❌ | ❌ | 🟡 |
| Environment isolation (dev/staging/prod data boundary) | ✅ (game_id+environment across the whole chain) | 🟡 | ✅ (project dimension) | 🟡 | ✅ (title dimension) | ✅ (project dimension) |
| Data retention tiering/partitioning | ✅ (per-environment policy + toYYYYMM partitioning) | ❌ (platform-hosted) | 🟡 | ❌ | ❌ | ✅ |

### 2.2 Analytics Capabilities

> **Conclusion:** The mainstream surfaces — real-time/retention/funnel/monetization — are already aligned with all five; the only real gap used to be reusable segmentation (now landed), and industry benchmarks do not apply under the single-company form.

| Capability | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| Real-time dashboards (DAU/online/minute trends) | ✅ | ✅ | ✅ (Realtime) | ✅ (Real-time Overview) | 🟡 | ✅ (Live) |
| Retention (N-Day + Rolling, cohort trends) | ✅ | ✅ | 🟡 | ✅ | 🟡 | ✅ |
| Funnels (ordered/unordered/time-window) | ✅ | ✅ | 🟡 (funnel exploration) | ✅ | 🟡 | ✅ |
| Reusable user segmentation (define a Segment once, filter everywhere) | ❌ (only platform/version/channel dimension slicing) | ✅ | ✅ (Audiences) | ✅ | ✅ (Segments) | ✅ |
| Monetization (IAP/ads/LTV/pLTV) | ✅ | 🟡 | ✅ | ✅ | 🟡 | 🟡 |
| Churn prediction | ✅ (heuristic and explainable) | ❌ | 🟡 (GA predictive audiences) | ❌ | ❌ | ❌ |
| Industry benchmarks (Benchmark) | N/A (no cross-company data pool under a single company) | 🟡 | ❌ | ✅ (11,600+ game sample) | ❌ | ❌ |
| Attribution/user acquisition (MMP integration, CPI/ROAS) | ❌ (only the attribution.channel field as a fallback dimension) | 🟡 | ✅ (with Google Ads) | 🟡 | ❌ | 🟡 |

### 2.3 Experimentation and Config Delivery

> **Conclusion:** The experimentation trio (A/B + Remote Config + Flags) is all ✅ in this repo, overtaking GA (paid tier) and Firebase's weak flags item.

| Capability | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| A/B experiments (traffic split + statistical test + SRM) | ✅ (FNV-1a hash32 deterministic bucketing, z/t tests, chi-square SRM) | ✅ (Game Overrides) | ✅ | 🟡 (paid tier) | ✅ (Experiments) | ✅ |
| Remote Config (environment overrides + incremental pull) | ✅ | ✅ | ✅ | 🟡 | ✅ (Title Data) | ✅ |
| Feature Flags (percentage rollout/advance) | ✅ | 🟡 (via Remote Config) | 🟡 | ❌ | 🟡 | ✅ (a strength) |

### 2.4 Stability and Risk Control

> **Conclusion:** Real-time risk control is the only "no-man's land" in the whole table — none of the five has complete real-time behavioral risk control; this is this repo's differentiated strong suit, not a patch.

| Capability | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| Crash/Error reporting and aggregation | ✅ (fingerprint normalization + symbolication rules + per-version crash rate) | ✅ (Cloud Diagnostics) | ✅ (Crashlytics) | 🟡 (error events) | 🟡 | 🟡 |
| Real-time risk control rules (THRESHOLD/FREQUENCY/PATTERN/VELOCITY/RATIO/ANOMALY/MACHINE_LEARNING/FEATURE + blacklist + replay protection) | ✅ (built into the main pipeline, front-loaded at the Gateway + Flink real-time) | ❌ | 🟡 (only App Check device integrity, not behavioral risk control) | ❌ | 🟡 (bans/audit, no real-time rules) | ❌ |
| Risk scoring and disposition (block/review/mark/throttle + Webhook) | ✅ | ❌ | ❌ | ❌ | 🟡 (manual moderation) | ❌ |

### 2.5 LiveOps and Player Operations

> **Conclusion:** Announcements/email/redemption are all in place; the only gap is targeted delivery by segment — it can be wired up as soon as segmentation (Segments) lands, making it "the last piece of the existing infrastructure".

| Capability | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| Announcements/email/redemption codes | ✅ | 🟡 | ❌ | ❌ | ✅ | ❌ |
| Player profile query/export/deletion (GDPR) | ✅ (cross-table packaged export + audit) | 🟡 | 🟡 | 🟡 | ✅ | ✅ |
| Targeted delivery (delivery by segment) | 🟡 (announcements can be scheduled, not yet wired to segments) | ❌ | ✅ (Audience targeting) | ❌ | ✅ (Segments+Offers) | 🟡 |

### 2.6 Engineering Experience (Integration and Debugging)

> **Conclusion:** The two cells with the biggest gaps — live event inspection and free-form dashboards — are now both filled in (Live Inspector / Dashboards); the raw data exit is still one tier weaker than BigQuery/HogQL and is bridged by `/raw-export` for now.

| Capability | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| Live event inspection (Live/Debug View: see whether each event arrived and the rejection reason) | ❌ | 🟡 | ✅ (DebugView/StreamView) | ✅ (Real-time event stream) | 🟡 | ✅ (Live events) |
| Custom dashboards (widget composition) | 🟡 (Report is a prototype, not free composition) | 🟡 | ✅ | 🟡 | 🟡 | ✅ |
| Raw data export/SQL access | 🟡 (player-level export + report CSV; no full raw export/SQL) | 🟡 | ✅ (BigQuery) | 🟡 | 🟡 (PlayFab Explorer) | ✅ (HogQL/Warehouse) |
| Metric alerting (threshold + notification) | ✅ | 🟡 | ✅ | ❌ | 🟡 | ✅ |
| Permissions/audit (RBAC + audit log) | ✅ (scoped RBAC + full audit) | 🟡 | 🟡 | ❌ | ✅ | ✅ |

## 3. Gap Conclusion: Missing Key Features (Ordered by User Value)

1. **Live event inspector (Live Inspector / Debug View)** — value 5/5, cost 1/5
   The number-one pain point of the integration period: after wiring up the SDK, "did my events actually arrive, and why were they rejected?". A standard feature among competitors (Firebase DebugView, GA Real-time, PostHog Live). All rejection reasons in this project (invalid_schema/api_key_scope_mismatch/pii_blocked/invalid_timestamp/sampling/dedup/ban) currently go only into the DLQ and the response body, with nowhere to visualize them. Landed as a Gateway in-memory ring buffer + inspection API + console page.
2. **Reusable user segmentation (Segments)** — value 4.5/5, cost 3/5
   Every competitor has it, and it is the infrastructure for targeted delivery (Firebase Audiences, PlayFab Offers). Definition (attribute + behavioral conditions) → materialization (ClickHouse subject set) → injection into retention/funnel/revenue/online report filtering + LiveOps targeted delivery. Note the distinction from the existing retention CohortService (that is retention-caliber cohort computation, not user segmentation).
3. **Widget-based custom dashboards** — value 3.5/5, cost 2/5
   The Report module already has a prototype; add widget cards (chart/table/KPI) with free composition and saving, catching up with all competitors' Dashboard Builder.
4. **Full raw data export (scheduled export to S3/object storage)** — value 3/5, cost 2/5
   Benchmarked against Firebase BigQuery. The archive infra is already in the architecture diagram (Object Storage/Archive); just add a daily-partitioned export job — the feeding channel for BI drill-down (Superset/Metabase).
5. **Attribution/user acquisition analysis (MMP integration)** — value 3/5 (5/5 for user-acquisition-driven studios), cost 4/5
   Depends on external MMP (AppsFlyer/Adjust) data source integration and table creation; the external dependency is heavy, so it is scheduled for mid-term evaluation.

## 4. Explicitly Not Pursued Features and Reasons

> **Conclusion:** The 6 not-pursued items each guard a hard boundary — cost order of magnitude (Replay), data boundary (Benchmark), product orthogonality (game backend/push/ad-platform direct connection), and model boundary (multi-tenancy).

| Feature | Source competitor | Reason for not pursuing |
|------|----------|------------|
| Session Replay | PostHog | Requires a client-side rendering-level recording SDK, with storage cost an order of magnitude higher; the marginal benefit for the "analytics + risk control" positioning is low; it widens the privacy surface (backfiring on the PII governance commitment) |
| Industry benchmark Benchmark | GameAnalytics | Single-company deployment is a product boundary (README positioning), with no cross-company data pool; building an anonymous data consortium would betray the data governance selling point |
| Game backend (leaderboards/achievements/multiplayer servers/economy systems) | PlayFab, Unity UGS | Orthogonal to "analytics + risk control + LiveOps reach"; game servers belong to the customer's domain, and this project can simply collaborate via the Webhook/integration surface |
| Push notification channel (self-built FCM/APNs) | Firebase | Requires vendor account systems and push infrastructure; reach is already covered by announcements + email + Webhook, and later it can be opened as an Integration extension point rather than self-built |
| Ad-platform direct-connection optimization (bidding/ROAS automation) | Firebase(Google Ads) | Beyond the responsibilities of an analytics platform; user-acquisition decisions are left to MMP/BI |
| Multi-tenant SaaS-ification | (this project's historical baggage) | Already clarified in the todo.md parked items: single company with multiple games is the product boundary |

## 5. Design Insights (From Competitors)

1. **Integration experience determines platform trust** (Firebase DebugView / GA Real-time): the "black box" feel of data is the top reason self-built analytics platforms get abandoned → Live Inspector was listed as the top priority P7-1.
2. **Flags and experiments share the same origin** (PostHog's 2026 selling point): a flag is the carrier of an experiment, and an experiment is the measurement of a flag. This project's Feature Flags / Remote Config / Experiments trio is fully capable but has scattered entry points; in the mid-term they should converge into a unified "delivery surface" (one entity, three roles), with no change in the short term.
3. **The standard event taxonomy is the industry's common language** (GA's seven categories): this project's P3 nine categories (+experiment/risk) are consistent with it and extend it with risk control; keep it and continue enforcing it as strong constraints in the Tracking Plan — this is the domain advantage over general-purpose tools like PostHog.
4. **Risk control is a differentiated strong suit, not a patch** (by contrast, none of the five competitors has complete real-time behavioral risk control): keep digging deeper (scoring/disposition/evidence chain already exist), and do not dilute it to chase the analytics surface.
5. **Segments are a multiplier of operational efficiency** (Firebase Audiences / PlayFab Segments): define once, use everywhere (report filtering + targeted delivery) — the prerequisite for LiveOps upgrading from "broadcast" to "precision" → P7-2.
6. **Openness of the data exit** (BigQuery/HogQL): self-hosted customers will eventually demand sovereignty over their raw data → full export (P7-4) takes priority over fancy reports.

## 6. Reference Analysis (Item-by-Item Verdicts and Landing Points)

> **Conclusion:** Four of the five gaps are closed (the old P7 list was archived on 2026-10-04), and MMP attribution is parked under P8 pending external accounts; of the 6 design insights, 4 have been taken up as landed capabilities, and the remaining 2 are mid-term candidates.

| Verdict | Item | Why | Landing point in this repo |
|------|------|--------|----------|
| Worth referencing | Live event inspector (§3.1) | The number-one pain point of the integration period; standard in four of the five | Landed: Gateway inspection buffer + `/v1/inspector/recent` + console Live Inspector page (`/analytics/inspector`, P7-1) |
| Worth referencing | Reusable user segmentation (§3.2) | Standard across all competitors; the prerequisite infrastructure for targeted delivery | Landed: `/analytics/segments` (definition → materialization → report filtering, P7-2), also referenced as the experiment Audience (B8 field-ization) |
| Worth referencing | Widget-based dashboards (§3.3) | All competitors' Dashboard Builder | Landed: `/analytics/dashboards` (P7-3) |
| Worth referencing | Full raw data export (§3.4) | The data-sovereignty demand benchmarked against BigQuery | Landed: `/raw-export` (P7-4); the object storage daily-partitioned export directory mechanism also supports P8's reverse reading |
| Worth referencing | MMP attribution integration (§3.5) | A hard requirement for user-acquisition-driven studios, but with a heavy external dependency | Parked under P8 (prerequisite: raw data export permission from any one MMP); the finalized integration path is in `mmp-attribution-evaluation.md` |
| Worth referencing | Standard event taxonomy (§5.3) | GA's eight categories are the industry's common language; this repo has nine categories with risk control extensions | Keep: B7 EventSchema as a first-class resource + Tracking Plan strong constraints — the domain advantage over general-purpose tools |
| Worth borrowing | Flags and experiments share the same origin (PostHog, §5.2) | A flag is the carrier of an experiment, and an experiment is the measurement of a flag | FeatureFlagsView + ExperimentsView are already separate and usable; converging into a unified "delivery surface" is a mid-term candidate (no change in the short term) |
| Worth borrowing | Keep deepening the risk control strong suit (§5.4) | By contrast, none of the five has complete real-time behavioral risk control | Keep: B5 feature layer (event → feature → rule) + B6 RiskScore and RiskCase state machines |
| Worth borrowing | Openness of the data exit (§5.6) | Self-hosted customers will eventually demand sovereignty over their raw data | `/raw-export` already exists; deeper SQL access (HogQL-level) is long-term, and BI drill-down goes through the export channel first |
| Not applicable | Session Replay (§4) | Client-side recording SDK, storage an order of magnitude higher, and the privacy surface backfiring on the PII governance commitment | Not doing it (§4 row 1) |
| Not applicable | Industry benchmark Benchmark (§4) | Single-company deployment is a product boundary, with no cross-company data pool | Not doing it (§4 row 2) |
| Not applicable | Game backend / self-built push channel / ad-platform direct connection (§4) | Product-orthogonal or beyond the responsibilities of an analytics platform | Collaborate via the Webhook/Integration surfaces (§4 rows 3–5) |
| Not applicable | Multi-tenant SaaS-ification (§4) | Single company with multiple games is the product boundary | Not doing it (todo.md parked item) |

## 7. Roadmap Connection

> Status (2026-10-08): P7-1~P7-4 have all landed and were closed out with the old list (80 items across P0~P7) archived on 2026-10-04; P7-5 evolved into the parked P8 item (prerequisite unmet). The text below is the original schedule at initiation, kept only as historical context.

New `todo.md` **P7 competitive gap closure** batch:

- P7-1 live event inspector (landed in this round): Gateway inspection buffer + `/v1/inspector/recent` + Control proxy API + console Live Inspector page
- P7-2 reusable user segmentation (definition → materialization → report injection → LiveOps targeting)
- P7-3 widget-based dashboards
- P7-4 full raw data export (object storage, daily-partitioned)
- P7-5 MMP attribution integration evaluation (research first, then project initiation)

Priority basis: user value × implementation cost ÷ external dependency; see the ordering in §3.
