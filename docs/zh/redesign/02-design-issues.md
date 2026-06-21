# 02 - 设计问题清单

按严重程度分级：P0 必须立即修正，P1 影响核心能力，P2 属于增强项。

## P0-1 目标架构不应继续做多租户

当前文档和部分代码把旧方案设计成多公司共享平台，核心实体是 `Organization/Tenant`。

问题：

- 一个正常游戏公司私有化部署时，不会和其他公司共用同一套业务租户。
- `tenant_id` 会污染事件模型、ClickHouse 分区、Flink key、SDK 参数和 BI 查询。
- 多租户会引入跨租户权限、配额、计费、Row Policy、迁移等复杂度，但对目标场景没有收益。

正确做法：

- 公司作为部署边界，不作为业务主键。
- 删除目标模型中的 `tenant_id/org_id/Organization`。
- 用 `game_id + environment` 作为事件、配置、查询和权限的主边界。

## P0-2 `Project/Game/App/Tenant` 四套概念混杂

当前仓库里同时存在：

- 老模型：`ProjectEntity`、`project_id`
- 新模型：`Organization → Game → Environment`
- 事件模型：`tenant_id + app_id`
- 文档模型：多处仍写项目级或租户级隔离

风险：

- SDK 不知道应传 `project_id`、`tenant_id` 还是 `game_id`。
- Flink 作业和 ClickHouse 表分区语义不稳定。
- 控制面 API 难以维护。

正确做法：

- 废弃 `Project` 和 `App` 作为核心业务名词。
- 统一事件字段为 `game_id`、`environment`。
- API Key 绑定 `(game_id, environment)`。
- 旧字段只允许出现在兼容层，并标记迁移期限。

## P0-3 客户端 HMAC Secret 是安全反模式

客户端 SDK 不应该持有 HMAC secret。Android/iOS/Unity/Web 都可以被反编译或调试，secret 泄露后可伪造事件。

正确做法：

- 客户端 SDK 只使用 public `api_key`。
- Server SDK 才能使用 `secret_key + HMAC`。
- 客户端伪造风险通过限流、Schema、风控、设备指纹、收据校验和异常检测处理。

## P0-4 风控缺失为一等链路

当前只有限流、PII 和“异常检测”概念，没有完整风控闭环。

必须补齐：

- 采集前置校验：API Key、环境、签名、时间窗、事件体大小、字段白名单。
- 实时风险识别：高频事件、脚本行为、异常付费、收据复用、多设备撞库、多 IP 登录、模拟器/Root/Jailbreak。
- 风险输出：`risk_events`、`risk_scores`、`risk_actions`。
- 处置动作：标记、降权、拦截、进入人工审核、Webhook 通知游戏服。
- 审计回溯：每条策略命中都可解释、可复盘。

## P1-5 事件模型不够游戏化

当前字段偏通用埋点，缺少游戏行业关键字段。

必须新增：

- 玩家：`player_id`、`character_id`、`server_id`、`guild_id`
- 进度：`level_id`、`progression_path`、`difficulty`、`attempt_num`
- 对局：`match_id`、`game_mode`、`result`、`score`
- 商业化：`order_id`、`product_id`、`receipt_hash`、`ad_network`、`ad_placement`
- 虚拟经济：`virtual_currency`、`virtual_amount`、`flow_type`、`item_id`
- 风控：`risk_context`、`client_integrity`、`ip_hash`、`device_fingerprint`

## P1-6 留存和漏斗能力不足

问题：

- 留存只支持固定偏移。
- 漏斗仅两步。
- 无分层留存、Rolling Retention、付费 cohort、渠道 cohort。

正确做法：

- 留存支持 `nday` 和 `rolling` 两种口径。
- 漏斗支持 N 步、有序/无序、时间窗、按人群切片。
- 所有计算按 `game_id + environment` 分组。

## P1-7 Identity Merge 缺失

游戏典型身份链路是：设备游客 → 平台账号 → 游戏角色 → 区服角色。

正确做法：

- 建立 `identities` 表。
- 支持 `device_id → account_id → player_id → character_id` 归并。
- 留存、LTV、风控都优先使用合并后的稳定玩家键。

## P1-8 付费和广告缺少可信校验

风险：

- 客户端可伪造 IAP。
- 广告回调可能重复或伪造。
- 收入指标和 LTV 会被污染。

正确做法：

- IAP 事件必须支持服务端收据校验。
- 广告收入优先接入服务端回调或聚合报表。
- `order_id/receipt_hash/ad_impression_id` 必须参与去重。
- 风控需要识别异常付费、退款、沙盒单、重复回调。

## P2-9 控制面过重但没有聚焦真实使用流

不需要组织、套餐、租户升级这些 SaaS 功能。

控制面应聚焦：

- 游戏管理
- 环境管理
- API Key 管理
- Tracking Plan
- PII 策略
- 风控规则
- 用户角色
- 告警配置
- 审计日志

## P2-10 BI 和实时大屏边界不清

Superset 适合探索式分析，不适合所有实时运营和风控告警。

正确做法：

- Superset 保留为 BI。
- 自研轻量 Dashboard 用于今日实时指标和风控告警。
- Redis + ClickHouse 聚合表支持秒级或分钟级刷新。
