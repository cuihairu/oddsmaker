# Analytics API

> 🌐 Language: **English** · 中文 → [analytics.md](analytics.md)

The analytics API covers five categories — revenue, ads, sessions, performance, and social — all reported by gameId.

## Base URL

```
http://localhost:8085/api/analytics
```

## Authentication

All endpoints require Bearer Token authentication:

```http
Authorization: Bearer {token}
```

---

## 1. Revenue Analytics

### 1.1 Revenue Overview

**Endpoint:** `GET /revenue/{gameId}/overview`

**Parameters:**
| Parameter | Type | Required | Description |
|------|------|------|------|
| `gameId` | string | Yes | Game ID |
| `startDate` | date | Yes | Start date (YYYY-MM-DD) |
| `endDate` | date | Yes | End date (YYYY-MM-DD) |

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `totalRevenue` | number | Total revenue |
| `iapRevenue` | number | In-app purchase revenue |
| `adRevenue` | number | Ad revenue |
| `subscriptionRevenue` | number | Subscription revenue |
| `days` | number | Number of days counted |

**Example:**
```http
GET /api/analytics/revenue/game_123/overview?startDate=2024-01-01&endDate=2024-01-31
```

**Response:**
```json
{
  "totalRevenue": 125000.50,
  "iapRevenue": 100000.00,
  "adRevenue": 20000.50,
  "subscriptionRevenue": 5000.00,
  "days": 31
}
```

---

### 1.2 ARPU/ARPPU Trends

**Endpoint:** `GET /revenue/{gameId}/arpu`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `date` | date | Date |
| `arpu` | number | Average Revenue Per User |
| `arppu` | number | Average Revenue Per Paying User |
| `payingUsers` | number | Number of paying users |
| `totalUsers` | number | Total number of users |

**Metric meaning:**
- **ARPU** = Total revenue / Total users
  - Healthy value: varies by game genre

- **ARPPU** = Total revenue / Number of paying users
  - Usually ARPPU > ARPU * 10

---

### 1.3 Revenue Distribution by Platform

**Endpoint:** `GET /revenue/{gameId}/by-platform`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `platform` | string | Platform (ANDROID/IOS/WEB) |
| `revenue` | number | Revenue for that platform |
| `arpu` | number | ARPU for that platform |
| `arppu` | number | ARPPU for that platform |

---

## 2. Ads Analytics

### 2.1 Ad Performance Overview

**Endpoint:** `GET /ads/{gameId}/overview`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `totalRevenue` | number | Total ad revenue |
| `totalImpressions` | number | Total impressions |
| `avgEcpm` | number | Average eCPM (revenue per thousand impressions) |
| `avgFillRate` | number | Average fill rate |
| `networkCount` | number | Number of ad networks |

**Metric meaning:**
- **eCPM** = (Ad revenue / Impressions) * 1000
  - Healthy value: $10-$50 (varies by region and ad type)

- **Fill rate** = Filled count / Request count
  - Healthy value: > 90%

---

### 2.2 Performance by Ad Network

**Endpoint:** `GET /ads/{gameId}/by-network`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `network` | string | Ad network (ADMOB/UNITY_ADS/...) |
| `revenue` | number | Revenue for that network |
| `impressions` | number | Impressions for that network |
| `ecpm` | number | eCPM for that network |
| `fillRate` | number | Fill rate for that network |

---

## 3. Session Analytics

### 3.1 Session Overview

**Endpoint:** `GET /sessions/{gameId}/overview`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `avgSessionDuration` | number | Average session duration (milliseconds) |
| `avgEventsPerSession` | number | Average number of events per session |
| `avgBounceRate` | number | Average bounce rate |
| `days` | number | Number of days counted |

**Metric meaning:**
- **Session duration** - How long a user plays in a single session
  - Healthy value: > 5 minutes (casual games), > 30 minutes (hardcore games)

- **Session depth** - Number of events per session
  - Healthy value: > 10 events

- **Bounce rate** - Share of sessions with only 1 event
  - Healthy value: < 30%

---

### 3.2 Session Trends

**Endpoint:** `GET /sessions/{gameId}/trends`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `date` | date | Date |
| `avgDuration` | number | Average session duration for the day |
| `avgEvents` | number | Average number of events for the day |
| `bounceRate` | number | Bounce rate for the day |

---

## 4. Performance Monitoring

### 4.1 Performance Overview

**Endpoint:** `GET /performance/{gameId}/overview`

**Parameters:**
| Parameter | Type | Required | Description |
|------|------|------|------|
| `gameId` | string | Yes | Game ID |
| `startDate` | datetime | Yes | Start time (ISO 8601) |
| `endDate` | datetime | Yes | End time (ISO 8601) |

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `FPS` | object | Frame rate statistics |
| `LAG` | object | Lag statistics |
| `CRASH` | object | Crash statistics |
| `MEMORY` | object | Memory statistics |
| `LOAD_TIME` | object | Load time statistics |

**Each metric contains:**
- `avgValue` - Average value
`count` - Number of events

**Metric meaning:**
- **FPS (frame rate)** - Game smoothness
  - Healthy value: > 30 FPS (mobile), > 60 FPS (PC)
  - Warning value: < 20 FPS

- **LAG (stutter)** - Duration of screen freezes (milliseconds)
  - Healthy value: < 100ms
  - Warning value: > 500ms

- **CRASH (crash)** - Number of app crashes
  - Healthy value: < 0.1% of sessions
  - Warning value: > 1% of sessions

- **MEMORY (memory)** - Memory usage (MB)
  - Healthy value: < 500MB (mobile)
  - Warning value: > 1GB

---

### 4.2 Crash Grouping

**Endpoint:** `GET /performance/{gameId}/crashes`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `crashHash` | string | Crash hash (used for grouping) |
| `count` | number | Number of crashes |
| `firstSeen` | datetime | First seen time |
| `lastSeen` | datetime | Last seen time |

---

## 5. Social Analytics

### 5.1 Social Overview

**Endpoint:** `GET /social/{gameId}/overview`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `totalFriendships` | number | Total number of friendships |
| `totalGuilds` | number | Total number of guilds |
| `avgViralCoefficient` | number | Average viral coefficient |
| `days` | number | Number of days counted |

**Metric meaning:**
- **Viral coefficient** - Average number of new users invited per user
  - > 1 indicates organic user growth
  - Healthy value: > 0.5

---

### 5.2 Social Impact on Retention

**Endpoint:** `GET /social/{gameId}/retention-impact`

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `socialUsersD7Retention` | number | D7 retention rate of social users |
| `nonSocialUsersD7Retention` | number | D7 retention rate of non-social users |
| `retentionLift` | number | Retention lift brought by social features |

---

## Best Practices

### 1. Data Collection
- Ensure events are reported correctly
- Use standard event types
- Include the necessary dimension information

### 2. Analysis Cycle
- Review key metrics daily
- Analyze trends weekly
- Perform in-depth analysis monthly

### 3. Alert Settings
- Set thresholds for key metrics
- Monitor abnormal fluctuations
- Respond to issues promptly

### 4. Action Recommendations
- Make decisions based on data
- Validate hypotheses with A/B tests
- Continuously optimize and iterate
