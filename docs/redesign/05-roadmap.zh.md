# 05 - 实施路线（P0 → P4）

按优先级分阶段落地，每个阶段独立可发布。

## 阶段总览

| 阶段 | 周期 | 目标 | 交付物 |
|---|---|---|---|
| **P0** | 2 周 | 安全/阻塞修复 | 修复 3 个 P0 问题 |
| **P1** | 4-6 周 | 游戏事件化 | 7 类事件 + Identity Merge + 双留存 + N 步漏斗 |
| **P2** | 4-6 周 | 实时与商业 | LTV + 关卡分析 + 虚拟经济 + 广告 + 异常检测 |
| **P3** | 8 周 | 智能化 | A/B 完整 + 流失预测 + Crash 系统 |
| **P4** | 8 周 | 生态化 | 反向 ETL + Remote Config + LiveOps + 插件 |

---

## P0 立即修复（2 周）

### P0.1 修复 Android SDK HMAC 安全漏洞

**任务**：
- 移除 `sdks/android/pit-android/src/main/java/io/pit/android/Pit.kt` 中 `hmacSecret` 字段
- 移除 `send()` 中 HMAC 签名逻辑（第 153-159 行）
- 注释清楚：HMAC 仅 Server SDK 使用
- 增加 lint 警告：禁止在客户端代码 import `HmacSHA256`

**验收**：
- Android SDK 不再持任何 secret
- 客户端到服务端请求仅含 `x-api-key`
- 单元测试覆盖：禁止 hmac 选项

**风险**：极低，纯移除逻辑

### P0.2 统一多租户语义模型

**任务**：
1. 删除 `ProjectEntity.java` 和 `ProjectRepo.java`
2. 在 `ApiKeyEntity` 中删除 `projectId` 字段及其向后兼容方法
3. 新增字段到事件 schema：
   - `tenant_id` = organization_id
   - `app_id` = `${game_id}_${environment}`
4. ClickHouse 表迁移：
   - 重命名 `project_id` 列为 `tenant_id` + 新增 `app_id`
   - 调整 `PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))`
5. Flink 作业改造：
   - `EventsEnrichJob` / `SessionsJob` / `RetentionJob` / `FunnelsJob` 全部按 `(tenant_id, app_id)` 分组
6. `ApiController` 接通 `Organization/Game/Environment` 模型，废弃 Project

**验收**：
- ClickHouse 表分区键改造完成
- 数据迁移脚本（旧 project_id → tenant_id+app_id 映射）
- Flink 作业 4 个全部改造
- e2e 测试通过

**风险**：中，需要数据迁移，建议先 dev 环境验证

### P0.3 启用 disabled 控制器，接通 DB

**任务**：
1. 逐一启用 8 个 `.disabled` 控制器
2. 验证 JPA 实体与数据库 schema 一致
3. 补充缺失的 Service 层实现
4. Spring Security + JWT 配置（`SecurityConfig.java.disabled` + `JwtUtil.java.disabled`）
5. 集成测试覆盖核心 API

**验收**：
- 所有 `/api/organizations/*`、`/api/games/*`、`/api/users/*`、`/api/api-keys/*` API 可用
- 登录/注册/角色分配/权限校验完整
- OpenAPI 文档与代码一致

**风险**：中，控制器代码量大，需逐个验证

### P0.4 PII 治理代码化

**任务**：
1. 启用 `services/gateway-service/.../api/BatchController.java.disabled`
2. 落地 `denyKeys` / `maskKeys` / `piiEmail` / `piiPhone` / `piiIp` 处理逻辑
3. 单元测试覆盖：邮件掩码、电话掩码、IP 粗化、拒绝字段
4. DLQ 流转：违规事件入 `pit.deadletter` 主题

**验收**：
- 配置 `denyKeys=["ssn"]`，事件含该字段时被拒绝入 DLQ
- 配置 `piiEmail=mask`，事件中 email 字段被 MD5 截断
- 单元测试通过

**风险**：低，仅 Gateway 代码

---

## P1 游戏事件化（4-6 周）

### P1.1 事件 Schema 扩展（7 类事件）

**任务**：
1. 更新 `schema/avro/pit-event.avsc` 到 v2（详见 [04-redesign.zh.md](./04-redesign.zh.md)）
2. 更新 `schema/json/pit-event-schema.json` 同步
3. ClickHouse 表重建（先建新表 `events_v2`，迁移后改名）
4. SDK 4 套（Web/Android/iOS/Unity）支持新字段
5. 提供 SDK 类型化 API：`pit.session()/business()/resource()/progression()/design()/error()/ad()`
6. Tracking Plan YAML + CI 校验

**验收**：
- 7 类事件全部能上报
- Tracking Plan 校验在 CI 阶段拦截不合规事件名

### P1.2 Identity Merge（用户身份合并）

**任务**：
1. 新增 `identities` 表（详见 [04-redesign.zh.md](./04-redesign.zh.md)）
2. Flink enrich 作业维护 `device_id → user_id` 映射（状态机）
3. 新增 SDK API：`pit.identify(userId)` / `pit.alias(deviceId, userId)`
4. 查询层走 `user_id` 而非 `device_id`，自动合并设备

**验收**：
- 游客→注册→第三方登录场景下，所有事件归属同一 user_id
- LTV/留存指标准确

### P1.3 双口径留存（N-Day + Rolling）

**任务**：
1. 重写 `retention-job` 支持任意 N 天
2. 新增 Rolling Retention 计算逻辑
3. 新增分层留存（付费/等级/渠道/行为）
4. ClickHouse 表 `retention` 支持 `retention_type` 字段
5. Superset 看板新增 D1/D3/D7/D14/D30/D60/D90 留存曲线

