# 02 - 设计问题清单（14 个关键缺陷）

按严重程度分级：🔴 P0（必须立即修复） / 🟠 P1（影响核心能力） / 🟡 P2（优化）。

---

## 🔴 P0-1 Android SDK 客户端嵌入 HMAC Secret（安全漏洞）

**位置**：`sdks/android/pit-android/src/main/java/io/pit/android/Pit.kt:33,154-159`

**问题**：
```kotlin
val hmacSecret: String? = null, // Caution: embedding secret in client is not recommended for production.
...
opts.hmacSecret?.let { secret ->
  val t = (System.currentTimeMillis() / 1000).toString()
  val msg = "$t." + String(bodyBytes, Charsets.UTF_8)
  val s = hmacSha256Hex(secret, msg)
  reqBuilder.addHeader("x-signature", "t=$t, s=$s")
}
```

**风险**：
- 反编译 APK 即可拿到 HMAC secret
- 可被用于伪造任意事件，污染数据
- 绕过配额、限流、计费

**正确做法**：
- 客户端 SDK（Web/Android/iOS/Unity）仅持 `public api_key`（公开，仅可写入）
- HMAC 仅用于 Server SDK（服务端到服务端，secret 不下发）
- 客户端事件合法性靠：限流 + Schema 校验 + 设备指纹 + PII 治理

---

## 🔴 P0-2 租户模型概念混乱（Project vs Game）

**位置**：
- `services/control-service/.../jpa/ProjectEntity.java`（13 行裸实体）
- `services/control-service/.../jpa/ApiKeyEntity.java:215-221`（向后兼容方法）
- `schema/avro/pit-event.avsc`（`project_id` 字段）
- `schema/sql/clickhouse/schema.sql`（`project_id` 分区）

**问题**：
- `ProjectEntity` 是早期单一项目模型遗留
- 新引入 `Organization → Game → Environment` 但事件层仍用 `project_id`
- `ApiKeyEntity.getProjectId()` 实际返回 `gameId`，注释为"向后兼容"
- ClickHouse 所有表分区键 = `project_id`，但语义未明确
- Flink 作业 `EventsEnrichJob`/`SessionsJob`/`RetentionJob` 全部按 `project_id` 处理

**风险**：
- 多租户隔离失效：同 `project_id` 不同环境数据混淆
- 跨游戏分析错误：把多游戏聚合到同一 `project_id`
- 后续迁移成本累积

**正确做法**：
- 废弃 `ProjectEntity`，删除 `ProjectRepo`
- 统一为：`Organization(tenant_id) → Game → Environment → App(app_id)`
- 事件 schema 字段改：`tenant_id` + `app_id`（其中 `app_id = game_id + env`）
- ClickHouse 表分区改：`PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))`

---

## 🔴 P0-3 新版企业控制器全部 disabled（核心未接通）

**位置**：
```
services/control-service/src/main/java/io/pit/control/
  controller/AuthController.java.disabled
  controller/UserController.java.disabled
  controller/OrganizationController.java.disabled
  controller/GameController.java.disabled
  controller/ApiKeyController.java.disabled
  config/SecurityConfig.java.disabled
  security/JwtAuthenticationFilter.java.disabled
  security/JwtUtil.java.disabled
  experiment/ExperimentController.java.disabled
  experiment/JsonSchemaService.java.disabled
services/gateway-service/src/main/java/io/pit/gateway/
  api/BatchController.java.disabled
```

**问题**：
- 文档 `docs/api-reference.md` 描述的 Organization/Game/User/ApiKey 完整 CRUD API 全部未启用
- 实际生效的 `ApiController` 仅基于 `ProjectEntity` 老模型
- 等于"新多租户模型已设计但未上线"

**正确做法**：
- 逐一启用 disabled 控制器，按 Organization/Game/User/ApiKey 实体接通 DB
- SecurityConfig + JwtUtil 配齐 Spring Security
- 删除/迁移 ApiController（保留旧接口做兼容期）

---

## 🟠 P1-4 事件模型脱离游戏行业（最严重的能力缺陷）

**位置**：`schema/avro/pit-event.avsc`

**问题**：
当前 13 字段全是通用分析字段，缺失所有游戏专业字段：

```
缺失：level_id / progression / match_id / guild_id / server_id / character_id /
      item_id / virtual_currency / flow_type / ad_placement / experiments /
      difficulty / game_mode / player_id / iap_receipt / ad_format / ad_network /
      error_severity / error_stack / attribution
```

全代码库 grep `level_id|player_level|game_mode|achievement|guild_id` — **零匹配**。

