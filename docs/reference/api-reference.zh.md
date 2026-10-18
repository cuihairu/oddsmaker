# Oddsmaker Control Service API 参考

> 🌐 Language: **中文** · English → [api-reference.md](api-reference.md)

Oddsmaker 是一个单公司、多游戏的分析与风控平台。control 服务管理游戏、环境、存储档案、API key、埋点方案（tracking plan）、风控规则、用户、角色分配与审计日志。

Oddsmaker 不把 `Organization` 或 `Tenant` 建模为目标业务资源。核心业务边界是 `game_id + environment`，物理路由则由 `storage_profile` 控制。

本页是一个精选索引，并非完整的接口面——每个 controller 也会在 `GET /v3/api-docs` 提供 OpenAPI 文档（Swagger UI 位于 `/swagger-ui.html`）。下方未详述的资源分组列于文末的"其他资源分组"。

## 认证

大多数端点接受来自 `POST /api/auth/login` 的 bearer token；引导/仅管理员端点（用户创建、key 签发、key 策略）接受 `x-admin-token`：

```http
Authorization: Bearer YOUR_TOKEN
x-admin-token: YOUR_ADMIN_TOKEN
```

## 资源

### 游戏

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/games` | 创建游戏 |
| GET | `/api/games` | 列出游戏 |
| GET | `/api/games/{gameId}` | 获取游戏详情 |
| PUT | `/api/games/{gameId}` | 更新游戏 |
| DELETE | `/api/games/{gameId}` | 删除或归档游戏 |
| POST | `/api/games/{gameId}/publish` | 将游戏转为上线状态 |
| POST | `/api/games/{gameId}/unpublish` | 将上线游戏转为维护状态 |
| GET | `/api/games/{gameId}/readiness` | 分析就绪度：`available`（ClickHouse 可达）+ `analysisLastEventAt`（ISO-8601 UTC，采集→分析链路最近一次落数；`null`=从未产出）。可选 `environment` 过滤 |

### 环境

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/games/{gameId}/environments` | 创建环境 |
| GET | `/api/games/{gameId}/environments` | 列出环境 |
| GET | `/api/games/{gameId}/environments/{environmentName}` | 获取环境配置 |
| PUT | `/api/games/{gameId}/environments/{environmentName}` | 更新环境配置 |
| DELETE | `/api/games/{gameId}/environments/{environmentName}` | 删除环境 |

推荐环境：`dev`、`staging`、`prod`。

环境是一个逻辑生命周期阶段，并非独享数据库的同义词。

### 存储档案

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/storage-profiles` | 创建存储路由档案 |
| GET | `/api/storage-profiles` | 列出存储档案 |
| GET | `/api/storage-profiles/{profileId}` | 获取存储档案详情 |
| PUT | `/api/storage-profiles/{profileId}` | 更新路由后端或隔离策略 |
| DELETE | `/api/storage-profiles/{profileId}` | 删除存储档案 |

### API Key

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/api-keys` | 创建绑定到 `gameId + environmentId` 的 API key |
| GET | `/api/api-keys?gameId=&environmentId=` | 列出 API key |
| GET | `/api/api-keys/{keyId}` | 获取 API key 详情 |
| PUT | `/api/api-keys/{keyId}` | 更新限额或策略绑定 |
| DELETE | `/api/api-keys/{keyId}` | 删除 API key |
| POST | `/api/keys` | 旧版兼容的创建端点 |
| GET | `/api/keys?gameId=&environmentId=` | 旧版兼容的查询端点 |
| GET | `/api/keys/{keyId}` | 旧版兼容的详情端点 |
| PUT | `/api/keys/{keyId}/policy` | 旧版兼容的策略更新端点 |
| DELETE | `/api/keys/{keyId}` | 旧版兼容的删除端点 |

Key 类型：

- `client`：面向客户端 SDK 的公开只写 key。
- `server`：服务间 key，附带一次性的 HMAC 密钥材料。
- `admin`：内部服务 key。

### 埋点方案（Tracking Plan）

方案的作用域是游戏（而非按环境）。方案只有激活/停用两种操作，没有单独的发布/回滚端点。

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/games/{gameId}/tracking-plans` | 创建方案（草稿） |
| GET | `/api/games/{gameId}/tracking-plans` | 列出某游戏的方案 |
| GET | `/api/games/{gameId}/tracking-plans/active` | 获取生效中的方案 |
| GET | `/api/games/{gameId}/tracking-plans/environment/{environmentId}` | 获取某环境的生效方案 |
| GET | `/api/games/{gameId}/tracking-plans/{trackingPlanId}` | 获取方案详情 |
| PUT | `/api/games/{gameId}/tracking-plans/{trackingPlanId}` | 更新草稿方案 |
| DELETE | `/api/games/{gameId}/tracking-plans/{trackingPlanId}` | 删除方案 |
| POST | `/api/games/{gameId}/tracking-plans/{trackingPlanId}/activate` | 激活方案 |
| POST | `/api/games/{gameId}/tracking-plans/{trackingPlanId}/deactivate` | 停用方案 |
| POST/GET | `/api/games/{gameId}/tracking-plans/{trackingPlanId}/events` | 在方案中注册 / 列出事件 |
| POST | `/api/games/{gameId}/tracking-plans/{trackingPlanId}/properties` | 注册属性定义 |

### 实验

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/experiments` | 创建实验 |
| GET | `/api/experiments?gameId=&environmentId=&environment=&status=` | 列出实验 |
| GET | `/api/experiments/{id}` | 获取实验详情 |
| PUT | `/api/experiments/{id}` | 更新实验 |
| DELETE | `/api/experiments/{id}` | 删除实验 |
| POST | `/api/experiments/{id}/publish` | 启动实验 |
| POST | `/api/experiments/{id}/pause` | 暂停实验 |
| GET | `/api/experiments/{id}/assign?userId=&deviceId=` | 确定性分组分配 |
| POST | `/api/experiments/{id}/metrics` | 接收指标快照 |
| POST | `/api/experiments/{id}/metrics/aggregate` | 在时间窗内聚合指标 |
| GET | `/api/experiments/{id}/results` | 包含 lift/CI/p-value/SRM 的结果 |

**公开端点**（无需认证）：
| GET | `/api/config/{gameId}/{environment}` | 为 SDK 获取运行中的实验配置 |

