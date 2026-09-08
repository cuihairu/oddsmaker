# TODO（短期执行）

面向新架构的短期落地事项。参考：`docs/redesign/05-roadmap.zh.md`。

## P0 模型统一与安全修复

- [ ] 事件契约 v1：统一为 `game_id + environment`，废弃目标模型中的 `tenant_id`、`org_id`、`project_id`
- [ ] Gateway 兼容层：旧 `project_id`、`tenant_id/app_id` 映射到新字段
- [ ] ClickHouse 新建 `events_v1`，按 `(game_id, environment, event_date)` 分区
- [ ] Flink 作业按 `game_id + environment` 重写 key
- [ ] SDK 参数统一：客户端只传 `apiKey/gameId/environment`（Web/Android 已符合；iOS/Unity 清理中）
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
- [ ] ClickHouse 表：`risk_events`、`risk_scores`、`risk_actions`
- [x] 风控 Webhook：输出 block/review/mark/throttle 到游戏服
- [ ] 风控大屏：风险趋势、规则命中、严重等级、处置状态

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
- [ ] 玩家数据查询与导出工具

## 暂停项

- [ ] 不继续实现 Organization/Tenant 相关新功能
- [ ] 不继续做租户套餐、租户升级、跨公司 Row Policy
