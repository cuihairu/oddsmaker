# TODO（短期执行）

面向新架构的短期落地事项。参考：`docs/redesign/05-roadmap.zh.md`。

## P0 模型统一与安全修复

- [x] 事件契约 v1：统一为 `game_id + environment`，废弃目标模型中的 `tenant_id`、`org_id`、`project_id`（全链路 schema/Gateway/Flink/ClickHouse/SDK 已无租户字段；Gateway 兼容层显式剔除 `tenant_id`/`org_id`）
- [x] Gateway 兼容层：旧 `project_id`、`tenant_id/app_id` 映射到新字段（`project_id -> game_id`、`app_id -> game_id + environment` 解析、`environment_id` 归一化；`tenant_id`/`org_id` 忽略，仅携带废弃字段的事件按 invalid_schema 拒绝）
- [x] ClickHouse 事件表：重建 `events` 表即 v1 契约，按 `(game_id, environment, toYYYYMM(event_date))` 分区（与设计文档 §5 目标态一致，不引入 events_v1 双表）
- [x] Flink 作业按 `game_id + environment` 重写 key（全部 job keyBy 与 ClickHouse 写入已按新契约）
- [x] SDK 参数统一：客户端只传 `apiKey/gameId/environment`（Web/Android/iOS/Unity 已清理完毕，无 projectId/tenantId/appId 残留）
- [x] 移除客户端 SDK HMAC secret；HMAC 仅保留给 Server SDK

## P1 单公司多游戏控制面

- [x] Game API：游戏增删改查、状态、平台、默认时区、默认货币
- [x] Environment API：`dev/staging/prod` 配置、采样、数据保留、策略绑定
- [x] API Key 管理：绑定 `(game_id, environment)`，区分 `client/server/admin`
- [x] Tracking Plan：事件名、字段字典、枚举、cardinality 上限
- [x] 公司内 RBAC：`global/game/environment` scope，角色包含 `owner/operator/analyst/developer/risk_admin/viewer`
- [x] 审计日志：策略、密钥、权限、风控动作全部记录

## P2 风控基础

- [x] RiskRule API：阈值、黑名单、速度、序列、模型规则
- [x] Gateway 风控前置：黑名单、重放、时间窗、非法环境、body size
- [x] Flink risk job：高频事件、重复收据、资源异常、广告 reward 异常
- [x] ClickHouse 表：`risk_events`、`risk_scores`、`risk_actions`（risk_events/scores 原有；新增 risk_actions 处置归档表 + Control 处置链路写入 + risk_scores 主体风险分联动更新）
- [x] 风控 Webhook：输出 block/review/mark/throttle 到游戏服
- [x] 风控大屏：风险趋势、规则命中、严重等级、处置状态（`/api/risk-metrics/*`，ClickHouse 数据源，CH 未配置时降级返回空数据）

## P3 游戏分析能力

- [x] 事件类型化：session/user/business/resource/progression/design/error/ad/risk
- [x] Identity Merge：`device_id/user_id/player_id/character_id`
- [x] 留存：N-Day + Rolling
- [x] 漏斗：N 步、有序/无序、时间窗
- [x] 商业化：IAP、广告、LTV
- [x] 玩法分析：关卡、任务、对局、虚拟经济
- [x] 实验平台：管理（已有）、分流器（SHA-256 确定性分桶 + 服务端 assign API）、指标快照收集、统计检验（比例 z-test / Welch t-test）与结果 API

## P4 游戏运营工具

- [x] 公告系统：创建/发布/定时发布/定时下线（sweep 扫描）/游戏服活跃拉取
- [x] 邮件系统：全服邮件/个人邮件/附件/过期清理
- [x] 兑换码系统：批量生成/兑换/防刷
- [x] 玩家数据查询：按 playerId 跨游戏基本数据（identity）/充值记录（订单幂等上报）/登录日志
- [x] 玩家数据导出工具：按 (gameId, playerId) 打包导出（档案/充值/登录/兑换四分区，json 单文件或 csv 分区 zip），sweep 异步生成 + 到期清理 + 全量审计

## P5 报表与数据看板增强

- [x] 留存趋势报表：按天/周/月 cohort 新增用户次留/7留/30留趋势（`/api/retention-metrics`，retention_daily 数据源，D30 仅成熟 cohort 计入汇总）
- [x] 付费漏斗分析：注册→首充→二充→月留存转化漏斗 + cohort 明细（`/api/payment-metrics`，events + v_user_first_seen）
- [x] 实时在线监控增强：近 N 分钟独立主体在线数，按平台/版本/渠道（attribution.channel 回退 platform）聚合 + 分钟趋势（`/api/online-metrics`）
- [x] 财报导出：按日/月 ARPU/ARPPU/付费率/DAU/新增/收入/订单指标报表与 CSV 导出（`/api/finance-metrics`，UTF-8 BOM + 导出审计）
- [x] 报表前端页：留存/付费漏斗/在线/财务四个看板页（零依赖 SVG 折线图 + 漏斗/分组可视化，CH 未配置统一降级提示）