**字段**：
- `environmentId`：内部环境 ID（`environment` 的替代）
- `environment`：逻辑环境名，如 `dev`/`staging`/`prod`（`environmentId` 的替代）

### PII 与采样策略

没有独立的 PII 策略资源。摄入策略按 API key 绑定，并通过 key 策略端点更新：

| 方法 | 端点 | 说明 |
|---|---|---|
| PUT | `/api/keys/{keyId}/policy` | 设置 PII 处理（`piiEmail`/`piiPhone`/`piiIp`）、props 白名单、deny/mask key 以及速率限制（`rpm`、`ipRpm`） |

gateway 在 `/v1/batch` 上强制执行这些字段。强制执行语义参见[采集 API](/zh/reference/api)。

### 风控规则

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/risk-rules` | 创建风控规则 |
| GET | `/api/risk-rules?gameId=&environmentId=&status=` | 列出规则（多条件、分页） |
| GET | `/api/risk-rules/{ruleId}` | 获取风控规则详情 |
| PUT | `/api/risk-rules/{ruleId}` | 更新规则 |
| DELETE | `/api/risk-rules/{ruleId}` | 删除规则 |
| POST | `/api/risk-rules/{ruleId}/enable` | 启用规则 |
| POST | `/api/risk-rules/{ruleId}/disable` | 停用规则 |

risk-job 按定时器（默认 60 秒）从 `GET /api/risk-dashboard/rules/{gameId}` 拉取已启用的规则，因此规则变更无需重启即可生效。

### 风控案例与策略实验室

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/games/{gameId}/risk-cases?status=&riskLevel=&ruleId=&disposition=` | 列出风控案例（最新在前；`ruleId`/`disposition` 为策略实验室的样本过滤条件，在最近 2000 条案例内做内存过滤） |
| GET | `/api/games/{gameId}/risk-cases/{caseId}` | 案例详情（evidence/context JSON 解析为对象；当 ClickHouse 中存在案例目标的 `subjectRiskScore` 快照时返回，未评分或 CH 不可用时为 null） |
| POST | `/api/games/{gameId}/risk-cases/{caseId}/unblock` | 对误报手动解封（级联至关联的封禁名单条目） |
| GET | `/api/games/{gameId}/risk-lab/rule-stats` | 按规则的审核聚合（案例数、已处置样本的误报率、平均审核小时数、零案例与孤儿规则行） |
| POST | `/api/games/{gameId}/risk-lab/replay` | 策略实验室回放演练：用活跃规则对至多 500 条样本事件评分（THRESHOLD/FEATURE 逐事件评估；流式窗口类型报告为 `needsStreaming` 并跳过；同类型规则在 `effectiveHits` 中收敛到最高 riskScore）。无状态——样本不会被持久化 |
| GET | `/api/games/{gameId}/risk-lab/sample-sets` | 具名样本集列表（仅元数据：name、sampleCount、createdAt） |
| POST | `/api/games/{gameId}/risk-lab/sample-sets` | 创建具名样本集（`risk:manage`）：请求体 `{name, description?, samples}`；name 每个游戏唯一，样本按与回放相同的规则校验（1–500），存储后不可变 |
| GET | `/api/games/{gameId}/risk-lab/sample-sets/{id}` | 样本集详情，含解析后可用于回放加载的样本 |
| DELETE | `/api/games/{gameId}/risk-lab/sample-sets/{id}` | 删除样本集（`risk:manage`，留有审计）；如需替换请重新创建 |
| GET | `/api/games/{gameId}/risk-scores?subjectType=&subjectId=` | 主体累计风险分，取自 ClickHouse `risk_scores` 的最新快照（`reasons` 解析为 `[{ruleId,contribution}]`；从未评分时 `found:false`；未配置 ClickHouse 时报 `CH_UNAVAILABLE` 错误） |

### 封禁名单

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/block-lists/check?gameId=&targetType=&targetValue=` | 检查目标是否被封禁 |
| GET | `/api/block-lists/active/{gameId}` | 列出某游戏的活跃封禁 |
| GET | `/api/block-lists/{blockId}` | 获取封禁详情 |
| POST | `/api/block-lists` | 添加封禁 |
| POST | `/api/block-lists/{blockId}/unblock` | 移除封禁 |
| POST | `/api/block-lists/batch-unblock` | 批量移除封禁 |
| POST | `/api/block-lists/from-risk-case/{riskCaseId}` | 从风控案例创建封禁 |
| GET | `/api/block-lists/stats/{gameId}` | 获取封禁统计 |
| GET | `/api/block-lists/search/{gameId}?query=` | 搜索封禁 |
| GET | `/api/block-lists/by-type/{gameId}/{targetType}` | 按类型列出封禁 |

**目标类型**：`device_id`、`user_id`、`player_id`、`ip`、`ip_range`、`account_id`

**封禁类型**：`HARD`（完全封禁）、`SOFT`（功能限制）、`TEMPORARY`（限时）、`SHADOW`（静默限制）

**封禁类别**：`fraud`、`cheating`、`abuse`、`tos_violation`、`security`

### Flink 作业

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/flink-jobs` | 创建 Flink 作业 |
| GET | `/api/flink-jobs/{jobId}` | 获取作业详情 |
| GET | `/api/flink-jobs/game/{gameId}` | 列出某游戏的作业 |
| GET | `/api/flink-jobs/running/{gameId}` | 列出运行中的作业 |
| POST | `/api/flink-jobs/{jobId}/deploy` | 部署作业到 Flink 集群 |
| POST | `/api/flink-jobs/{jobId}/stop` | 停止运行中的作业 |
| POST | `/api/flink-jobs/{jobId}/refresh` | 从 Flink 集群刷新作业状态 |
| GET | `/api/flink-jobs/stats/{gameId}` | 获取作业统计 |
| GET | `/api/flink-jobs/{jobId}/config` | 获取作业配置 |
| GET | `/api/flink-jobs/{jobId}/rules` | 获取关联的风控规则 |
| POST | `/api/flink-jobs/{jobId}/metrics` | 更新作业指标 |

**作业类型**：`RISK_EVALUATION`、`FRAUD_DETECTION`、`ANOMALY_DETECTION`、`REALTIME_AGGREGATION`、`PATTERN_MATCHING`

**作业状态**：`DRAFT`、`DEPLOYING`、`RUNNING`、`STOPPING`、`STOPPED`、`FAILED`、`PAUSED`

