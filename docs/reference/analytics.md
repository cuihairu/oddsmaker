# Analytics API

分析 API 覆盖收入、广告、会话、性能和社交五类，均按 gameId 出数。

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

---

### 1.2 ARPU/ARPPU 趋势

**端点:** `GET /revenue/{gameId}/arpu`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `date` | date | 日期 |
| `arpu` | number | 每用户平均收入 (Average Revenue Per User) |
| `arppu` | number | 每付费用户平均收入 (Average Revenue Per Paying User) |
| `payingUsers` | number | 付费用户数 |
| `totalUsers` | number | 总用户数 |

**指标含义:**
- **ARPU** = 总收入 / 总用户数
  - 健康值：因游戏类型而异
  
- **ARPPU** = 总收入 / 付费用户数
  - 通常 ARPPU > ARPU * 10

---

### 1.3 按平台收入分布

**端点:** `GET /revenue/{gameId}/by-platform`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `platform` | string | 平台 (ANDROID/IOS/WEB) |
| `revenue` | number | 该平台收入 |
| `arpu` | number | 该平台 ARPU |
| `arppu` | number | 该平台 ARPPU |

---

## 二、广告分析

### 2.1 广告性能概览

**端点:** `GET /ads/{gameId}/overview`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `totalRevenue` | number | 广告总收入 |
| `totalImpressions` | number | 总展示次数 |
| `avgEcpm` | number | 平均 eCPM (每千次展示收入) |
| `avgFillRate` | number | 平均填充率 |
| `networkCount` | number | 广告网络数量 |

**指标含义:**
- **eCPM** = (广告收入 / 展示次数) * 1000
  - 健康值：$10-$50 (因地区和广告类型而异)

- **填充率** = 填充次数 / 请求次数
  - 健康值：> 90%

---

### 2.2 按广告网络性能

**端点:** `GET /ads/{gameId}/by-network`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `network` | string | 广告网络 (ADMOB/UNITY_ADS/...) |
| `revenue` | number | 该网络收入 |
| `impressions` | number | 该网络展示次数 |
| `ecpm` | number | 该网络 eCPM |
| `fillRate` | number | 该网络填充率 |

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
  - 健康值：> 5 分钟 (休闲游戏), > 30 分钟 (重度游戏)

- **会话深度** - 每会话事件数
  - 健康值：> 10 个事件

- **跳出率** - 只有 1 个事件的会话占比
  - 健康值：< 30%

---

### 3.2 会话趋势

**端点:** `GET /sessions/{gameId}/trends`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `date` | date | 日期 |
| `avgDuration` | number | 当日平均会话时长 |
| `avgEvents` | number | 当日平均事件数 |
| `bounceRate` | number | 当日跳出率 |

---

## 四、性能监控

### 4.1 性能概览

**端点:** `GET /performance/{gameId}/overview`

**参数:**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `gameId` | string | 是 | 游戏 ID |
| `startDate` | datetime | 是 | 开始时间 (ISO 8601) |
| `endDate` | datetime | 是 | 结束时间 (ISO 8601) |

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `FPS` | object | 帧率统计 |
| `LAG` | object | 卡顿统计 |
| `CRASH` | object | 崩溃统计 |
| `MEMORY` | object | 内存统计 |
| `LOAD_TIME` | object | 加载时间统计 |

**每个指标包含:**
- `avgValue` - 平均值
`count` - 事件数量

**指标含义:**
- **FPS (帧率)** - 游戏流畅度
  - 健康值：> 30 FPS (移动端), > 60 FPS (PC)
  - 警告值：< 20 FPS

- **LAG (卡顿)** - 画面停顿时长 (毫秒)
  - 健康值：< 100ms
  - 警告值：> 500ms

- **CRASH (崩溃)** - 应用崩溃次数
  - 健康值：< 0.1% 会话
  - 警告值：> 1% 会话

- **MEMORY (内存)** - 内存使用量 (MB)
  - 健康值：< 500MB (移动端)
  - 警告值：> 1GB

---

### 4.2 崩溃分组

**端点:** `GET /performance/{gameId}/crashes`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `crashHash` | string | 崩溃哈希 (用于分组) |
| `count` | number | 崩溃次数 |
| `firstSeen` | datetime | 首次出现时间 |
| `lastSeen` | datetime | 最后出现时间 |

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
  - > 1 表示用户自发增长
  - 健康值：> 0.5

---

### 5.2 社交对留存的影响

**端点:** `GET /social/{gameId}/retention-impact`

**响应字段:**
| 字段 | 类型 | 说明 |
|------|------|------|
| `socialUsersD7Retention` | number | 社交用户 7 日留存率 |
| `nonSocialUsersD7Retention` | number | 非社交用户 7 日留存率 |
| `retentionLift` | number | 社交带来的留存提升 |

---

## 最佳实践

### 1. 数据采集
- 确保事件正确上报
- 使用标准事件类型
- 包含必要的维度信息

### 2. 分析周期
- 每日查看关键指标
- 每周分析趋势
- 每月深度分析

### 3. 告警设置
- 设置关键指标阈值
- 监控异常波动
- 及时响应问题

### 4. 行动建议
- 基于数据做决策
- A/B 测试验证假设
- 持续优化迭代
