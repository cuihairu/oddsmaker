# Changelog

## v0.2.0 (unreleased)
- 兑换码系统（P4）：UNIQUE 一次性码批量生成（Crockford Base32 去混淆字符集）/ SHARED 通用码（指定或生成，总量限次）；防刷三层（每玩家限领计数、批次总量、有效期与状态）+ UNIQUE 条件更新原子核销 + (batch, player, seq) 唯一约束幂等兜底；兑换返回奖励快照凭据；游戏服 redeem/history API（跨游戏码统一按 invalid_code 处理防探测）；运营端批次 CRUD/停用/码导出 + Flyway V0.8.2 三表
- 运营邮件系统（P4）：全服（惰性展开）与个人（精确收件人匹配）邮件；附件 JSON 校验（type/id 必填）；领取凭据固化附件快照且幂等（唯一约束 + 并发兜底）；sweep 每 5 分钟将到期已发送邮件标记 EXPIRED；游戏服经 inbox/claim API 拉取与代领
- 公告系统（P4）：全生命周期管理——创建（草稿/定时）、立即发布、改期、手动下线、软删除；sweep 每分钟扫描驱动 SCHEDULED→PUBLISHED 与 autoOfflineAt 到点下线；游戏服经 `GET /api/announcements/active` 拉取展示窗口内公告（环境匹配或全环境）；状态机约束（OFFLINE 不可复活、PUBLISHED 不可直接删）；增删改与自动迁移全量审计
- 留存分析补齐 Rolling 口径：RetentionPolicy 抽取纯逻辑（可配 N-Day `retention.ndays`、Rolling `retention.rolling.ndays`）；Rolling 语义为第 N 天及以后任意一天活跃，最后活跃日跨越阈值时补记；新增 `retention_rolling` CH 表
- 漏斗补齐无序口径：FunnelType 新增 UNORDERED；STANDARD/UNORDERED 走任意顺序完成判定（UnorderedFunnelLogic：全步骤完成 + 时间跨度约束，超窗重置，每用户一次转化）；SEQUENTIAL/TIME_WINDOW 保持原顺序推进逻辑
- 事件类型推断补全九类：inferEventType 新增 user/resource/design 映射（session/user/business/resource/progression/design/error/ad/risk + experiment 附加）
- Identity Merge、商业化（IAP/广告/LTV 视图）、玩法分析（关卡进度/经济流转视图）经核对已具备，勾选完成
- 实验平台（A/B 测试）闭环：ExperimentSplitter 确定性分流（SHA-256(salt+subjectId) 分桶 + 权重分配，SDK/服务端算法一致）；服务端分流 API `GET /api/experiments/{id}/assign`（非 running 实验兜底 control）；指标快照接收 API（聚合管道按窗口幂等回填每变体 count/sum/sumSquares/successes）；结果 API 输出比例 z-test / Welch t-test 检验（lift、95% CI、p 值、显著性、小样本 low_power 提示），多变体以 control_variant 为基线两两对比
- Gateway 风控前置：新增 ReplayGuard（签名重放 401 replay_detected、event_id 幂等吸收计入 duplicates），事件时间戳信差 ±24h 检查（invalid_timestamp）；黑名单/签名时间窗/非法环境/body size 此前已具备
- Flink risk job 新增两类检测：DUPLICATE_RECEIPT（同 subject 同 receipt_hash/order_id 窗口内 ≥2 次，CRITICAL/REVIEW）、AD_REWARD（激励广告 reward 窗口超频，HIGH/ALERT）；RuleConfig 默认兜底同步扩展
- 风控 Webhook 闭环：BLOCK/REVIEW/MARK/THROTTLE 处置后统一通过 `risk_action` webhook 输出到游戏服；REVIEW 升级为创建 RiskCase 进入审核队列（CRITICAL 优先级 1）；新增 MARK 动作分发
- 公司内 RBAC 落地：六角色权限矩阵（owner/operator/analyst/developer/risk_admin/viewer），支持 global/game/environment 三级 scope 分配与精确回收；新增 `/api/users/{userId}/role-assignments` API，GRANT_ROLE/REVOKE_ROLE 全量审计
- 修复失效鉴权：RiskRuleController/ReportController 的 `@PreAuthorize(hasAuthority(...))` 无 authority 供给（实际永远拒绝），替换为 AccessGuard 显式 scope 检查；SecurityException 统一映射 403
- 审计日志补齐密钥与环境资源：API Key 创建/策略变更/删除、环境创建/更新/删除全量记录（含变更前后值）
- Tracking Plan 字段字典规格化：属性定义新增 `cardinalityLimit` 上限（防高基数字段打爆存储）；ENUM 类型强制要求非空、无重复的 allowedValues JSON 数组且 cardinalityLimit ≥ 候选数；ARRAY 强制声明 arrayElementType
- 属性定义补齐 update/delete 接口（draft 计划内可编辑，软删除），增删改全量审计
- 新增 TrackingPlanServiceTest 10 个用例覆盖字段字典校验矩阵
- 环境策略绑定下发执行：Control 内部接口透出 `envStatus/envEnableSampling/envSampleRate`；Gateway 对非 active 环境返回 503 `environment_unavailable`，环境级确定性采样按 device_id SHA-256 分桶（同设备事件同进同出，保漏斗/留存口径），响应新增 `sampled_out` 计数
- 采样分桶弃用 String.hashCode（规整前缀聚集严重），改用 SHA-256 摘要取桶
- Game API 补齐默认时区：`games.default_timezone`（IANA 标识，默认 UTC），DTO 校验 + Service 层 ZoneId 白名单校验
- Game 生命周期操作接入审计日志：创建/更新/删除/发布/下线全量记录（P1「审计日志」覆盖 Game 资源）
- GameServiceTest 从空壳补齐 8 个用例：默认值、时区校验、状态机、软删除级联、审计断言
- API Key 角色化：keyType 统一为 `client/server/admin`；server key 强制 HMAC，admin key 只读；创建接口支持 `keyRole` 参数
- 客户端 SDK 移除 HMAC：iOS 删除 HMACManager，Unity 移除签名代码；HMAC 仅保留给 Server SDK
- Gateway HmacFilter 加固：client key 携带签名头返回 401 `signature_not_supported`，杜绝无 secret 验签 NPE；补充 client/server key 行为测试
- RiskRule API：新增 `/api/risk-rules` CRUD + enable/disable，Specification 多条件分页查询，操作全量写入审计日志

## v0.1.0 (initial release)
- Unified Java stack for ingest + streaming + analytics
- Gateway (Spring Boot): /v1/batch NDJSON+gzip; auth/HMAC; per-key & per-IP rate limit; JSON Schema; props allowlist; PII policy (mask/drop/coarse IP); DLQ; unified errors; OTel
- Control service: H2 persistence; API+Web UI; projects & API keys; dynamic policies (ratelimit/allowlist/PII); admin token
- Streaming (Flink): enrich (validate/dedup/UA/GeoIP), sessions, retention (D0/D1/D7/D30), funnels (two-step)
- Storage (ClickHouse): events/sessions; MVs & views (events/dau/revenue/ua/os)
- BI: Superset importable bundle; E2E script to import; dashboards for events, DAU, retention, funnel, revenue, UA/OS
- SDKs: Web (TS), Android (Kotlin), Unity (C#), iOS (Swift)
- Observability: OTel Collector → Prom & Grafana; docker-compose for local
- Dev/CI: k6 load test, perf matrix/report, E2E script, Gradle CI for gateway tests & Flink assemble & Web SDK build
- Deploy: Dockerfiles, Helm chart, raw K8s manifests, deployment docs
