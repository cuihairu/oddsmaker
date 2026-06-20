# 分析 API

分析 API 提供游戏运营所需的全方位数据分析能力，包括收入、广告、会话、性能和社交分析。

## 基础 URL

```
http://localhost:8085/api/analytics
```

## 认证

所有端点需要 Bearer Token 认证：

```http
Authorization: Bearer {token}
```

---

## 一、收入分析

### 1.1 收入概览

**端点:** `GET /revenue/{gameId}/overview`

**参数:**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `gameId` | string | 是 | 游戏 ID |
| `startDate` | date | 是 | 开始日期 (YYYY-MM-DD) |
| `endDate` | date | 是 | 结束日期 (YYYY-MM-DD) |

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `totalRevenue` | number | 总收入 |
| `iapRevenue` | number | 应用内购买收入 |
| `adRevenue` | number | 广告收入 |
| `subscriptionRevenue` | number | 订阅收入 |
| `days` | number | 统计天数 |

**示例:**
```http
GET /api/analytics/revenue/game_123/overview?startDate=2024-01-01&endDate=2024-01-31
```

**响应:**
```json
{
  "totalRevenue": 125000.50,
  "iapRevenue": 100000.00,
  "adRevenue": 20000.50,
  "subscriptionRevenue": 5000.00,
  "days": 31
}
```

**分析价值:**
- 了解收入构成（IAP vs 广告 vs 订阅）
- 识别收入趋势
- 评估商业化策略效果

---

### 1.2 ARPU/ARPPU 趋势

**端点:** `GET /revenue/{gameId}/arpu`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `date` | date | 日期 |
| `arpu` | number | 每用户平均收入 |
| `arppu` | number | 每付费用户平均收入 |
| `payingUsers` | number | 付费用户数 |
| `totalUsers` | number | 总用户数 |

**指标含义:**
- **ARPU** = 总收入 / 总用户数
  - 反映整体变现能力
  
- **ARPPU** = 总收入 / 付费用户数
  - 反映付费用户消费能力

**分析价值:**
- 评估用户付费意愿
- 监控付费用户价值
- 优化定价策略

---

## 二、广告分析

### 2.1 广告性能概览

**端点:** `GET /ads/{gameId}/overview`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `totalRevenue` | number | 广告总收入 |
| `totalImpressions` | number | 总展示次数 |
| `avgEcpm` | number | 平均 eCPM |
| `avgFillRate` | number | 平均填充率 |
| `networkCount` | number | 广告网络数量 |

**指标含义:**
- **eCPM** = (广告收入 / 展示次数) * 1000
  - 反映广告变现效率
  
- **填充率** = 填充次数 / 请求次数
  - 反映广告可用性

---

## 三、会话分析

### 3.1 会话概览

**端点:** `GET /sessions/{gameId}/overview`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `avgSessionDuration` | number | 平均会话时长 (毫秒) |
| `avgEventsPerSession` | number | 平均每会话事件数 |
| `avgBounceRate` | number | 平均跳出率 |
| `days` | number | 统计天数 |

**指标含义:**
- **会话时长** - 用户单次游戏时长
- **会话深度** - 每会话事件数，反映用户参与度
- **跳出率** - 只有 1 个事件的会话占比

---

## 四、性能监控

### 4.1 性能概览

**端点:** `GET /performance/{gameId}/overview`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `FPS` | object | 帧率统计 |
| `LAG` | object | 卡顿统计 |
| `CRASH` | object | 崩溃统计 |
| `MEMORY` | object | 内存统计 |

**指标含义:**
- **FPS** - 游戏流畅度（帧/秒）
- **卡顿** - 画面停顿时长（毫秒）
- **崩溃** - 应用崩溃次数
- **内存** - 内存使用量（MB）

---

## 五、社交分析

### 5.1 社交概览

**端点:** `GET /social/{gameId}/overview`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `totalFriendships` | number | 总好友关系数 |
| `totalGuilds` | number | 总公会数 |
| `avgViralCoefficient` | number | 平均病毒系数 |
| `days` | number | 统计天数 |

**指标含义:**
- **病毒系数** - 每个用户平均邀请的新用户数

### 5.2 社交对留存的影响

**端点:** `GET /social/{gameId}/retention-impact`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `socialUsersD7Retention` | number | 社交用户 7 日留存率 |
| `nonSocialUsersD7Retention` | number | 非社交用户 7 日留存率 |
| `retentionLift` | number | 社交带来的留存提升 |

**分析价值:**
- 量化社交功能对留存的影响
- 优化社交功能设计
- 证明社交功能 ROI