### 风控仪表盘

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/risk-dashboard/overview/{gameId}?since=` | 获取风控概览统计 |
| GET | `/api/risk-dashboard/trends/{gameId}?since=&intervalHours=` | 获取风控随时间的趋势 |
| GET | `/api/risk-dashboard/high-risk-targets/{gameId}?since=&limit=` | 获取高风险目标列表 |
| GET | `/api/risk-dashboard/rule-performance/{gameId}` | 获取规则表现指标 |
| GET | `/api/risk-dashboard/block-stats/{gameId}` | 获取封禁统计 |
| GET | `/api/risk-dashboard/job-stats/{gameId}` | 获取 Flink 作业统计 |
| GET | `/api/risk-dashboard/dashboard/{gameId}?since=` | 获取完整仪表盘数据 |
| GET | `/api/risk-dashboard/rules/{gameId}` | 某游戏的生效规则（由 risk-job 读取） |
| GET | `/api/risk-dashboard/recent-cases/{gameId}?limit=` | 获取近期风控案例 |
| GET | `/api/risk-dashboard/review-queue-stats/{gameId}` | 获取审核队列统计 |

**仪表盘组件**：
- 总览：案例总数、高风险案例、待审核、活跃封禁
- 趋势：案例、封禁与审核的时间序列数据
- 高风险目标：被标记最频繁的目标
- 规则表现：触发次数、封禁率、效率指标
- 封禁统计：按类型、类别与范围的活跃封禁
- 作业统计：Flink 作业状态与吞吐指标

### Webhook

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/webhooks/game/{gameId}` | 列出某游戏的 webhook 配置 |
| GET | `/api/webhooks/configs/{configId}` | 获取 webhook 配置详情 |
| GET | `/api/webhooks/logs/{configId}` | 获取 webhook 投递日志 |
| GET | `/api/webhooks/stats/{gameId}` | 获取 webhook 统计 |
| POST | `/api/webhooks/test/{configId}` | 测试 webhook 端点 |
| POST/PUT/DELETE | `/api/webhooks/game/{gameId}/configs` | 管理 webhook 配置 |

**Webhook 认证类型**：`none`、`basic`、`bearer`、`api_key`（配置时不在此集合内的取值会被拒绝）

**单配置超时**：`timeoutSeconds`（1–300，默认 30）决定投递 HTTP 客户端的读超时；越界值会被钳制，缺省时取全局默认（`oddsmaker.webhook.read-timeout-ms`）。连接超时始终是全局的。

**Webhook 状态**：`ACTIVE`、`INACTIVE`、`PAUSED`、`FAILED`

**事件类型**：`risk_case`、`block`、`alert`、`review`

**投递状态**：`PENDING`、`SENDING`、`SUCCESS`、`FAILED`、`RETRYING`、`TIMEOUT`、`CANCELLED`

### 审核队列

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/review-queue/game/{gameId}` | 获取某游戏的审核队列 |
| GET | `/api/review-queue/pending/{gameId}` | 获取待审核项 |
| GET | `/api/review-queue/high-priority/{gameId}?minPriority=` | 获取高优先级项 |
| POST | `/api/review-queue/{queueItemId}/assign` | 为条目分配审核人 |
| POST | `/api/review-queue/{queueItemId}/claim` | 认领条目进行审核 |
| POST | `/api/review-queue/{queueItemId}/start` | 开始审核流程 |
| POST | `/api/review-queue/{queueItemId}/complete` | 完成审核并给出处置结论 |
| POST | `/api/review-queue/{queueItemId}/escalate` | 将条目升级至更高权限方 |
| POST | `/api/review-queue/{queueItemId}/cancel` | 取消审核 |
| GET | `/api/review-queue/reviewer/{reviewer}` | 获取审核人已分配的条目 |
| GET | `/api/review-queue/stats/{gameId}` | 获取队列统计 |

**审核状态**：`PENDING`、`ASSIGNED`、`CLAIMED`、`IN_REVIEW`、`COMPLETED`、`ESCALATED`、`CANCELLED`

**处置结论**：`confirmed_fraud`、`confirmed_benign`、`inconclusive`、`needs_investigation`

**队列类型**：`default`、`high_priority`、`fraud`、`cheating`

**类别**：`fraud`、`cheating`、`abuse`、`tos_violation`、`suspicious`

### 报表

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/reports` | 创建自定义报表 |
| GET | `/api/reports/{reportId}` | 获取报表详情 |
| GET | `/api/reports/game/{gameId}` | 列出某游戏的报表 |
| GET | `/api/reports/published/{gameId}` | 列出已发布的报表 |
| POST | `/api/reports/{reportId}/publish` | 发布报表 |
| POST | `/api/reports/{reportId}/execute` | 执行报表 |
| GET | `/api/reports/{reportId}/executions` | 获取报表执行历史 |
| GET | `/api/reports/{reportId}/stats` | 获取报表统计 |
| GET | `/api/reports/overview/{gameId}` | 获取游戏报表概览 |
| GET | `/api/reports/search/{gameId}?query=` | 搜索报表 |
| GET | `/api/reports/popular/{gameId}` | 获取热门报表 |
| GET | `/api/reports/recent/{gameId}` | 获取最近运行的报表 |

**报表类型**：`CUSTOM`、`TEMPLATE`、`SYSTEM`、`SCHEDULED`、`ADHOC`

**报表状态**：`DRAFT`、`PUBLISHED`、`SCHEDULED`、`ARCHIVED`、`DELETED`

**执行状态**：`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED`、`TIMEOUT`

**图表类型**：`line`、`bar`、`pie`、`table`、`heatmap`

**报表类别**：`analytics`、`retention`、`funnel`、`revenue`、`risk`

