# Oddsmaker 项目功能完成度详细评估

## 项目概述
PIT 是一个事件驱动的游戏分析平台，包含：
- **事件采集网关** (Gateway Service) - 接收和验证事件
- **控制面板** (Control Service) - 管理项目/API密钥/实验
- **实时处理** (Flink Jobs) - 数据富化和分析
- **数据仓库** (ClickHouse) - 事件和分析结果存储
- **BI集成** (Superset) - 可视化仪表板
- **多平台SDK** - Web/Android/iOS/Unity

---

## 1. 多租户支持现状

### 1.1 Project_ID 支持程度 ✓ 85%

**已实现**:
- ✓ 事件模型核心支持 (Avro/JSON schema)
- ✓ Gateway服务完整的project_id验证
- ✓ ClickHouse分区设计 (project_id, toYYYYMM)
- ✓ Control API - 项目创建/编辑/删除
- ✓ 所有Flink作业按project_id处理
- ✓ API密钥绑定到项目
- ✓ 实验隔离（per project）

**缺失**:
- ✗ 项目间权限隔离未实现 (API密钥在Gateway中验证但无基于project的权限)
- ✗ 跨项目数据访问控制 (Superset/ClickHouse中)
- ✗ 项目级配额/限流管理
- ✗ 项目间数据导出防护

**建议**: 中优先级 - 当前单一客户/多项目场景可用，企业多客户场景需完善

---

### 1.2 用户权限管理现状 ✗ 15%

**已实现**:
- ✓ AdminToken认证 (x-admin-token header)
- ✓ API密钥生成和撤销
- ✓ 基本API密钥策略 (RPM限流、PII策略)

**缺失**:
- ✗ 无用户/账户系统
- ✗ 无RBAC (角色based权限控制)
- ✗ 无资源级权限 (谁可以修改某项目)
- ✗ Web UI认证 (HTML页面完全开放)
- ✗ 无审计日志
- ✗ 无会话管理
- ✗ OAuth/SSO支持

**完成度**: 15% | **优先级**: 高 (生产环保必需)
**推荐方案**: 引入Keycloak/Auth0或自建轻量认证系统

---

### 1.3 组织层级支持情况 ✗ 0%

**缺失**:
- 无组织(Organization)概念
- 无团队(Team)管理
- 无工作空间(Workspace)隔离
- 无成员邀请/权限继承

**完成度**: 0% | **优先级**: 低 (MVP后期功能)
**建议**: 后续需求时参考结构: Org > Project > ApiKey

---

## 2. 数据模型现状

### 2.1 现有的事件表结构 ✓ 90%

**核心事件表** (`events`):
```
Fields:
- project_id, event_date, ts_server, event_id, event_name
- user_id, device_id, session_id
- platform, app_version, country
- props_json (JSON), revenue_amount, revenue_currency
```

**完成度**: 90%

**细节**:
- ✓ Avro schema完整定义
- ✓ JSON schema验证
- ✓ 通用字段覆盖完整
- ✓ Revenue字段支持
- ✗ 缺少client_ip和user_agent持久化 (仅在Gateway处理，不入库)
- ✗ 缺少session表的完整性保证

**相关视图/表**:
- ✓ mv_events_by_day (每日事件聚合)
- ✓ mv_dau (日活用户)
- ✓ mv_revenue_by_day (收入聚合)
- ✓ mv_ua_os_by_day (浏览器/OS分布)
- ✓ sessions 表 (Flink输出)
- ✓ retention_daily (留存按cohort)
- ✓ funnels_2step (漏斗分析)

---

### 2.2 游戏专业字段支持情况 ✓ 60%

**现有游戏相关字段**:
- ✓ revenue_amount + revenue_currency (内购)
- ✓ props_json (自定义属性)
- ✓ platform/app_version (平台版本分布)
- ✓ country (地域)
- ✓ session_id (会话)
- ✓ Flink提取的device_class (从UA)

