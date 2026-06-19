# TODO（短期执行）

面向新架构的短期落地事项。参考：`docs/redesign/05-roadmap.zh.md`。

## P0 模型统一与安全修复

- [ ] 事件契约 v1：统一为 `game_id + environment`，废弃目标模型中的 `tenant_id`、`org_id`、`project_id`
- [ ] Gateway 兼容层：旧 `project_id`、`tenant_id/app_id` 映射到新字段
- [ ] ClickHouse 新建 `events_v1`，按 `(game_id, environment, event_date)` 分区
- [ ] Flink 作业按 `game_id + environment` 重写 key
- [ ] SDK 参数统一：客户端只传 `apiKey/gameId/environment`
- [ ] 移除客户端 SDK HMAC secret；HMAC 仅保留给 Server SDK

## P1 单公司多游戏控制面

- [ ] Game API：游戏增删改查、状态、平台、默认时区、默认货币
- [ ] Environment API：`dev/staging/prod` 配置、采样、数据保留、策略绑定
- [ ] API Key 管理：绑定 `(game_id, environment)`，区分 `client/server/admin`
- [ ] Tracking Plan：事件名、字段字典、枚举、cardinality 上限
- [ ] 公司内 RBAC：`global/game/environment` scope，角色包含 `owner/operator/analyst/developer/risk_admin/viewer`
- [ ] 审计日志：策略、密钥、权限、风控动作全部记录

## P2 风控基础

- [ ] RiskRule API：阈值、黑名单、速度、序列、模型规则
- [ ] Gateway 风控前置：黑名单、重放、时间窗、非法环境、body size
- [ ] Flink risk job：高频事件、重复收据、资源异常、广告 reward 异常
- [ ] ClickHouse 表：`risk_events`、`risk_scores`、`risk_actions`
- [ ] 风控 Webhook：输出 block/review/mark/throttle 到游戏服
- [ ] 风控大屏：风险趋势、规则命中、严重等级、处置状态

## P3 游戏分析能力

- [ ] 事件类型化：session/user/business/resource/progression/design/error/ad/risk
- [ ] Identity Merge：`device_id/user_id/player_id/character_id`
- [ ] 留存：N-Day + Rolling
- [ ] 漏斗：N 步、有序/无序、时间窗
- [ ] 商业化：IAP、广告、LTV
- [ ] 玩法分析：关卡、任务、对局、虚拟经济

## 暂停项

- [ ] 不继续实现 Organization/Tenant 相关新功能
- [ ] 不继续做租户套餐、租户升级、跨公司 Row Policy