### Cohort

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/cohorts` | 创建 cohort |
| GET | `/api/cohorts/{cohortId}` | 获取 cohort 详情 |
| GET | `/api/cohorts/game/{gameId}` | 列出某游戏的 cohort |
| GET | `/api/cohorts/completed/{gameId}` | 列出已完成的 cohort |
| POST | `/api/cohorts/{cohortId}/calculate` | 计算 cohort 分析 |
| GET | `/api/cohorts/{cohortId}/results` | 获取 cohort 结果 |
| GET | `/api/cohorts/stats/{gameId}` | 获取 cohort 统计 |
| GET | `/api/cohorts/search/{gameId}?query=` | 搜索 cohort |
| GET | `/api/cohorts/recent/{gameId}` | 获取最近的 cohort |

**Cohort 类型**：`ACQUISITION`、`BEHAVIORAL`、`CUSTOM`、`SEGMENTED`

**Cohort 状态**：`PENDING`、`CALCULATING`、`COMPLETED`、`FAILED`、`ARCHIVED`

**分析类型**：`retention`、`engagement`、`revenue`、`churn`

**时间单位**：`day`、`week`、`month`

### 数据导出

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/exports` | 创建导出作业 |
| GET | `/api/exports/{exportJobId}` | 获取导出作业详情 |
| GET | `/api/exports/user/{userId}` | 列出某用户的导出作业 |
| GET | `/api/exports/game/{gameId}` | 列出某游戏的导出作业 |
| POST | `/api/exports/{exportJobId}/process` | 处理导出作业 |
| POST | `/api/exports/{exportJobId}/cancel` | 取消导出作业 |
| GET | `/api/exports/stats/{gameId}` | 获取导出统计 |
| GET | `/api/exports/user-stats/{userId}` | 获取用户导出统计 |

**导出类型**：`events`、`users`、`sessions`、`reports`、`risk_cases`

**导出格式**：`csv`、`excel`、`json`、`parquet`

**导出状态**：`PENDING`、`PROCESSING`、`COMPLETED`、`FAILED`、`CANCELLED`、`EXPIRED`

**压缩类型**：`none`、`gzip`、`zip`

### 集成

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/integrations` | 创建集成 |
| GET | `/api/integrations/{integrationId}` | 获取集成详情 |
| GET | `/api/integrations/game/{gameId}` | 列出某游戏的集成 |
| GET | `/api/integrations/game/{gameId}/type/{type}` | 按类型列出集成 |
| PUT | `/api/integrations/{integrationId}` | 更新集成 |
| POST | `/api/integrations/{integrationId}/verify` | 验证集成连接 |
| POST | `/api/integrations/{integrationId}/enable` | 启用集成 |
| POST | `/api/integrations/{integrationId}/disable` | 停用集成 |
| DELETE | `/api/integrations/{integrationId}` | 删除集成 |
| GET | `/api/integrations/{integrationId}/logs` | 获取集成调用日志 |
| GET | `/api/integrations/stats/{gameId}` | 获取集成统计 |
| GET | `/api/integrations/{integrationId}/call-stats` | 获取集成调用统计 |
| POST | `/api/integrations/{integrationId}/trigger` | 触发一次集成调用 |
| POST | `/api/integrations/game/{gameId}/trigger-batch` | 触发多个集成 |

**集成类型**：`SLACK`、`DISCORD`、`EMAIL_SMTP`、`EMAIL_SES`、`EMAIL_SENDGRID`、`PAYMENT_STRIPE`、`PAYMENT_PAYPAL`、`AUTH_OAUTH`、`AUTH_SAML`、`WEBHOOK`、`CUSTOM`、`ALERT_PAGERDUTY`、`ALERT_DATADOG`、`ANALYTICS_GA`

**认证类型**：`NONE`、`API_KEY`、`BEARER_TOKEN`、`BASIC_AUTH`、`OAUTH2`、`HMAC`、`MUTUAL_TLS`

**集成状态**：`ACTIVE`、`INACTIVE`、`VERIFYING`、`FAILED`、`DISABLED`

**事件类型**：`risk_case`、`block`、`alert`、`review`、`system`

### 速率限制与配额

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/rate-limits` | 创建速率限制规则 |
| GET | `/api/rate-limits/{ruleId}` | 获取速率限制详情 |
| GET | `/api/rate-limits/game/{gameId}` | 列出某游戏的速率限制 |
| PUT | `/api/rate-limits/{ruleId}` | 更新速率限制 |
| DELETE | `/api/rate-limits/{ruleId}` | 删除速率限制 |
| GET | `/api/rate-limits/stats/{gameId}` | 获取速率限制统计 |
| POST | `/api/rate-limits/quotas` | 创建配额 |
| GET | `/api/rate-limits/quotas/check` | 检查配额可用性 |
| POST | `/api/rate-limits/quotas/update-usage` | 更新配额用量 |
| GET | `/api/rate-limits/quotas/stats/{gameId}` | 获取配额统计 |

**速率限制作用域**：`GLOBAL`、`GAME`、`API_KEY`、`ENDPOINT`、`USER`

**限流算法**：`FIXED_WINDOW`、`SLIDING_WINDOW`、`TOKEN_BUCKET`、`LEAKY_BUCKET`

**窗口类型**：`SECOND`、`MINUTE`、`HOUR`、`DAY`、`WEEK`、`MONTH`

**资源类型**：`EVENTS_PER_DAY`、`EVENTS_PER_MONTH`、`STORAGE_GB`、`API_CALLS_PER_DAY`、`USERS`、`CONCURRENT_SESSIONS`、`REPORTS_PER_MONTH`、`EXPORTS_PER_MONTH`、`CUSTOM_REPORTS`、`INTEGRATIONS`、`RETENTION_DAYS`