**缺失关键游戏字段**:
- ✗ level/stage (关卡进度)
- ✗ character_id (角色)
- ✗ battle_result (战斗胜负)
- ✗ dungeon_type (副本类型)
- ✗ loot_items (掉落物品)
- ✗ achievement_id (成就)
- ✗ premium_currency (高级货币)
- ✗ A/B测试分组字段 (仅在props中软实现)
- ✗ 无针对游戏的预定义事件类型

**完成度**: 60% | **优先级**: 中
**建议方案**:
- 扩展schema: 可通过props + 文档指导
- 预定义事件: 创建game_events.avsc (game_level_complete, game_purchase等)

---

### 2.3 需要扩展的字段

**推荐增强**:

1. **客户端诊断字段**:
   - network_type (wifi/4g/5g)
   - device_model (精确设备型号)
   - sdk_version (SDK版本)
   - error_code (如果有错误)

2. **游戏业务字段**:
   - game_level (关卡号)
   - game_mode (PvP/PvE等)
   - difficulty (难度)
   - play_time (游戏时长)
   - achievement_unlocked (成就解锁)

3. **统计学字段**:
   - experiment_group (实验分组，目前在props)
   - test_variant (测试变体)
   - sampling_rate (采样率)

---

## 3. 分析能力现状

### 3.1 Flink 作业分析能力 ✓ 75%

**已实现的作业**:

| 作业 | 功能 | 完成度 | 备注 |
|------|------|--------|------|
| events-enrich-job | 基础校验+去重+IP/UA解析 | 90% | 缺GeoIP可选 |
| sessions-job | 会话聚合(30min gap) | 85% | 会话去重算法可优化 |
| retention-job | Cohort留存分析 | 80% | 仅支持0/1/7/30d |
| funnels-job | 2步漏斗 | 70% | 限于2步，无可配置漏斗 |

**能力分析**:

✓ 已有:
- 基础的schema校验
- event_id去重(7天TTL)
- GeoIP + UA解析(可选)
- 会话识别(30分钟gap)
- 留存计算(固定间隔)
- 基础漏斗(2步)

✗ 缺失:
- 可配置漏斗长度(>2步)
- 流失分析(哪个步骤卡住)
- 时间序列异常检测
- 自定义事件聚合
- 实时指标(10s级)
- 成本优化(未采用RocksDB状态)
- 多维并行聚合
- 窗口大小可配

**完成度**: 75% | **优先级**: 中 (核心能力到位，需细化)

---

### 3.2 ClickHouse 聚合表和视图 ✓ 80%

**物化视图**(Materialized Views):
- ✓ mv_events_by_day (按日/事件聚合)
- ✓ mv_dau (日活)
- ✓ mv_revenue_by_day (收入)
- ✓ mv_ua_os_by_day (UA/OS维度)

**普通视图**(Views):
- ✓ v_events_trend (事件趋势)
- ✓ v_dau_trend (DAU趋势)
- ✓ v_revenue_by_day (收入视图)
- ✓ v_ua_by_day, v_os_by_day

**实验相关视图**:
- ✓ v_exp_exposures_by_day (曝光日聚合)
- ✓ v_exp_exposures_by_day_dim (曝光含维度)
- ✓ v_exp_conversion_same_day (同日转化)
- ✓ v_exp_conversion_24h, v_exp_conversion_7d (转化率)
- ✓ 含维度的转化视图 (_dim后缀)

**缺失**:
- ✗ 用户路径分析视图
- ✗ 成本 ROI 视图
- ✗ 均值/分位数聚合表 (p50/p95等)
- ✗ 按custom dimensions的预聚合
- ✗ 实时(5分钟级)视图

**完成度**: 80% | **优先级**: 低 (基础覆盖，优化后期)

---

### 3.3 Superset 中的图表和仪表板 ✓ 70%

**已实现仪表板**:
- `oddsmaker_overview` - 概览仪表板
- `oddsmaker_experiments` - 实验分析仪表板

**oddsmaker_overview包含的图表**:
- DAU (日活)
- Events Trend (事件趋势)
- Revenue (收入)
- Revenue by Currency
- Platform Distribution (平台分布)
- App Version Distribution
- Countries Distribution