**验收**：
- 任意 N 天留存可计算
- Rolling 与 N-Day 数据双口径并存

### P1.4 N 步可配置漏斗

**任务**：
1. 新增 `funnel_configs` 表（Control Service 管理）
2. 重写 `funnels-job` 支持任意步数 + 有序/无序 + 时间窗
3. SDK API：`pit.funnel(funnel_id, step, props)`
4. Superset 看板新增漏斗可视化

**验收**：
- 配置 6 步新手引导漏斗，可视化各步转化率
- 支持按用户分群（付费/等级/渠道）切片

### P1.5 Schema 治理落地

**任务**：
1. Tracking Plan YAML schema 定义
2. SDK 代码生成器：YAML → TypeScript / Kotlin / Swift 类型
3. CI 阶段校验：SDK 调用 vs Tracking Plan 一致
4. Apicurio Schema Registry 版本兼容性检查

**验收**：
- Tracking Plan 变更自动触发 SDK 类型重新生成
- 不合规 SDK 调用 CI 阶段失败

---

## P2 实时与商业（4-6 周）

### P2.1 实时大屏（5 分钟滚动）

**任务**：
1. Flink 作业：事件流 → `realtime_5min` 表
2. Redis 实时计数器加速（DAU/Session/Revenue/Events）
3. 自研大屏前端（React + ECharts + WebSocket）
4. 替代/补充 Superset 看板

**验收**：
- 大屏延迟 < 5 秒
- 今日 DAU/Revenue 实时刷新

### P2.2 LTV + pLTV

**任务**：
1. Cohort LTV 计算（ARPDAU × Lifetime）
2. 留存曲线拟合（幂律 / 指数）
3. Python 服务做 pLTV 预测（scikit-learn + TensorFlow）
4. Superset 看板新增 LTV 趋势

**验收**：
- D7/D30/D90 LTV 可查
- pLTV 预测误差 < 15%

### P2.3 关卡分析

**任务**：
1. `progression_attempts` 表落地
2. Flink 作业聚合：通过率、首次通过率、平均尝试次数
3. 难度系数算法：`难度 = 1 - 通过率`
4. Superset 看板新增关卡分析

### P2.4 虚拟经济

**任务**：
1. `resource_flows` 表落地
2. Flink 作业计算 source/sink 净流
3. 玩家货币分布（P50/P90/P99）
4. 通胀监控看板

### P2.5 广告分析

**任务**：
1. 事件 schema 新增 `ad` 类型
2. Flink 作业聚合：impression/click/reward/eCPM/ARPU(Ad)
3. Superset 看板新增广告分析

### P2.6 异常检测（CEP）

**任务**：
1. Flink CEP 作业：识别作弊行为（高速重复事件）
2. 异常付费模式检测（短时大额、多地登录）
3. 游戏平衡性监控（胜率偏置）
4. 告警通知（邮件/Webhook）

---

## P3 智能化（8 周）

### P3.1 A/B 完整平台

**任务**：
1. Control Service：实验 CRUD + 流量分桶 + 目标绑定 + 定向
2. CUPED 方差缩减
3. 显著性检验（t-test / z-test）
4. SRM 检查（样本比失衡）
5. 95% CI 计算
6. Superset 看板：实验效果对比

### P3.2 流失预测

**任务**：
1. Python 训练服务：特征工程（行为、社交、付费）
2. GBDT 模型（参考 LightGBM）
3. 实时评分：用户级流失风险
4. 高风险用户自动告警 → LiveOps 触达

### P3.3 Crash 报告系统

**任务**：
1. 事件 schema 新增 `error` 类型完整支持
2. SDK Crash 自动捕获（Android/iOS 原生 + Unity）
3. 服务端符号化（dSYM / Proguard mapping / Source map）
4. 按 stack hash 聚合 + 影响用户数 + 趋势

### P3.4 自定义 Dashboard

**任务**：
1. 拖拽式看板编辑器（参考 Metabase）
2. 自定义指标计算引擎
3. 定时报告（邮件 / Slack）
4. 移动端响应式

---

## P4 生态化（8 周）

### P4.1 反向 ETL / 数仓导出
- S3 / BigQuery / Snowflake 导出
- CDC 实时同步
- 第三方工具集成（dbt、Airflow）

### P4.2 Remote Config
- 服务端配置下发（Countly 风格）
- 按 cohort/实验分组下发不同配置
- SDK 端缓存 + 热更新

### P4.3 LiveOps 推送
- 用户分群 → 推送
- A/B 触达文案
- 触达效果回流分析

### P4.4 插件 SDK
- 插件接口规范
- 第三方插件市场（参考 Countly）
- 社区贡献机制

---

## 成功指标

### 技术指标
- 性能：采集延迟 < 100ms，查询响应 < 2s
- 可用性：SLA 99.9%+，故障恢复 < 5min
- 扩展性：支持千万级 DAU，万亿级事件
- 准确性：数据准确率 99.99%+

### 业务指标
- 用户采用：月活跃游戏公司 100+
- 数据规模：日处理事件 10 亿+
- 客户满意：NPS > 50，续费率 > 90%
- 生态健康：API 调用 1000 万+/月

---

## 风险评估

| 风险 | 应对 |
|---|---|
| 技术复杂度（AI/pLTV） | 分阶段实施，先 MVP 后优化 |
| 数据合规（GDPR/CCPA） | 内置多地区合规模板 |
| 大客户定制化 | 预留扩展点，模块化设计 |
| 数据迁移（schema 改造） | 双写过渡，灰度迁移 |
| 性能退化（字段增加） | 物化视图预聚合 + 冷热分层 |