**正确做法**：
- 引入 7 类标准事件（详见 [04-redesign.zh.md](./04-redesign.zh.md)）
- Avro schema 扩展到 60+ 字段
- ClickHouse 表使用 `Map(String, String)` 兼顾 schema 稳定与扩展性

---

## 🟠 P1-5 事件命名无 Taxonomy（Cardinality 失控风险）

**位置**：`schema/sql/clickhouse/schema.sql` 中 `event_name LowCardinality(String)`

**问题**：
- 无命名规范强制
- `event_name = "levelStart" / "level_start" / "Level_Start_v2"` 都能写入
- LowCardinality 字典会膨胀

**正确做法**：
- 强制 `category:sub_category:outcome`（与 GameAnalytics 一致）
- Tracking Plan YAML 在 CI 阶段校验 SDK 调用与文档一致
- 引入 Schema Registry 版本兼容性检查（backward/forward）

---

## 🟠 P1-6 留存口径单一（仅 0/1/7/30）

**位置**：`jobs/flink/retention-job/.../RetentionJob.java` + `schema/sql/clickhouse/schema.sql:121-130`

**问题**：
- `retention_daily` 仅按 cohort 日 + 偏移 d（0/1/7/30）聚合
- 缺失：
  - N-Day（任意 N，比如 D3/D14/D60）
  - Rolling Retention（截至 N 日累计回来过）
  - 分层留存（付费/等级/渠道/行为）
  - 首日事件触发留存

**正确做法**：
- 新表 `retention`：`(tenant_id, app_id, cohort_date, d_offset, retention_type, users)`
- `retention_type`: `nday | rolling | first_event | paid | level | channel`
- 双口径并列支持（行业基准：D1 ~35-40%、D7 ~15-20%、D30 ~5-10%）

---

## 🟠 P1-7 漏斗仅支持 2 步

**位置**：`jobs/flink/funnels-job/.../FunnelsJob.java` + `schema/sql/clickhouse/schema.sql:132-144`

**问题**：
- `funnels_2step` 表只支持 `step1`/`step2`
- 真实游戏漏斗需要：新手引导 6 步、付费路径 4-5 步、关卡路径任意步

**正确做法**：
- 配置驱动的 N 步漏斗：`funnels` 表 `(tenant_id, app_id, funnel_id, version, ts, user_id, step_completed, completed_all)`
- 支持有序/无序、超时窗口、按用户分群切片
- Flink 作业改造为从 Control Service 拉取漏斗配置

---

## 🟠 P1-8 缺失 Identity Merge（用户身份合并）

**位置**：`schema/avro/pit-event.avsc` 中 `user_id` 与 `device_id` 平行字段

**问题**：
- 游戏典型场景：游客（device_id）→ 注册账号（user_id）→ 第三方登录（openid）
- 当前没有 `identities` 表来合并多 device → user
- 所有留存/LTV 都失真（同玩家被分成多个身份）

**正确做法**：
- 新增 `identities` 表（参考 Mixpanel `$identify`/`$merge`）
- Flink enrich 作业维护 `device_id → user_id` 映射（状态机）
- 查询层走 `$user_id` 而非 `device_id`

---

## 🟠 P1-9 实验平台仅"曝光"层（缺统计显著性）

**位置**：
- `sdks/web/src/index.ts:248-380`（客户端 SDK 曝光）
- `schema/sql/clickhouse/queries_experiment*.sql`（仅曝光数 + 同日转化）

**问题**：
- 仅记录 `experiment_exposure` 事件
- 缺失：实验配置、目标事件绑定、显著性检验、SRM 检查、CUPED 方差缩减

**正确做法**：
- Control Service 提供 `/api/experiments` 完整 CRUD（流量分桶、变体、目标、定向）
- 分析作业计算：提升 %、p-value、95% CI、CUPED 调整后效果、SRM（Sample Ratio Mismatch）
- 对标 GrowthBook / Statsig

---

## 🟠 P1-10 缺失广告相关字段

**位置**：`schema/avro/pit-event.avsc`

**问题**：游戏广告变现是核心收入之一（尤其超休闲游戏），但 schema 完全缺失：
```
缺失：ad_impression / ad_click / ad_reward / placement / ad_network / eCPM / fill_rate
```

**正确做法**：
- 新增 `event_type = "ad"` 枚举
- 字段：`ad_placement`、`ad_network`、`ad_format`（interstitial/rewarded/banner）
- 物化视图：日 ad impressions / revenue / eCPM

---

## 🟡 P2-11 实时能力薄弱（仅每日聚合）