**oddsmaker_experiments包含的图表**:
- Experiment Exposures (曝光数)
- Experiment Conversion Rate (转化率)
- Exposures by Variant (各变体曝光)
- Variant Performance by Platform (平台表现)

**图表功能完成度**:
- ✓ 时间范围过滤 (Last 90 days默认)
- ✓ 全局过滤器
- ✓ 下钻表格 (部分图表)
- ✓ 导出CSV

**缺失**:
- ✗ 用户留存曲线
- ✗ 漏斗分析图表
- ✗ 用户分群对比
- ✗ 自定义指标计算
- ✗ 预警规则
- ✗ 移动端优化
- ✗ 实时更新(仅每日)

**完成度**: 70% | **优先级**: 中 (基础可用，需增强分析深度)

---

## 4. 控制面功能现状

### 4.1 现有的管理 API ✓ 85%

**项目管理**:
- ✓ POST /api/projects (创建/更新)
- ✓ GET /api/projects (列表/搜索)
- ✓ DELETE /api/projects/{id}

**API密钥管理**:
- ✓ POST /api/keys (创建)
- ✓ GET /api/keys (列表/搜索含分页)
- ✓ GET /api/keys/{apiKey} (获取详情)
- ✓ PUT /api/keys/{apiKey}/policy (更新策略)
- ✓ DELETE /api/keys/{apiKey}
- ✓ POST /api/keys/batch-delete

**实验管理**:
- ✓ POST /api/experiments (创建/更新)
- ✓ GET /api/experiments (列表含过滤/分页)
- ✓ POST /api/experiments/{id}/publish
- ✓ POST /api/experiments/{id}/pause
- ✓ DELETE /api/experiments/{id}
- ✓ GET /api/experiments/export (导出)
- ✓ GET /api/config/{projectId} (SDK获取运行中的实验)
- ✓ POST /api/experiments/validate (校验)

**API密钥策略**:
- ✓ rpm (每个key的requests/min)
- ✓ ipRpm (每个IP的requests/min)
- ✓ propsAllowlist (白名单属性)
- ✓ piiEmail/piiPhone/piiIp (PII处理模式)
- ✓ denyKeys (拒绝属性)
- ✓ maskKeys (掩盖属性)

**缺失**:
- ✗ 用户/权限API
- ✗ 审计日志API
- ✗ 指标查询API (ad-hoc)
- ✗ 报表导出
- ✗ 告警规则API

**完成度**: 85% | **优先级**: 低 (基础覆盖完整)

---

### 4.2 Web UI 的完成度 ✓ 70%

**已实现功能** (位于 /services/control-service/static/index.html):

**管理区块**:
- ✓ Admin Token设置
- ✓ 项目CRUD (创建/编辑/删除/列表)
- ✓ 分页搜索
- ✓ API密钥CRUD
- ✓ 批量删除
- ✓ CSV导出
- ✓ 密钥复制到剪贴板
- ✓ 策略编辑 (RPM/PII/白名单等)
- ✓ 实验CRUD
- ✓ 实验发布/暂停
- ✓ 实验JSON导出
- ✓ 实验配置实时验证
- ✓ 错误提示与高亮

**UI特点**:
- 纯HTML+JavaScript (无框架)
- 本地localStorage持久化token
- 实时配置验证反馈
- 分页导航

**缺失**:
- ✗ 响应式设计/移动适配
- ✗ 暗黑主题
- ✗ 实时搜索
- ✗ 快捷键
- ✗ 国际化 (部分中文)
- ✗ 权限控制UI
- ✗ 实验结果可视化预览
- ✗ Dashboard定制化
- ✗ 通知系统

**完成度**: 70% | **优先级**: 低 (MVP功能足够，UX可优化)

---

### 4.3 缺失的管理功能

**必需但缺失** (高优先级):
- 用户认证/授权系统
- 审计日志记录
- 数据导出/备份
- 告警规则管理
- 实验成本估算

**重要但可后续** (中优先级):
- API配额管理
- 白名单IP配置
- 数据保留策略
- 性能报告
- A/B测试假设检验

