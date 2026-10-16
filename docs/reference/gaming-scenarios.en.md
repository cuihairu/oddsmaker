# Gaming Analytics Scenario Coverage Analysis

> 🌐 Language: **English** · 中文 → [gaming-scenarios.md](gaming-scenarios.md)

This document analyzes the coverage and flexibility of the Oddsmaker platform's gaming analytics scenarios.

## Overall Assessment

Six core scenarios and five extended scenarios are itemized below with their feature points and implementation status. The gaps are concentrated in custom metrics, the dashboard builder, and custom aggregations (see the flexibility assessment), as well as MAB auto-tuning and sequential testing for A/B experiments.

---

## I. Core Scenarios Covered

### 1.1 Event Collection

**Coverage: 5/5 items**

| Feature | Status | Description |
|------|------|------|
| Multi-platform SDK | ✅ | Web, Android, iOS, Unity, Server (HMAC signature) |
| Batch/realtime modes | ✅ | Flexible event reporting methods |
| Event Schema | ✅ | Standard events + custom properties |
| Data validation | ✅ | Tracking Plan validation |
| PII handling | ✅ | Desensitization, masking, dropping strategies |

**Supported event types:**
- `session` - session events
- `user` - user attributes
- `business` - business events (payments, ads)
- `resource` - resource events (virtual currency)
- `progression` - progression events (levels)
- `design` - design events
- `error` - error events
- `ad` - ad events
- `risk` - risk control events

### 1.2 Retention Analysis

**Coverage: 5/5 items**

| Feature | Status | Description |
|------|------|------|
| N-Day retention | ✅ | D1/D3/D7/D14/D30/D60/D90 |
| Rolling retention | ✅ | Rolling retention computation |
| Custom windows | ✅ | Flexible retention window configuration |
| Dimension drill-down | ✅ | By platform, channel, version, and other dimensions |
| Cohort grouping | ✅ | Grouped by registration date |

### 1.3 Funnel Analysis

**Coverage: 5/5 items**

| Feature | Status | Description |
|------|------|------|
| N-step funnels | ✅ | Any number of steps |
| Time windows | ✅ | Time limits between steps |
| Ordered/unordered | ✅ | Strict sequence or loose matching |
| Dimension drill-down | ✅ | Analyze by user attributes |
| Conversion rate calculation | ✅ | Overall conversion rate + per-step conversion rate |

**Typical funnel scenarios:**
- Onboarding funnel (register → create character → complete tutorial)
- Payment funnel (browse items → click buy → complete payment)
- Level funnel (start level → complete level → get reward)

### 1.4 A/B Testing Analysis

**Coverage: 8/8 items** (MAB auto-tuning and sequential testing not yet implemented)

| Feature | Status | Description |
|------|------|------|
| Experiment configuration | ✅ | Variants, weights, target events |
| User bucketing | ✅ | Consistent hashing bucketing |
| Exposure tracking | ✅ | Experiment exposure events |
| Conversion rate comparison | ✅ | Conversion rate per variant |
| Statistical significance | ✅ | p-value calculation |
| SRM detection | ✅ | Sample ratio mismatch detection |
| Uplift analysis | ✅ | B vs A uplift |
| Confidence intervals | ✅ | 95% confidence interval |

**To be enhanced:**
- Multi-armed bandit (MAB) auto-tuning
- Sequential testing

### 1.5 Risk Control

**Coverage: 7/7 items**

| Feature | Status | Description |
|------|------|------|
| Rule engine | ✅ | THRESHOLD/FREQUENCY/PATTERN/VELOCITY/RATIO/ANOMALY/MACHINE_LEARNING/FEATURE rules; blacklists go through BlockList, replays go through the gateway ReplayGuard |
| Real-time evaluation | ✅ | Flink stream processing |
| Risk scoring | ✅ | 0-100 point scoring |
| Automatic bans | ✅ | Configurable ban duration |
| Review queue | ✅ | Manual review process |
| Webhook notifications | ✅ | Real-time alerts |
| Risk dashboard | ✅ | Risk trends, hit rates |

**Typical risk control rules:**
- High-frequency payment detection
- Duplicate receipt validation
- Anomalous login detection
- Anomalous virtual currency growth
- Abnormal ad reward

### 1.6 User Segmentation

**Coverage: 4/4 items**