### 健康监控

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/health/overview` | 获取系统健康概览 |
| GET | `/api/health/checks` | 列出所有健康检查 |
| GET | `/api/health/checks/{checkName}` | 获取健康检查详情 |
| POST | `/api/health/checks/{checkName}/run` | 执行健康检查 |
| GET | `/api/health/live` | 存活探针（公开） |
| GET | `/api/health/ready` | 就绪探针（公开） |
| GET | `/api/health/metrics/{type}` | 获取近期指标 |
| GET | `/api/health/alerts/active` | 获取活跃告警 |
| GET | `/api/health/alerts/stats` | 获取告警统计 |
| POST | `/api/health/alerts/{alertId}/acknowledge` | 确认告警 |
| POST | `/api/health/alerts/{alertId}/resolve` | 解决告警 |

**健康检查类型**：`DATABASE`、`STORAGE`、`CACHE`、`QUEUE`、`EXTERNAL_API`、`FLINK_CLUSTER`、`KAFKA`、`ELASTICSEARCH`、`SYSTEM_RESOURCE`

**健康状态**：`HEALTHY`、`DEGRADED`、`UNHEALTHY`、`DOWN`、`UNKNOWN`

**指标类型**：`CPU_USAGE`、`MEMORY_USAGE`、`DISK_USAGE`、`NETWORK_IN`、`NETWORK_OUT`、`DISK_IO_READ`、`DISK_IO_WRITE`、`ACTIVE_CONNECTIONS`、`QUEUE_DEPTH`、`THREAD_COUNT`、`GC_COUNT`、`GC_TIME`、`REQUEST_RATE`、`ERROR_RATE`、`LATENCY_P50`、`LATENCY_P95`、`LATENCY_P99`

**告警类型**：`SYSTEM_DOWN`、`HIGH_CPU`、`HIGH_MEMORY`、`HIGH_DISK`、`SLOW_RESPONSE`、`HIGH_ERROR_RATE`、`QUEUE_BUILDUP`、`CONNECTION_LEAK`、`DATABASE_ISSUE`、`STORAGE_ISSUE`、`EXTERNAL_SERVICE`、`SECURITY_EVENT`、`QUOTA_EXCEEDED`、`ANOMALY_DETECTED`

**告警级别**：`INFO`、`WARNING`、`ERROR`、`CRITICAL`、`EMERGENCY`

**告警状态**：`OPEN`、`ACKNOWLEDGED`、`INVESTIGATING`、`RESOLVED`、`CLOSED`、`SNOOZED`

### 系统管理

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/system/maintenances` | 创建维护窗口 |
| GET | `/api/system/maintenances/active` | 获取活跃的维护窗口 |
| GET | `/api/system/maintenances/upcoming` | 获取即将开始的维护窗口 |
| POST | `/api/system/maintenances/{windowId}/start` | 开始维护窗口 |
| POST | `/api/system/maintenances/{windowId}/complete` | 完成维护窗口 |
| POST | `/api/system/maintenances/{windowId}/cancel` | 取消维护窗口 |
| GET | `/api/system/maintenances/check/{gameId}` | 检查游戏是否处于维护中 |
| GET | `/api/system/configs` | 获取全部系统配置 |
| GET | `/api/system/configs/public` | 获取公开配置（无需认证） |
| GET | `/api/system/configs/{configKey}` | 获取配置值 |
| PUT | `/api/system/configs/{configKey}` | 设置配置值 |
| GET | `/api/system/features` | 获取全部 Feature Flag |
| GET | `/api/system/features/enabled` | 获取已启用的 Feature Flag |
| POST | `/api/system/features` | 创建 Feature Flag（初始状态恒为 DISABLED；`featureflag:manage`） |
| PUT | `/api/system/features/{flagKey}` | 全字段更新（仅更新所提供字段；不含 status/percentage——请使用 enable/disable/percentage） |
| GET | `/api/system/features/{flagKey}/check` | 检查功能是否已启用 |
| POST | `/api/system/features/{flagKey}/enable` | 启用功能 |
| POST | `/api/system/features/{flagKey}/disable` | 停用功能 |
| POST | `/api/system/features/{flagKey}/percentage` | 设置功能灰度比例 |
| GET | `/api/system/status` | 获取系统状态 |

**维护类型**：`SCHEDULED`、`EMERGENCY`、`ROLLING`、`PATCHING`

**维护状态**：`SCHEDULED`、`PENDING`、`IN_PROGRESS`、`PAUSED`、`COMPLETED`、`CANCELLED`、`EXTENDED`

**影响范围**：`GLOBAL`、`GAME`、`ENVIRONMENT`、`SERVICE`、`REGION`

**配置类型**：`SYSTEM`、`SECURITY`、`PERFORMANCE`、`INTEGRATION`、`NOTIFICATION`、`RETENTION`、`LIMIT`、`FEATURE`、`CUSTOM`

**Feature Flag 类型**：`BOOLEAN`、`PERCENTAGE`、`WHITELIST`、`BLACKLIST`、`CONDITIONAL`

**Feature Flag 状态**：`ENABLED`、`DISABLED`、`CONDITIONAL`、`STAGED_ROLLOUT`

### 高级安全

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/security/mfa/enable` | 为用户启用 MFA |
| POST | `/api/security/mfa/verify` | 校验并激活 MFA |
| POST | `/api/security/mfa/disable` | 停用 MFA |
| POST | `/api/security/mfa/validate` | 校验 MFA 码 |
| GET | `/api/security/mfa/user/{userId}` | 获取用户 MFA 配置 |
| GET | `/api/security/mfa/user/{userId}/enabled` | 检查用户是否已启用 MFA |
| POST | `/api/security/sso/configs` | 创建 SSO 配置 |
| GET | `/api/security/sso/configs/active` | 获取活跃的 SSO 配置 |
| POST | `/api/security/sso/configs/{configId}/activate` | 激活 SSO 配置 |
| POST | `/api/security/sso/callback` | SSO 登录回调 |
| POST | `/api/security/sessions/create` | 创建安全会话 |
| GET | `/api/security/sessions/validate` | 校验会话 |
| POST | `/api/security/sessions/{sessionId}/terminate` | 终止会话 |
| POST | `/api/security/sessions/user/{userId}/terminate-all` | 终止用户全部会话 |
| GET | `/api/security/policies/password` | 获取密码策略 |
| GET | `/api/security/policies/session` | 获取会话策略 |
| GET | `/api/security/policies/mfa` | 获取 MFA 策略 |
| GET | `/api/security/policies/mfa/required` | 检查是否强制要求 MFA |

**MFA 方式**：`TOTP`、`SMS`、`EMAIL`、`HARDWARE_TOKEN`、`BIOMETRIC`、`PUSH`、`BACKUP_CODE`

**MFA 状态**：`DISABLED`、`ENABLED`、`PENDING`、`LOCKED`、`RECOVERY_MODE`

**SSO 协议**：`SAML2`、`OAUTH2`、`OIDC`、`CAS`

**SSO 状态**：`DISABLED`、`ACTIVE`、`TESTING`、`ERROR`

**会话状态**：`ACTIVE`、`EXPIRED`、`REVOKED`、`TERMINATED`

**认证方式**：`PASSWORD`、`MFA`、`SSO`、`API_KEY`、`OAUTH`

**策略类型**：`PASSWORD`、`SESSION`、`MFA`、`ACCESS`、`API`

### 数据管道

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/pipelines` | 创建管道 |
| GET | `/api/pipelines/{pipelineId}` | 获取管道详情 |
| GET | `/api/pipelines/game/{gameId}` | 列出某游戏的管道 |
| GET | `/api/pipelines/{pipelineId}/jobs` | 获取管道作业 |
| GET | `/api/pipelines/{pipelineId}/stats` | 获取管道统计 |
| POST | `/api/pipelines/{pipelineId}/execute` | 执行管道 |
| POST | `/api/pipelines/{pipelineId}/activate` | 激活管道 |
| POST | `/api/pipelines/{pipelineId}/pause` | 暂停管道 |
| POST | `/api/pipelines/{pipelineId}/stop` | 停止管道 |
| POST | `/api/pipelines/quality-rules` | 创建质量规则 |
| GET | `/api/pipelines/quality-rules/game/{gameId}` | 列出某游戏的质量规则 |
| GET | `/api/pipelines/{pipelineId}/quality-rules` | 获取管道质量规则 |