**优化项** (低优先级):
- 批量操作
- 快捷模板
- 集成第三方BI
- Webhook通知

---

## 5. SDK 功能现状

### 5.1 各平台 SDK 的完成度

#### 5.1.1 Web SDK ✓ 90%

**位置**: `/sdks/web/src/index.ts` (TypeScript)

**已实现**:
- ✓ 事件采集 (track)
- ✓ 用户识别 (setUserId/setUserProps)
- ✓ 收入事件 (revenue)
- ✓ 自动会话管理 (30min gap)
- ✓ 批量发送 (可配置batch大小)
- ✓ 本地队列持久化 (localStorage)
- ✓ 离线支持 + 重连机制
- ✓ Gzip压缩
- ✓ 实验分配辅助函数
- ✓ 实验配置缓存 + 后台刷新
- ✓ 版本比较函数 (semver)
- ✓ 目标人群匹配 (platform/appVersion/country)
- ✓ HMAC签名支持 (可选)

**质量指标**:
- ~370 LOC
- 无外部依赖
- 完整的TS类型定义
- 充分的注释

**缺失**:
- ✗ Service Worker离线模式
- ✗ IndexedDB大容量队列
- ✗ 性能监控集成
- ✗ 自定义事件验证

**完成度**: 90% | **品质**: 高

---

#### 5.1.2 Android SDK ✓ 85%

**位置**: `/sdks/android/oddsmaker-android/src/main/java/io/oddsmaker/android/Oddsmaker.kt`

**已实现**:
- ✓ 事件采集 (track)
- ✓ 用户识别
- ✓ 收入事件
- ✓ 会话管理
- ✓ SharedPreferences持久化
- ✓ OkHttp3集成
- ✓ 批量发送 + NDJSON
- ✓ Gzip压缩
- ✓ HMAC签名 (可选)
- ✓ 实验相关函数
- ✓ TTL缓存

**缺失**:
- ✗ 完整的JSON序列化 (手写StringBuilder)
- ✗ WorkManager后台同步
- ✗ Lifecycle感知
- ✗ ProGuard规则文件
- ✗ 网络诊断
- ✗ 错误上报

**完成度**: 85% | **优先级**: 中 (需完善序列化)

---

#### 5.1.3 iOS SDK ✓ 80%

**位置**: `/sdks/ios/Sources/Oddsmaker/Oddsmaker.swift`

**已实现** (部分代码):
- ✓ 事件采集
- ✓ 用户识别
- ✓ 会话管理
- ✓ 队列持久化
- ✓ URLSession发送
- ✓ Gzip压缩 (CryptoKit)
- ✓ 后台保存
- ✓ 生命周期感知
- ✓ 实验辅助

**缺失**:
- ✗ 缓存实现 (部分代码被截断)
- ✗ 完整的HMAC签名
- ✗ Combine/async-await现代API
- ✗ 本地数据库支持 (仅文件)
- ✗ 网络监控

**完成度**: 80% | **优先级**: 中 (代码未完整)

---

#### 5.1.4 Unity SDK ✓ 60%

**位置**: `/sdks/unity/Runtime/Oddsmaker.cs`

**已实现**:
- ✓ 基础事件采集
- ✓ 用户识别
- ✓ 会话管理 (仅概念)
- ✓ 收入事件

**缺失**:
- ✗ 批量发送
- ✗ 持久化 (PlayerPrefs)
- ✗ HTTP客户端集成
- ✗ 离线支持
- ✗ 实验功能
- ✗ 完整实现

**完成度**: 60% | **优先级**: 低 (MVP阶段)

---

### 5.2 实验分流功能 ✓ 85%

**核心算法** (FNV-1a 32位哈希):
- ✓ hash32函数 (所有SDK实现一致)
- ✓ 一致的分配结果
- ✓ 权重支持
- ✓ Salt参数化

**功能完成度**:
- ✓ assignVariant() - 基础分配
- ✓ 目标人群过滤 (platform/appVersion/country)
- ✓ 缓存机制 (localStorage/SharedPreferences)
- ✓ 后台刷新 (不阻塞)
- ✓ SDK获取实验配置
- ✓ 曝光事件自动上报