| Feature | Status | Description |
|------|------|------|
| Cohort analysis | ✅ | Grouped by registration date |
| LTV calculation | ✅ | Cumulative/predicted LTV |
| Payer segmentation | ✅ | Payers/non-payers |
| Activity segmentation | ✅ | Active/dormant/churned |

---

## II. Scenarios to Be Enhanced

### 2.1 Revenue Analysis

**Current status: ✅ Implemented**

| Feature | Status | API Endpoint |
|------|------|----------|
| ARPU/ARPPU | ✅ | `/api/analytics/revenue/{gameId}/arpu` |
| Revenue trends | ✅ | `/api/analytics/revenue/{gameId}/overview` |
| Distribution by platform | ✅ | `/api/analytics/revenue/{gameId}/by-platform` |
| IAP/ad/subscription revenue | ✅ | Categorized revenue statistics |

### 2.2 Ad Analysis

**Current status: ✅ Implemented**

| Feature | Status | API Endpoint |
|------|------|----------|
| eCPM | ✅ | `/api/analytics/ads/{gameId}/overview` |
| Fill rate | ✅ | Statistics by ad network |
| Click-through rate (CTR) | ✅ | Statistics by ad format |
| Ad network comparison | ✅ | `/api/analytics/ads/{gameId}/by-network` |

### 2.3 Session Analysis

**Current status: ✅ Implemented**

| Feature | Status | API Endpoint |
|------|------|----------|
| Session duration distribution | ✅ | P50/P95/P99 statistics |
| Session depth | ✅ | Events per session |
| Bounce rate | ✅ | `/api/analytics/sessions/{gameId}/overview` |
| Session quality scoring | ✅ | High/medium/low quality distribution |

### 2.4 Performance Monitoring

**Current status: ✅ Implemented**

| Feature | Status | API Endpoint |
|------|------|----------|
| Frame rate monitoring | ✅ | `/api/analytics/performance/{gameId}/overview` |
| Jank detection | ✅ | LAG metric |
| Crash analysis | ✅ | `/api/analytics/performance/{gameId}/crashes` |
| Memory monitoring | ✅ | MEMORY metric |

### 2.5 Social Analysis

**Current status: ✅ Implemented**

| Feature | Status | API Endpoint |
|------|------|----------|
| Friends system | ✅ | `/api/analytics/social/{gameId}/overview` |
| Guild analysis | ✅ | Guild count, size |
| Viral coefficient | ✅ | Invite conversion rate |
| Social retention impact | ✅ | `/api/analytics/social/{gameId}/retention-impact` |

---

## III. Flexibility Assessment

### 3.1 High Flexibility

| Feature | Description |
|------|------|
| Event Schema | Supports custom properties, flexible extension |
| Risk control rules | Configurable rule engine, supports complex conditions |
| Report building | Visual report builder |
| Sampling rate | Adjustable event sampling ratio |
| Data retention | Configurable data retention period |

### 3.2 Medium Flexibility

| Feature | Description |
|------|------|
| Analysis dimensions | Fixed dimensions, extensible via props |
| Funnel steps | Limited, but sufficient for most scenarios |
| Retention windows | Predefined windows + custom windows |

### 3.3 Needs Improvement

| Feature | Description |
|------|------|
| Custom metrics | Lacks custom metric definitions |
| Custom dashboards | Delivered: console `/analytics/dashboards` (full CRUD via `/api/games/{gameId}/dashboards`) |
| Custom aggregations | Lacks custom aggregation functions |

---

## IV. Scenario Coverage Matrix

| Scenario | Priority | Status |
|------|--------|------|
| Event collection | P0 | ✅ Complete |
| Retention analysis | P0 | ✅ Complete |
| Funnel analysis | P0 | ✅ Complete |
| A/B testing | P0 | ✅ Complete |
| Risk control | P0 | ✅ Complete |
| User segmentation | P1 | ✅ Complete |
| Revenue analysis | P1 | ✅ Complete |
| Ad analysis | P2 | ✅ Complete |
| Session analysis | P2 | ✅ Complete |
| Performance monitoring | P2 | ✅ Complete |
| Social analysis | P3 | ✅ Complete |
| Custom reports | P1 | ✅ Complete (report APIs + `/analytics/dashboards` visual dashboards) |

---

## V. Summary

Completed: core scenarios such as event collection, retention, funnels, A/B testing, risk control, and segmentation; revenue, ad, session, performance, and social analytics APIs; multi-game architecture and developer tools.

Not implemented: custom metric definitions, custom aggregation functions, MAB auto-tuning, sequential testing.
