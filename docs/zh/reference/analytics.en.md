# Analytics API

The analytics API covers five categories — revenue, ads, sessions, performance and social — all keyed by gameId.

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
| `days` | number | Days counted |

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

**Parameters:**
| Parameter | Type | Required | Description |
|------|------|------|------|
| `gameId` | string | Yes | Game ID |
| `startDate` | date | Yes | Start date (YYYY-MM-DD) |
| `endDate` | date | Yes | End date (YYYY-MM-DD) |

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `date` | date | Date |
| `arpu` | number | Average revenue per user |
| `arppu` | number | Average revenue per paying user |
| `payingUsers` | number | Paying users |
| `totalUsers` | number | Total users |

**Metric meanings:**
- **ARPU** = total revenue / total users
  
- **ARPPU** = total revenue / paying users

---

### 1.3 Revenue by Platform

**Endpoint:** `GET /revenue/{gameId}/by-platform?date=2024-01-15`

Returns the revenue rows per platform for a single day (fields such as `platform` and revenue amount).

---

## 2. Ads Analytics

### 2.1 Ads Performance Overview

**Endpoint:** `GET /ads/{gameId}/overview`

**Parameters:**
| Parameter | Type | Required | Description |
|------|------|------|------|
| `gameId` | string | Yes | Game ID |
| `startDate` | date | Yes | Start date (YYYY-MM-DD) |
| `endDate` | date | Yes | End date (YYYY-MM-DD) |

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `totalRevenue` | number | Total ad revenue |
| `totalImpressions` | number | Total impressions |
| `avgEcpm` | number | Average eCPM |
| `avgFillRate` | number | Average fill rate |
| `networkCount` | number | Number of ad networks |

**Metric meanings:**
- **eCPM** = (ad revenue / impressions) * 1000
  
- **Fill rate** = filled requests / requests

### 2.2 Performance by Ad Network

**Endpoint:** `GET /ads/{gameId}/by-network?startDate=2024-01-01&endDate=2024-01-31`

Returns the performance rows per ad network for the date range (impressions, revenue, eCPM and other fields).

---

## 3. Session Analytics

### 3.1 Session Overview

**Endpoint:** `GET /sessions/{gameId}/overview`

**Parameters:**
| Parameter | Type | Required | Description |
|------|------|------|------|
| `gameId` | string | Yes | Game ID |
| `startDate` | date | Yes | Start date (YYYY-MM-DD) |
| `endDate` | date | Yes | End date (YYYY-MM-DD) |

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `avgSessionDuration` | number | Average session duration (milliseconds) |
| `avgEventsPerSession` | number | Average events per session |
| `avgBounceRate` | number | Average bounce rate |
| `days` | number | Days counted |

**Metric meanings:**
- **Session duration** - how long a user plays in a single session
- **Session depth** - events per session, reflecting user engagement
- **Bounce rate** - share of sessions with only 1 event

### 3.2 Session Trends

**Endpoint:** `GET /sessions/{gameId}/trends?startDate=2024-01-01&endDate=2024-01-31`

Returns daily session trend rows for the date range (session count, duration and other fields).

---

## 4. Performance Monitoring

### 4.1 Performance Overview

**Endpoint:** `GET /performance/{gameId}/overview`

**Parameters:**
| Parameter | Type | Required | Description |
|------|------|------|------|
| `gameId` | string | Yes | Game ID |
| `startDate` | datetime | Yes | Start time (ISO 8601 datetime, at datetime granularity; e.g. 2024-01-01T00:00:00) |
| `endDate` | datetime | Yes | End time (ISO 8601 datetime, at datetime granularity; e.g. 2024-01-31T23:59:59) |

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `FPS` | object | Frame rate statistics |
| `LAG` | object | Lag statistics |
| `CRASH` | object | Crash statistics |
| `MEMORY` | object | Memory statistics |

**Metric meanings:**
- **FPS** - game smoothness (frames per second)
- **Lag** - frame freeze duration (milliseconds)
- **Crash** - number of application crashes
- **Memory** - memory usage (MB)

### 4.2 Crash Groups

**Endpoint:** `GET /performance/{gameId}/crashes`

Returns the list of crash fingerprint groups (grouped by `crash_hash`, same source as top-groups under `/api/crash-metrics`).

The full crash-specific metrics live under `/api/crash-metrics`:

```http
GET /api/crash-metrics/{gameId}/top-groups
GET /api/crash-metrics/{gameId}/trend
GET /api/crash-metrics/{gameId}/rate-by-version
POST /api/crash-metrics/{gameId}/symbolicate
```

---

## 5. Social Analytics

### 5.1 Social Overview

**Endpoint:** `GET /social/{gameId}/overview`

**Parameters:**
| Parameter | Type | Required | Description |
|------|------|------|------|
| `gameId` | string | Yes | Game ID |
| `startDate` | date | Yes | Start date (YYYY-MM-DD) |
| `endDate` | date | Yes | End date (YYYY-MM-DD) |

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `totalFriendships` | number | Total friendships |
| `totalGuilds` | number | Total guilds |
| `avgViralCoefficient` | number | Average viral coefficient |
| `days` | number | Days counted |

**Metric meanings:**
- **Viral coefficient** - the average number of new users invited per user

### 5.2 Social Impact on Retention

**Endpoint:** `GET /social/{gameId}/retention-impact`

**Parameters:**
| Parameter | Type | Required | Description |
|------|------|------|------|
| `gameId` | string | Yes | Game ID |
| `date` | date | Yes | Statistics date (YYYY-MM-DD) |

**Response fields:**
| Field | Type | Description |
|------|------|------|
| `socialUsersD7Retention` | number | D7 retention of social users |
| `nonSocialUsersD7Retention` | number | D7 retention of non-social users |
| `retentionLift` | number | Retention lift brought by social features |