**管道类型**：`BATCH`、`STREAMING`、`HYBRID`、`REALTIME`、`ETL`

**管道状态**：`DRAFT`、`ACTIVE`、`PAUSED`、`STOPPED`、`FAILED`、`ARCHIVED`

**作业状态**：`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED`、`TIMEOUT`、`RETRYING`

**质量规则类型**：`SCHEMA`、`COMPLETENESS`、`UNIQUENESS`、`ACCURACY`、`CONSISTENCY`、`TIMELINESS`、`VALIDITY`、`RANGE`、`PATTERN`、`REFERENCE`

**质量级别**：`INFO`、`WARNING`、`ERROR`、`CRITICAL`

### 维度同步

维度同步状态、HTTP Pull 来源配置，以及 Control 托管的 Agent 部署属性。Pull 行把游戏 API 的分页数据转换为 `dimension_define` 事件并推送到 `/v1/batch`；进度记录在 `dimension_sync_status`（`sourceType: agent|pull`）。完整链路设计参见 [dimension-sync.md](/zh/reference/dimension-sync)。

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/dimensions/sync-status?gameId=&environment=` | 按来源的同步状态（游标/checkpoint、最近推送时间、已推送/错误计数、最近一次错误），覆盖 Agent 与 Pull 两类来源 |
| GET | `/api/games/{gameId}/dimension-pulls` | 列出某游戏的 Pull 配置（`dimension:read`）；凭证永不回显 |
| POST | `/api/games/{gameId}/dimension-pulls` | 创建 Pull 配置：`{environment, sourceKey, dimType?, endpoint, credential, intervalSeconds?, pageLimit?, enabled?}`（`dimension:manage`）；Bearer 凭证以 AES-GCM 加密存储 |
| PUT | `/api/games/{gameId}/dimension-pulls/{pullId}` | 更新配置；省略 `credential` 则保留现有凭证（`dimension:manage`） |
| DELETE | `/api/games/{gameId}/dimension-pulls/{pullId}` | 删除配置（`dimension:manage`） |
| POST | `/api/games/{gameId}/dimension-pulls/{pullId}/run` | 手动触发一轮拉取；返回 `{success, pushed, cursor, error}`（`dimension:manage`） |
| GET | `/api/games/{gameId}/dimension-pulls/export` | 以 YAML 导出全部 Pull 配置（`application/x-yaml`，`dimension:read`）；凭证永不导出 |
| POST | `/api/games/{gameId}/dimension-pulls/import` | 导入 YAML 配置文本：按 `(environment, sourceKey)` 定位，逐条创建或更新，缺省 `credential` 保留现有值，任一失败则整批回滚；返回 `{created, updated, total}`（`dimension:manage`） |
| GET | `/api/games/{gameId}/agent-configs` | 列出 Agent 部署配置（每个 `(game, environment, agentKey)` 一份）；非敏感 props 文本可见，敏感 key 只回显名称（`dimension:read`） |
| POST | `/api/games/{gameId}/agent-configs` | 创建 Agent 配置：`{environment, agentKey, props, secrets, enabled?}`——`props` 是带 key 白名单的 properties 格式文本，`secrets`（AES-GCM 托管）必须包含 `gateway.api-key`（`dimension:manage`） |
| PUT | `/api/games/{gameId}/agent-configs/{id}` | 更新：`props` 整体替换；省略 `secrets` 保留现有值，显式传入 map 则轮换（`dimension:manage`） |
| DELETE | `/api/games/{gameId}/agent-configs/{id}` | 删除 Agent 配置（`dimension:manage`） |
| GET | `/api/dimensions/agent-configs/resolved?gameId=&environment=&agentKey=` | Agent 配置拉取：响应体是一段可 `Properties.load` 的文本，包含注入的 `game.id`/`game.environment`/`status.source-key`、props 以及解密后的 secrets；已停用的配置会被拒绝（`dimension:read`） |

导入/导出的 YAML 形态：以 `sourceKey` 为键的 `dimensions` 根映射；允许的字段为 `environment`、`dimType`、`endpoint`、`credential`、`intervalSeconds`、`pageLimit`、`enabled`——未知字段会被拒绝并给出精确错误。Agent 配置键复用 `agent.properties` 的点号键名并按可见性拆分：非敏感键放入 `props`，六个敏感键（`gateway.api-key`、`status.token`、数据库/Kafka/keystore 密码）放入 `secrets`。示例参见 [dimension-sync.md](/zh/reference/dimension-sync) 中的配置驱动一节。

### 机器学习模型

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/ml-models` | 创建 ML 模型 |
| GET | `/api/ml-models/{modelId}` | 获取模型详情 |
| GET | `/api/ml-models/game/{gameId}` | 列出某游戏的模型 |
| GET | `/api/ml-models/deployed` | 列出已部署的模型 |
| PUT | `/api/ml-models/{modelId}` | 更新模型配置 |
| POST | `/api/ml-models/{modelId}/archive` | 归档模型 |
| DELETE | `/api/ml-models/{modelId}` | 删除模型 |
| POST | `/api/ml-models/{modelId}/training` | 创建训练作业 |
| POST | `/api/ml-models/training/{trainingId}/start` | 启动训练 |
| PUT | `/api/ml-models/training/{trainingId}/progress` | 更新训练进度 |
| POST | `/api/ml-models/training/{trainingId}/complete` | 完成训练 |
| POST | `/api/ml-models/training/{trainingId}/fail` | 标记训练失败 |
| POST | `/api/ml-models/training/{trainingId}/cancel` | 取消训练 |
| GET | `/api/ml-models/training/{trainingId}` | 获取训练作业详情 |
| GET | `/api/ml-models/{modelId}/training` | 获取训练历史 |
| POST | `/api/ml-models/{modelId}/deploy` | 部署模型 |
| POST | `/api/ml-models/{modelId}/ab-test` | 配置 A/B 测试 |
| DELETE | `/api/ml-models/{modelId}/ab-test` | 停止 A/B 测试 |
| POST | `/api/ml-models/predictions` | 记录一次预测 |
| POST | `/api/ml-models/predictions/{predictionId}/complete` | 完成一次预测 |
| POST | `/api/ml-models/predictions/{predictionId}/fail` | 标记预测失败 |
| POST | `/api/ml-models/predictions/{predictionId}/feedback` | 添加预测反馈 |
| GET | `/api/ml-models/predictions/{predictionId}` | 获取预测详情 |
| GET | `/api/ml-models/{modelId}/predictions` | 获取预测历史 |
| GET | `/api/ml-models/{modelId}/predictions/range` | 按时间范围获取预测 |
| GET | `/api/ml-models/{modelId}/stats` | 获取模型统计 |
| GET | `/api/ml-models/stats/global` | 获取全局 ML 统计 |
| GET | `/api/ml-models/{modelId}/drift` | 检测模型漂移 |