## P6 实验、Crash 和智能化

- [x] 实验平台补齐 SRM：卡方检验（实际分桶 vs 配置权重，正则化不完全 gamma 精确 p 值 + 下溢 clamp），`/api/experiments/{id}/results` 输出总体与分指标 SRM（阈值 0.001）
- [x] Crash/Error 链路：Gateway CrashFingerprinter 对 error 事件注入 crash_hash（堆栈前 8 帧规范化：去地址/行号/路径漂移，SHA-256 前 16 hex）+ crash_message；符号化引擎（symbol_mappings.mapping_rules 正则规则，V0.8.5）；Crash 聚合 API `/api/crash-metrics`（Top 分组/趋势/版本崩溃率/符号化）+ 前端 Crash 监控页
- [x] pLTV 预测：D7→D30 乘数法（成熟 cohort 拟合乘数外推未成熟 cohort），`/api/ltv-metrics/{gameId}/pltv`
- [x] 流失预测：v_user_features_30d 特征 → ChurnScorer 启发式打分（不活跃主因子 + 会话衰减 + 付费缓冲，可解释 reasons）→ 归档 predictions（type=churn，TTL 30 天），`/api/prediction-metrics/{gameId}/churn[/refresh]`
- [x] 风险评分模型：risk_events 30 天严重度加权聚合 → RiskScorer 模型分归档 predictions（type=risk_model），与规则分（risk_scores）互补
- [x] Remote Config / LiveOps 联动：游戏级键值配置 CRUD + 环境覆盖解析（环境特定 key 覆盖全环境）+ 聚合版本增量拉取（304），V0.8.6 remote_configs 表
- [x] 测试覆盖率：引入 JaCoCo（test 后自动生成 XML/HTML/CSV 报告），P5/P6 新代码指令覆盖率 98.3%（control）/96.7%（gateway CrashFingerprinter，剩余为不可达防御分支）；gateway config/kafka 组件包补齐（89.4%/76.3%，剩余为需真实 broker 的初始化分支）

## P7 竞品差距收敛（依据 `docs/competitive-analysis.md`，2026-09 竞品调研）

- [x] P7-1 实时事件检视器（Live Inspector / Debug View）：Gateway 内存环形缓冲记录每条事件结局（accepted/rejected/sampled_out/duplicate + 拒绝原因与 schema 明细）+ `/v1/inspector/recent` 检视 API（API Key 作用域过滤）+ Control 代理端点 + 控制台 Live Inspector 页（轮询刷新）
- [x] P7-2 可复用用户分群（Segments）：分群定义（属性 + 行为条件）→ ClickHouse 物化（segment_members ReplacingMergeTree）→ 在线报表注入 segment 过滤（留存/漏斗/财务待接入）→ 控制台分群管理页（创建/计算/成员预览/启停/软删）；权限 segment:read / segment:manage
- [x] P7-3 自定义仪表盘 widget 化：仪表盘 CRUD（V0.9.13 迁移 + dashboard:read/manage 权限）+ 布局 JSON 校验（widget 类型/数据源白名单、params 数值钳制、span 规范化）+ 控制台 widget 编辑器（KPI/折线/柱状/表格 × 在线/留存/付费漏斗/Crash 四数据源、12 栅格布局、按游戏保存）；权限 dashboard:read / dashboard:manage
- [x] P7-4 全量原始数据导出：events 按日分区导出 JSONL（gzip 可选）到导出目录（对象存储由运维同步该目录），分批游标读取（单分区上限 500 万行）+ manifest（行数/字节/SHA-256）原子写 + 分区列表 API + 控制台导出页；权限复用 export:execute
- [x] P7-5 MMP 归因接入评估：AppsFlyer/Adjust 数据源与建表调研（`docs/mmp-attribution-evaluation.md`）——调研结论：三家主流 MMP 均支持 Webhook 实时推 + 定时落自有云存储两类原始数据通道；推荐方案 B（定时云存储导出 → 加载 job → `attribution_installs` 表，与 P7-4 导出目录模式对称），有条件立项进 P8（前置：真实 MMP 原始数据套餐权限）；不做广告平台直连

明确不跟进（详见竞品分析 §4）：Session Replay、行业基准、游戏后端（排行榜/成就/多人服务器）、自建推送通道、广告平台直连、多租户 SaaS 化。

## 暂停项

- [ ] 不继续实现 Organization/Tenant 相关新功能
- [ ] 不继续做租户套餐、租户升级、跨公司 Row Policy
