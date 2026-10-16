# Game Analytics Scenario Coverage

> 🌐 Language: **English** · 中文 → [gaming-scenarios.md](gaming-scenarios.md)

This document analyzes the coverage and flexibility of the Oddsmaker platform with respect to game analytics scenarios.

## Overall Assessment

The ten analytics dimensions are listed below item by item, together with their feature points and implementation status. Gaps are concentrated in custom metrics on the analytics side (see the flexibility assessment), as well as MAB auto-tuning and sequential testing for A/B testing.

---

## 1. Covered Core Scenarios

### 1.1 Event Collection

**Coverage: 5/5 items**

| Feature | Status | Description |
|------|------|------|
| Multi-platform SDK | ✅ | Web, Android, iOS, Unity, Server (HMAC signature) |
| Batch/real-time mode | ✅ | Flexible event reporting modes |
| Event Schema | ✅ | Standard events + custom properties |
| Data validation | ✅ | Tracking Plan validation |
| PII handling | ✅ | Desensitization, masking and drop policies |

### 1.2 Retention Analysis

**Coverage: 4/4 items**

| Feature | Status | Description |
|------|------|------|
| N-Day retention | ✅ | D1/D3/D7/D14/D30/D60/D90 |
| Rolling retention | ✅ | Rolling retention calculation |
| Custom windows | ✅ | Windows configured via the job-level system property `retention.ndays` (default 1,7,30), not per game |
| Dimension drill-down | ✅ | Drill down by cohort (platform/channel/version dimensions pending) |

### 1.3 Funnel Analysis

**Coverage: 4/4 items**

| Feature | Status | Description |
|------|------|------|
| N-step funnel | ✅ | Any number of steps |
| Time window | ✅ | Time limit between steps |
| Ordered/unordered | ✅ | Strict order or loose matching |
| Conversion rate calculation | ✅ | Overall conversion + step conversion |

**Typical funnel scenarios:**
- Onboarding funnel (registration → character creation → tutorial completion)
- Payment funnel (browse items → click purchase → complete payment)
- Level funnel (start level → complete level → earn rewards)

### 1.4 A/B Testing Analysis

**Coverage: 5/5 items** (MAB auto-tuning and sequential testing not implemented)

| Feature | Status | Description |
|------|------|------|
| Experiment configuration | ✅ | Variants, weights, target events |
| User assignment | ✅ | Consistent hashing assignment |
| Conversion comparison | ✅ | Conversion rate per variant |
| Statistical significance | ✅ | p-value calculation |
| SRM detection | ✅ | Sample ratio mismatch detection |

### 1.5 Risk Control

**Coverage: 5/5 items**

| Feature | Status | Description |
|------|------|------|
| Rule engine | ✅ | Threshold, blocklist/allowlist, velocity, sequence rules |
| Real-time evaluation | ✅ | Flink stream processing |
| Risk scoring | ✅ | 0-100 score |
| Automatic ban | ✅ | Configurable ban duration |
| Review queue | ✅ | Manual review process |

### 1.6 Revenue Analysis

**Coverage: 4/4 items**

| Feature | Status | Description |
|------|------|------|
| ARPU/ARPPU | ✅ | Average revenue per user / per paying user |
| Revenue trends | ✅ | Daily statistics |
| Distribution by platform | ✅ | Android/iOS/Web revenue comparison |
| Revenue composition | ✅ | IAP/ad/subscription revenue breakdown |

### 1.7 Ads Analytics

**Coverage: 3/4 items** (the ctr column exists in the data layer but is not exposed by the API)

| Feature | Status | Description |
|------|------|------|
| eCPM | ✅ | Revenue per thousand impressions |
| Fill rate | ✅ | Ad availability |
| CTR | ⚠️ | ctr column exists in the data layer, not exposed by the API |
| Network comparison | ✅ | Performance of different ad networks |

### 1.8 Session Analytics

**Coverage: 3/4 items** (the session quality column exists in the data layer, the pipeline is not connected)

| Feature | Status | Description |
|------|------|------|
| Session duration | ✅ | Average session duration |
| Session depth | ✅ | Events per session |
| Bounce rate | ✅ | First-experience quality |
| Session quality | ⚠️ | Column exists in the data layer, pipeline not connected |

### 1.9 Performance Monitoring

**Coverage: 4/4 items**

| Feature | Status | Description |
|------|------|------|
| Frame rate monitoring | ✅ | FPS statistics |
| Lag detection | ✅ | Frame freeze identification |
| Crash analysis | ✅ | Crash grouping and statistics |
| Memory monitoring | ✅ | Memory usage tracking |

### 1.10 Social Analytics

**Coverage: 4/4 items**

| Feature | Status | Description |
|------|------|------|
| Friends system | ✅ | Friendship statistics |
| Guild analytics | ✅ | Guild count and size |
| Viral coefficient | ✅ | Organic user growth |
| Social retention impact | ✅ | Retention lift from social features |

---

## 2. Flexibility Assessment

### High flexibility
- Event Schema is customizable
- Risk rules are configurable
- Reports are customizable
- Sampling rate is adjustable

### Medium flexibility
- Analytics dimensions are fixed (extendable via props)
- Funnels have no hard cap on the step count

### To be improved
- Custom metric definitions are missing on the analytics side (the ML model side already has a customMetrics field)

Custom dashboards are live (CRUD endpoints and the console page `/analytics/dashboards`) and are no longer listed as a gap.

---

## 3. Summary

All feature points of the 10 dimensions listed above have corresponding implementations; the endpoint list is in [API Reference](./api-reference.md). Not implemented: custom metric definitions on the analytics side, MAB auto-tuning, sequential testing; custom dashboards are live.