**模型类型**：`CLASSIFICATION`、`REGRESSION`、`CLUSTERING`、`ANOMALY_DETECTION`、`RECOMMENDATION`、`TIME_SERIES`、`NLP`、`CUSTOM`

**模型状态**：`DRAFT`、`TRAINING`、`EVALUATING`、`DEPLOYED`、`STAGING`、`ARCHIVED`、`FAILED`

**训练状态**：`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED`、`TIMEOUT`

**预测状态**：`PENDING`、`PROCESSING`、`COMPLETED`、`FAILED`、`TIMEOUT`、`CACHED`

**反馈类型**：`CORRECT`、`INCORRECT`、`PARTIAL`、`UNKNOWN`

**模型框架**：模型记录上的自由文本 `VARCHAR(50)`——不做枚举校验；常见取值包括 TensorFlow、PyTorch、scikit-learn、XGBoost、LightGBM、ONNX 及自定义名称

### 开发者工具

| 方法 | 端点 | 说明 |
|---|---|---|
| POST | `/api/developer/sdk-keys` | 创建 SDK key |
| GET | `/api/developer/sdk-keys/{keyId}` | 获取 SDK key 详情 |
| GET | `/api/developer/sdk-keys/game/{gameId}` | 列出某游戏的 SDK key |
| PUT | `/api/developer/sdk-keys/{keyId}` | 更新 SDK key 配置 |
| POST | `/api/developer/sdk-keys/{keyId}/suspend` | 暂停 SDK key |
| POST | `/api/developer/sdk-keys/{keyId}/activate` | 激活 SDK key |
| POST | `/api/developer/sdk-keys/{keyId}/revoke` | 吊销 SDK key |
| DELETE | `/api/developer/sdk-keys/{keyId}` | 删除 SDK key |
| GET | `/api/developer/sdk-keys/{publicKey}/validate` | 校验 SDK key |
| POST | `/api/developer/sdk-versions` | 创建 SDK 版本 |
| GET | `/api/developer/sdk-versions/{versionId}` | 获取 SDK 版本详情 |
| GET | `/api/developer/sdk-versions/platform/{platform}` | 列出某平台的版本 |
| GET | `/api/developer/sdk-versions/platform/{platform}/latest` | 获取某平台的最新版本 |
| POST | `/api/developer/sdk-versions/{versionId}/release` | 发布版本 |
| POST | `/api/developer/sdk-versions/{versionId}/deprecate` | 弃用版本 |
| POST | `/api/developer/sdk-versions/{versionId}/retire` | 退役版本 |
| POST | `/api/developer/sdk-versions/{versionId}/download` | 记录一次下载 |
| POST | `/api/developer/telemetry-configs` | 创建遥测配置 |
| GET | `/api/developer/telemetry-configs/{configId}` | 获取遥测配置详情 |
| GET | `/api/developer/telemetry-configs/game/{gameId}` | 列出某游戏的遥测配置 |
| GET | `/api/developer/telemetry-configs/effective` | 获取生效的遥测配置 |
| PUT | `/api/developer/telemetry-configs/{configId}` | 更新遥测配置 |
| POST | `/api/developer/telemetry-configs/{configId}/activate` | 激活遥测配置 |
| POST | `/api/developer/telemetry-configs/{configId}/deactivate` | 停用遥测配置 |
| POST | `/api/developer/telemetry-configs/{configId}/archive` | 归档遥测配置 |
| DELETE | `/api/developer/telemetry-configs/{configId}` | 删除遥测配置 |
| GET | `/api/developer/stats` | 获取 SDK 统计 |

**SDK 平台**：`WEB`、`ANDROID`、`IOS`、`UNITY`、`UNREAL`、`REACT_NATIVE`、`FLUTTER`、`Cocos2d`、`SERVER`、`CUSTOM`

**SDK key 状态**：`ACTIVE`、`SUSPENDED`、`EXPIRED`、`REVOKED`

**投递模式**：`REALTIME`、`BATCH`、`HYBRID`

**版本状态**：`DRAFT`、`TESTING`、`BETA`、`RELEASED`、`DEPRECATED`、`RETIRED`

**变更类型**：`MAJOR`、`MINOR`、`PATCH`、`HOTFIX`

**配置类型**：`EVENT_DELIVERY`、`BATCH`、`COMPRESSION`、`ENCRYPTION`、`RETRY`、`OFFLINE`、`MONITORING`、`CUSTOM`

**配置状态**：`DRAFT`、`ACTIVE`、`INACTIVE`、`ARCHIVED`