**缺失**:
- ✗ 流量分层 (traffic layers)
- ✗ 互斥分组 (mutually exclusive groups)
- ✗ 动态权重调整
- ✗ 统计假设检验
- ✗ 自适应分配 (bandit算法)

**完成度**: 85% | **优先级**: 低 (基础完整，高级功能后续)

---

### 5.3 游戏专业事件支持 ✓ 50%

**现状**:
- ✓ 通用事件模型 (event_name + props)
- ✓ 收入事件 (revenue)
- ✓ 实验事件 (experiment_exposure)
- ✗ 无预定义游戏事件类型

**缺失的游戏事件**:
```
- game_level_start/complete
- game_battle_start/end
- game_purchase (更细致)
- game_achievement_unlock
- game_quest_accept/complete
- game_dungeon_enter/exit
- game_social_interact (加好友)
- game_ad_watch (看广告)
- game_economy_change (经济事件)
```

**建议方案**:
1. 创建 `game_events.avsc` 预定义schema
2. SDK文档明确游戏事件命名规范
3. Superset添加游戏分析模板

**完成度**: 50% | **优先级**: 中

---

## 总体完成度总结表

| 模块 | 完成度 | 优先级 | 备注 |
|------|--------|--------|------|
| **多租户支持** | | | |
| - Project_ID支持 | 85% | 中 | 权限隔离缺失 |
| - 用户权限管理 | 15% | 高 | 生产必需 |
| - 组织层级 | 0% | 低 | MVP后期 |
| **数据模型** | | | |
| - 事件表结构 | 90% | 低 | 基础完整 |
| - 游戏字段 | 60% | 中 | props可扩展 |
| **分析能力** | | | |
| - Flink作业 | 75% | 中 | 核心到位 |
| - ClickHouse视图 | 80% | 低 | 基础覆盖 |
| - Superset仪表板 | 70% | 中 | 需增强深度 |
| **控制面** | | | |
| - 管理API | 85% | 低 | 基础完整 |
| - Web UI | 70% | 低 | MVP足够 |
| - 缺失功能 | 0% | 高 | 认证/审计 |
| **SDK功能** | | | |
| - Web SDK | 90% | 低 | 质量高 |
| - Android SDK | 85% | 中 | 序列化待优化 |
| - iOS SDK | 80% | 中 | 代码未完整 |
| - Unity SDK | 60% | 低 | MVP阶段 |
| - 实验分流 | 85% | 低 | 基础完整 |
| - 游戏事件 | 50% | 中 | 需预定义 |

---

## 推荐优先级排序

### 第一阶段 (MVP到生产) - 关键路径
1. **用户认证系统** (高) - 必需
2. **权限隔离** (高) - 安全必需
3. **审计日志** (高) - 合规必需
4. **iOS SDK完整化** (高) - 功能缺失
5. **Android JSON序列化优化** (中) - 质量提升

### 第二阶段 (生产就绪) - 运维稳定
1. **数据备份/恢复** (中)
2. **性能监控** (中)
3. **容量规划** (中)
4. **告警规则** (中)
5. **游戏事件预定义** (中)

### 第三阶段 (产品增强) - 深化分析
1. **可配置漏斗** (中)
2. **用户路径分析** (中)
3. **实时监控** (中)
4. **高级实验功能** (低)
5. **组织多层** (低)

---

## 风险评估

| 风险 | 严重性 | 现状 |
|------|--------|------|
| 无身份认证 | 高 | Web UI完全开放 |
| 无权限控制 | 高 | 所有管理员等权 |
| 数据隐私 | 中 | 有PII处理但未完整 |
| 可扩展性 | 中 | Flink状态未优化 |
| 可用性 | 中 | 无HA/备份方案 |

---

## 关键成功指标 (KPI) 建议

**定义对标**:
- DAU追踪准确性: >99%
- 延迟: <5s (事件入库)
- 实验分配一致性: 100%
- API可用性: >99.9%