**位置**：`bi/superset/bundle/dashboards/`

**问题**：
- Flink 作业全部"按事件→ClickHouse"，无 5 分钟实时滚动大屏
- Superset 看板默认 90 天区间，非实时
- 缺失：今日新增、今日付费、今日异常告警

**正确做法**：
- ClickHouse 物化视图：`realtime_5min (tenant_id, app_id, ts, dau, sessions, events, revenue)`
- Redis 计数器加速今日实时指标
- Flink CEP 作业做异常检测（作弊、刷榜、异常付费）

---

## 🟡 P2-12 缺失 Crash/Error 体系

**位置**：`services/control-service/.../jpa/GameEntity.java:99-100`（仅有开关字段）

**问题**：
- GameAnalytics 把 Error 作为 7 大事件之一
- 含 severity、stacktrace、symbolication
- Pit 仅在 `GameEntity.enableCrashReporting` 字段上有开关，零实现

**正确做法**：
- 事件 schema 新增 `event_type = "error"` + `error_severity` + `error_stack`
- SDK 提供 `pit.error(severity, message, stack)` 方法
- 服务端符号化（dSYM/Proguard mapping）
- Crash 聚合：按 stack hash 聚合、趋势、影响用户数

---

## 🟡 P2-13 SDK 字段不统一

**位置**：`sdks/{web,android,ios,unity}/`

**问题**：
| SDK | HMAC | gzip | offline | A/B | session 算法 |
|---|---|---|---|---|---|
| Web | ❌ | CompressionStream | localStorage | ✅ | 30 min 固定 |
| Android | ✅（漏洞）| GZIPOutputStream | SharedPreferences | ✅ | 30 min 固定 |
| iOS | ? | ? | ? | ? | ? |
| Unity | - | - | - | - | - |

**正确做法**：
- 客户端 SDK 全部统一为：仅 `api_key` 鉴权、NDJSON + gzip、IndexedDB/Room/CoreData offline、内置 A/B
- HMAC 仅 Server SDK
- 类型化 API：`pit.session()/business()/resource()/progression()/design()/error()/ad()`

---

## 🟡 P2-14 PII 治理代码缺失（文档有，代码无）

**位置**：
- `services/control-service/.../jpa/ApiKeyEntity.java:73-85`（DB 字段完整）
- `docs/api.zh.md` 描述 PII 策略
- `services/gateway-service/.../api/BatchController.java.disabled`（实际处理代码 disabled）

**问题**：
- ApiKeyEntity 有 `piiEmail/piiPhone/piiIp/denyKeys/maskKeys`
- 但 Gateway 代码看不到 PII 处理逻辑（BatchController disabled）
- 等于"治理策略可配置但不执行"

**正确做法**：
- 启用 BatchController，落地 PII 处理
- `denyKeys` → 直接拒绝事件入 DLQ
- `maskKeys` → 邮箱/电话 MD5 + 截断
- `coarse` → IP 转 /24（v4）或 /48（v6）
- 单元测试覆盖

---

## 优先级矩阵

| 优先级 | 编号 | 标题 | 影响 | 修复成本 |
|---|---|---|---|---|
| 🔴 P0 | 1 | Android HMAC 漏洞 | 安全 | 0.5 天 |
| 🔴 P0 | 2 | 租户模型 Project vs Game | 数据隔离 | 3-5 天 |
| 🔴 P0 | 3 | disabled 控制器接通 | 业务阻塞 | 5-7 天 |
| 🟠 P1 | 4 | 游戏事件模型缺失 | 核心定位 | 7-10 天 |
| 🟠 P1 | 5 | Taxonomy | Cardinality | 2-3 天 |
| 🟠 P1 | 6 | 双口径留存 | 核心指标 | 3-5 天 |
| 🟠 P1 | 7 | N 步漏斗 | 核心指标 | 3-5 天 |
| 🟠 P1 | 8 | Identity Merge | 数据准确 | 5-7 天 |
| 🟠 P1 | 9 | A/B 完整平台 | 核心能力 | 7-10 天 |
| 🟠 P1 | 10 | 广告字段 | 商业化 | 2-3 天 |
| 🟡 P2 | 11 | 实时大屏 | 运营效率 | 5-7 天 |
| 🟡 P2 | 12 | Crash 报告 | 客户质量 | 7-10 天 |
| 🟡 P2 | 13 | SDK 字段统一 | 一致性 | 5-7 天 |
| 🟡 P2 | 14 | PII 治理代码化 | 合规 | 3-5 天 |

详细落地计划见 [05-roadmap.zh.md](./05-roadmap.zh.md)。