### 用户与角色分配

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/users` | 列出用户 |
| POST | `/api/users` | 创建用户 |
| GET | `/api/users/{userId}` | 获取用户详情 |
| PUT | `/api/users/{userId}` | 更新用户 |
| GET | `/api/users/{userId}/role-assignments` | 列出某用户的角色分配 |
| POST | `/api/users/{userId}/role-assignments` | 分配角色（`roleId`，可选 `gameId`、`environment`） |
| DELETE | `/api/users/{userId}/role-assignments?roleId=&gameId=&environment=` | 撤销角色分配 |

`user_role_assignments` 是权限的唯一写入路径；`users.roles` 只是展示列。

作用域：`global`、`game`、`environment`（environment 作用域需要 gameId）。

内置角色（在 `V0.2.3` 中播种，`roles` 表是唯一事实来源）：`operator`（全局，拥有全部权限）、`game_admin`、`analyst`、`marketing`、`finance`、`developer`、`viewer`、`qa`。不存在 `owner` 或 `risk_admin` 角色。

### 审计日志

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/api/audit-logs?gameId=&environmentId=&actor=&action=&from=&to=` | 过滤搜索 |
| GET | `/api/audit-logs/user/{userId}` | 按操作者 |
| GET | `/api/audit-logs/resource/{resourceType}/{resourceId}` | 按资源 |
| GET | `/api/audit-logs/action/{action}` | 按操作 |
| GET | `/api/audit-logs/status/{status}` | 按结果状态 |
| GET | `/api/audit-logs/time-range?from=&to=` | 按时间范围 |
| GET | `/api/audit-logs/game/{gameId}` | 按游戏 |
| GET | `/api/audit-logs/failed` | 失败的操作 |
| GET | `/api/audit-logs/auth` | 登录/登出/登录失败记录 |
| GET | `/api/audit-logs/sensitive` | 敏感权限读取（需要 `audit:sensitive`） |
| GET | `/api/audit-logs/search?query=` | 关键词搜索 |
| GET | `/api/audit-logs/statistics` | 聚合统计 |
| GET | `/api/audit-logs/{logId}` | 单条记录 |
| DELETE | `/api/audit-logs/cleanup` | 清理旧记录 |

### 其他资源分组

以下资源均已实现，并通过同一认证模型暴露；各端点参数参见 `/v3/api-docs`：

| 分组 | 基础路径 | 覆盖内容 |
|---|---|---|
| 分析 | `/api/analytics` | 收入 / 广告 / 会话 / 性能 / 社交总览、ARPU、按平台、按渠道、趋势、崩溃 |
| 公告 | `/api/games/{gameId}/announcements` | 运营公告生命周期（草稿/排期/发布/下线） |
| 封禁名单 | `/api/block-lists` | 被封禁目标、解封、统计（见上文相应小节） |
| Cohort | `/api/cohorts` | Cohort 定义、计算、结果 |
| 崩溃指标 | `/api/crash-metrics` | 崩溃聚合与符号化（`/{gameId}/symbolicate`） |
| 仪表盘 | `/api/games/{gameId}/dashboards` | 自定义仪表盘布局与组件 |
| 数据质量与特征存储 | `/api/data-quality`、`/api/feature-store` | 数据质量序列 + 汇总（B10）；按游戏/环境的特征存储 |
| 维度同步 | `/api/dimensions` | 来自 dimension-sync agent 的 `/sync-status` 心跳 |
| 事件导出 | `/api/games/{gameId}/events-export` | 原始事件导出作业（每日 JSONL + manifest） |
| 漏斗 | `/api/funnels` | 漏斗定义与结果 |
| 身份 | `/api/identities` | 身份查询（按设备/玩家/用户/标识符）+ 显式合并（`POST /{gameId}/merge`，`privacy:manage`；次要身份并入主身份、打墓碑标记、留审计） |
| 事件检查器 | `/api/inspector`（control）与 gateway 的 `/v1/inspector/recent` | 用于调试的近期原始事件 |
| 财务指标 | `/api/finance-metrics/{gameId}` | 财务报表（`/report`）与导出（`/export`） |
| 集成 | `/api/integrations` | Slack/Discord/邮件/支付/webhook 集成 |
| LTV 指标 | `/api/ltv-metrics` | LTV 预测查询 |
| 邮件 | `/api/games/{gameId}/mails` | 带附件与领取回执的玩家邮件 |
| 告警邮件通道 | `/api/games/{gameId}/alert-email-config`（+`/test`） | 按游戏的告警邮件收件人；尽力投递（未配置 SMTP 时跳过） |
| 指标告警 | `/api/games/{gameId}/alert-rules`、`/api/games/{gameId}/alerts` | 阈值告警规则与告警实例 |
| ML 工件 | `/api/ml-artifacts` | 带版本的模型工件注册表（register/list/active） |
| 在线指标 | `/api/online-metrics` | 实时在线玩家数 |
| 支付漏斗 | `/api/payment-metrics` | 支付漏斗指标 |
| 玩家数据 | `/api/player-data/{playerId}/...` | 跨游戏档案、支付、登录日志（按权限过滤） |
| 玩家删除 | `/api/privacy/erasure-requests` | GDPR 式删除请求生命周期 |
| 玩家导出 | `/api/player-exports` | 玩家数据导出作业 |
| 预测指标 | `/api/prediction-metrics` | churn / risk-score / pltv / propensity 批量评分 + 刷新 |
| 速率限制 | `/api/rate-limits` | 按游戏的限流与配额规则 |
| 兑换码 | `/api/games/{gameId}/redeem-batches` | 兑换码批次、兑换、历史 |
| 远程配置 | `/api/games/{gameId}/remote-configs` | 键值对远程配置版本 |
| 保留期 | `/api/retention/enforcements` | 保留期强制执行记录 |
| 留存指标 | `/api/retention-metrics` | N 日 / 滚动留存查询 |
| 风控指标 | `/api/risk-metrics` | 趋势 / 规则命中 / 严重度 / 处置分布 |
| 角色 | `/api/roles` | 角色 CRUD、权限绑定（roles 表是事实来源） |
| Schema | `/api/games/{gameId}/schemas` | 事件 schema CRUD（B7）：create/list/active/get/update/publish/compatibility/deactivate/delete + 事件定义与属性 CRUD |
| 分群 | `/api/games/{gameId}/segments` | 用户分群定义、计算、成员 |
| 符号映射 | `/api/symbols` | 崩溃符号化正则规则 |
| 系统 | `/api/system` | 维护窗口、系统配置、Feature Flag（见上文相应小节） |
| 埋点方案 | `/api/games/{gameId}/tracking-plans` | 见上文相应小节 |
| 用户 | `/api/users` | 见上文相应小节 |
| Webhook | `/api/webhooks` | 见上文相应小节 |

## 响应格式

```json
{
  "code": 200,
  "message": "Success",
  "data": {},
  "timestamp": "2026-06-18 12:00:00",
  "traceId": "abc123"
}
```

## 错误码

| 状态码 | 说明 |
|---|---|
| 400 | 错误请求 |
| 401 | 未认证 |
| 403 | 权限不足 |
| 404 | 资源不存在 |
| 409 | 冲突 |
| 429 | 已限流 |
| 500 | 内部错误 |
