# Oddsmaker 外部审核（2026-10-18）· 生产安全 / 数据一致性 / 灾备 / 可观测

> 来源: https://chatgpt.com/s/t_6ad47e1300088191b029aac673d73745
> 取回: 2026-10-18
>
> **性质：外部建议，不是本仓事实陈述。** 每条结论必须先与代码核对再采纳；
> 下方「核验结论」表记录核对结果，未核实的条目不得直接实施。

># Oddsmaker — Code Agent TODO

> **项目目标：** 优先解决生产安全、数据一致性、部署可靠性、灾备恢复和可观测性问题，将 Oddsmaker 从功能较完整的工程项目推进到可验证、可运维的游戏数据分析与风控平台。
>
> **审计日期：** 2026-10-18
>
> **执行原则：** 本文件中的问题来自静态审计。Code Agent 必须先检查当前分支的真实代码、配置、测试和提交记录。已经修复的问题应验证后标记完成，不得重复修改或仅凭文档宣称完成。

---

## 1. 执行规则

### 1.1 任务优先级

- **P0 — Critical：** 生产安全风险，必须优先处理。
- **P1 — High：** 数据正确性、发布可靠性、灾备及核心运行能力。
- **P2 — Medium：** 文档一致性、工程治理和运维体验。
- **BLOCKED：** 缺少外部条件、权限、真实数据或必要决策，暂时无法完成。

### 1.2 状态管理

每项任务使用以下状态：

- `[ ]` 待执行
- `[x]` 已完成，并附有验证证据
- `IN_PROGRESS` 正在执行
- `BLOCKED` 存在明确的外部阻塞

只有实际完成修改并通过相应验收后，才能勾选完成。

每项完成的任务必须记录：

1. 修改内容及涉及文件。
2. 执行的测试命令。
3. 实际测试结果。
4. 必要的运行、部署或恢复验证证据。
5. 尚未解决的问题及其影响。

### 1.3 全局约束

- 修改前检查 Git 分支、工作区状态和近期提交，不覆盖用户已有的未提交修改。
- 优先采用最小、可回滚的修改，不进行无关的大规模重构。
- 不为了通过测试而删除断言、降低安全要求或跳过关键验证。
- 不把空数据、未执行的作业或缺失的下游消费者伪装成正常运行。
- 不在日志、测试报告、提交记录或文档中泄露密码、Token、私钥及其他敏感信息。
- 不假设某项功能尚未实现；先搜索代码并验证当前状态。
- 不宣称端到端恰好一次处理，除非整个链路确实满足相应保证。
- 未经授权，不修改生产 DNS、云平台配置或线上数据。
- 不将静态检查通过等同于生产验证通过。

---

## 2. 当前架构约束

所有后续修改必须保持以下设计原则：

1. 一个部署实例服务于一家游戏公司。
2. 同一实例管理多个游戏和多个环境。
3. `game_id + environment` 是业务与数据隔离的核心边界。
4. `storage_profile` 用于物理存储和路由策略，不代替业务隔离。
5. 事件采集、流式处理、分析查询和风控决策必须有明确的可用性状态。
6. 风控信号、特征、规则、风险评分、决策和动作应保持职责分离。
7. Lite、Standard、Production 等运行模式必须准确反映真实能力。
8. 保留现有技术栈与领域模型，除非有明确证据证明当前设计无法满足需求。

---

# 3. P0 — 生产安全

## SEC-001：移除生产环境默认凭据

**状态：** 待核实

**问题背景**

静态审计发现，根目录 `docker-compose.yml` 及相关配置中存在开发用途的默认密码和 Token，例如 `oddsmaker`、`dev-admin-token`、`dev-internal-token` 和 `dev-secret`。

这些值可以用于隔离的开发环境，但不得成为生产环境的隐式默认值。

**执行步骤**

- [ ] 检查 `docker-compose.yml`、`.env.example`、所有部署清单、容器启动脚本及应用配置。
- [ ] 搜索生产运行路径中所有默认密码、固定 Token、硬编码密钥及不安全的回退值。
- [ ] 明确区分开发配置与生产配置。
- [ ] 将生产敏感配置改为通过环境变量或 Secret 注入。
- [ ] 对必需的生产密钥实施 fail-closed 校验：缺失、为空或仍使用已知开发凭据时，拒绝启动。
- [ ] 确保应用启动日志不会输出敏感配置的明文。
- [ ] 检查 PostgreSQL、Redis、Kafka、ClickHouse 等基础设施的端口映射和网络暴露范围。
- [ ] 默认不将内部基础设施端口暴露到公网；需要访问时，通过受控网络、绑定地址或访问策略明确授权。
- [ ] 更新部署文档，说明生产密钥生成、注入、轮换和权限管理方法。

**验收标准**

- [ ] 不配置生产必需密钥时，应用或部署流程能够明确失败。
- [ ] 使用已知开发 Token 启动生产配置时，应用或部署流程能够拒绝启动。
- [ ] 开发环境仍可按照文档正常启动。
- [ ] 未授权的外部网络无法直接访问内部基础设施端口。
- [ ] 自动化测试覆盖缺失密钥、默认密钥及有效生产配置。
- [ ] 部署文档与实际配置一致。

**注意事项**

不得仅删除默认值而不提供合理的配置校验与迁移说明。不得为了方便测试而在生产配置中保留危险的回退路径。

---

# 4. P1 — 数据正确性

## DATA-001：统一事件幂等键与去重边界

**状态：** 待核实

**问题背景**

静态审计发现，设计文档将事件的逻辑身份定义为：

`game_id + environment + event_id`

但 Gateway 的 `ReplayGuard.consumeEventId` 被记录为使用单独的 `event_id` 去重，而 ClickHouse 的事件表也需要单独考虑重复写入语义。

如果当前实现仍然如此，不同游戏或环境之间可能发生不必要的去重冲突；即使入口去重正确，下游重试仍可能产生重复数据。

**执行步骤**

- [ ] 检查事件 ID 的生成规则、Gateway 校验逻辑及 `ReplayGuard.consumeEventId`。
- [ ] 检查 Redis 幂等键的构造、TTL、并发处理及异常恢复行为。
- [ ] 统一事件逻辑身份，至少包含 `game_id`、`environment` 和 `event_id`。
- [ ] 使用无歧义的键编码方式，避免简单拼接产生键冲突。
- [ ] 检查 Kafka 重投、消费者重启、Flink 状态恢复及 ClickHouse 重试的重复数据行为。
- [ ] 明确各阶段采用的投递和处理语义，区分入口去重与端到端数据一致性。
- [ ] 根据现有表结构和查询逻辑，决定是否需要下游去重、幂等写入或聚合修正。
- [ ] 为重复事件、跨游戏事件、跨环境事件及并发请求增加回归测试。

**验收标准**

- [ ] 同一游戏、同一环境、同一事件 ID 的重复请求符合既定幂等语义。
- [ ] 不同游戏即使事件 ID 相同，也不会相互抑制。
- [ ] 同一游戏的不同环境即使事件 ID 相同，也不会相互抑制。
- [ ] 并发请求和 Redis TTL 行为符合预期。
- [ ] Kafka 重投及下游重试不会在统计结果中产生未处理的重复计数。
- [ ] 对无法提供严格一次性保证的链路，文档明确说明实际语义及残余风险。

**注意事项**

不能仅修改 Redis Key 就将本任务标记为完成。必须验证下游存储、统计聚合和重放场景。

## DATA-002：明确采集、分析与风控就绪状态

**状态：** 待核实

**问题背景**

静态审计发现，Standard 模式可能允许事件进入 Kafka，但不启动完整的 Flink 分析作业。此时部分分析接口仍可能返回 `available=true`，造成接口状态与实际分析能力不一致。

**执行步骤**

- [ ] 检查 Lite、Standard、Production 三种模式的实际启动组件。
- [ ] 检查分析 API 的 `available`、错误响应、空结果及降级行为。
- [ ] 区分采集可用、分析可用和风控可用状态。
- [ ] 区分进程存活、服务就绪和业务数据链路就绪。
- [ ] 为缺少 Flink 消费者、ClickHouse 不可用、分析作业停止等场景定义明确状态。
- [ ] 检查风控依赖缺失时是否可能错误地执行高风险决策。
- [ ] 为各运行模式增加接口状态与数据链路的集成测试。
- [ ] 更新 README、API 文档和运行模式说明。

**验收标准**

- [ ] Standard 模式不会将尚未执行的分析能力伪装为正常可用。
- [ ] 分析能力未就绪时，API 返回明确的不可用或降级信息。
- [ ] 分析已就绪但暂时没有事件时，仍可正确表达“可用但暂无数据”。
- [ ] Kafka 积压、消费者停止及 ClickHouse 不可用时，状态反映真实情况。
- [ ] 风控系统不会把依赖缺失误判为低风险或安全。
- [ ] 三种运行模式均有自动化验证。

**注意事项**

不要把所有空结果都视为错误。必须区分正常的零数据、未完成的数据处理和基础设施故障。

---

# 5. P1 — 部署与发布

## DEPLOY-001：统一 Control 与 Gateway 的发布契约

**状态：** 待核实

**问题背景**

静态审计发现，Release 工作流的生产部署阶段可能只更新 Control Deployment，而 Gateway 使用独立的运行模式。如果生产要求两者保持同一版本，则可能出现发布后版本不一致。

另外，工作流使用的健康检查地址包含 `.local` 域名，必须确认执行检查的 Runner 是否实际能够访问该地址。

**执行步骤**

- [ ] 检查 `.github/workflows/release.yaml` 的完整发布过程。
- [ ] 检查 Control 与 Gateway 的部署清单、镜像引用和版本更新机制。
- [ ] 明确发布流程是否负责同时更新两个服务。
- [ ] 如果需要同步发布，使用相同的不可变镜像版本或 digest。
- [ ] 检查发布失败、健康检查失败及回滚逻辑。
- [ ] 验证发布 Runner 对健康检查地址的 DNS 解析、网络路由和访问权限。
- [ ] 如果需要访问内网，采用经过授权的自托管 Runner 或受控的网络访问方式。
- [ ] 为部署后的验证增加真实事件采集冒烟测试。
- [ ] 记录发布版本、两个服务的实际运行版本和最终验证结果。

**验收标准**

- [ ] Control 与 Gateway 的发布版本符合明确的兼容策略。
- [ ] 发布完成后可以确认两个服务的实际运行版本。
- [ ] 健康检查在真实 CI 网络环境中能够执行。
- [ ] 发布失败不会被错误地标记为成功。
- [ ] 真实采集请求能够被接受并进入预期的处理链路。
- [ ] 回滚步骤经过验证。

**注意事项**

不得为了让健康检查通过而将内部管理接口直接暴露到公网。先验证网络条件，再选择合理的检查方案。

## DEPLOY-002：明确 Production 模式的运行与扩容边界

**状态：** 待核实

**问题背景**

静态审计发现，根 Compose 中的 Flink 作业可能通过独立 JVM 和 local executor 方式运行，而不是独立的 Flink 集群。

这种模式适合简化部署，但不能自动提供集群调度、水平扩容和完整的任务故障恢复能力。

**执行步骤**

- [ ] 检查所有 Flink 作业的实际启动命令和执行环境。
- [ ] 区分单机 local executor、Flink 集群和 Kubernetes 运行方式。
- [ ] 检查作业重启、检查点、状态恢复、数据重放及失败告警。
- [ ] 明确当前支持的部署规模和已验证的运行边界。
- [ ] 在部署文档中说明单机模式与集群模式的能力差异。
- [ ] 将集群化改造单独列为后续工作，不与本次必要修复混合实施。

**验收标准**

- [ ] 每种部署模式都有明确的启动方式和运行能力说明。
- [ ] 文档不再将单机运行方式等同于完整的高可用 Flink 集群。
- [ ] 核心作业的故障处理和恢复行为有测试或明确的限制说明。
- [ ] 不因本任务引入不必要的大规模架构迁移。

---

# 6. P1 — 灾备与恢复

## DR-001：验证异地备份和真实恢复闭环

**状态：** 待核实

**问题背景**

静态审计发现，Kubernetes 备份任务使用的容器环境可能缺少 AWS CLI。若备份脚本在客户端缺失时只发出警告并跳过上传，就可能出现本地备份成功、异地备份失败，但任务仍被误认为成功的情况。

**执行步骤**

- [ ] 检查 PostgreSQL 备份、WAL 归档及恢复流程。
- [ ] 检查 Redis 持久化、备份和恢复流程。
- [ ] 检查 ClickHouse 备份脚本、表结构恢复及数据恢复流程。
- [ ] 检查 Kubernetes CronJob 使用的镜像是否具备实际需要的客户端工具。
- [ ] 明确本地备份成功、异地上传成功和恢复验证成功是不同状态。
- [ ] 对要求异地保存的备份，在上传失败时返回失败状态并触发告警。
- [ ] 检查对象存储凭据权限、加密、备份保留周期和校验机制。
- [ ] 记录备份文件、时间戳、校验信息和对应版本。
- [ ] 在隔离环境中执行 PostgreSQL、Redis 和 ClickHouse 的恢复演练。
- [ ] 验证恢复后的关键数据、Schema、权限及应用连接。
- [ ] 明确 RPO、RTO 的业务目标；缺少目标时记录为待决策项，不自行编造指标。

**验收标准**

- [ ] 所有要求异地保存的备份均有可验证的上传结果。
- [ ] 必需的异地上传失败时，备份任务不会静默成功。
- [ ] 能够在隔离环境中恢复核心数据库。
- [ ] 恢复后的数据通过完整性检查和应用级验证。
- [ ] 恢复过程不会覆盖生产数据。
- [ ] 备份、上传、校验和恢复均有可审查的执行记录。
- [ ] 灾备文档记录真实演练结果，而不只是脚本执行成功。

---

# 7. P1 — 可观测性

## OBS-001：接通业务指标、告警和故障诊断

**状态：** 待核实

**问题背景**

静态审计发现，项目已有 OpenTelemetry Collector、Prometheus 和 Grafana 等观测组件，但业务应用是否真正向观测系统输出指标，仍需要验证。

仅启动监控组件并不能证明业务链路已经可观测。

**执行步骤**

- [ ] 检查 Control、Gateway、Flink 作业和其他关键服务的指标导出方式。
- [ ] 检查 OpenTelemetry Collector 的接收、处理和导出配置。
- [ ] 验证 Prometheus 是否能抓取真实业务指标。
- [ ] 为 Gateway 增加请求量、错误率、延迟和拒绝率指标。
- [ ] 为 Kafka 增加消费积压、消费延迟和死信队列指标。
- [ ] 为 Flink 增加作业状态、重启、处理延迟和检查点相关指标。
- [ ] 为 ClickHouse 增加写入错误、查询延迟及存储增长指标。
- [ ] 为风控系统增加规则命中、决策结果、动作执行失败和告警投递结果指标。
- [ ] 检查指标标签的基数，避免使用 `user_id`、`event_id` 等高基数字段作为常规标签。
- [ ] 检查日志和指标中是否包含 Token、个人数据或不必要的原始事件内容。
- [ ] 建立关键故障的告警规则和处置说明。
- [ ] 通过故障注入或集成测试验证告警确实能够触发。

**验收标准**

- [ ] 能够从观测系统查询到真实业务服务的指标。
- [ ] Gateway、Kafka、Flink、ClickHouse 和风控关键链路具有基本故障可见性。
- [ ] 关键告警具有触发条件和处理说明。
- [ ] 重要故障发生时，不需要仅依赖人工查看日志才能发现。
- [ ] 指标和日志不引入不必要的敏感数据及高基数问题。

---

# 8. P2 — 文档与工程治理

## DOC-001：统一 Flink 作业和部署模式的文档口径

**状态：** 待核实

**问题背景**

静态审计发现，README、运行模式文档和重构计划对 Flink 作业的数量及 `dimension-sync`、`configurable-funnels` 等组件的归类可能不一致。

需要通过实际启动入口、打包配置和部署清单确定事实，而不是简单统一数字。

**执行步骤**

- [ ] 枚举实际存在的 Flink 作业入口类和启动命令。
- [ ] 检查独立 JAR、主类、共享模块和独立 Agent 的关系。
- [ ] 对照根 Compose、Kubernetes 清单、README 和 `deploy/MODES.md`。
- [ ] 统一“作业数量”“JAR 数量”“独立运行进程数量”的定义。
- [ ] 明确各运行模式实际启动的作业及依赖。
- [ ] 标记已实现、未实现、可选和已弃用的能力。
- [ ] 检查文档示例中的配置、端口、环境变量与当前代码是否一致。

**验收标准**

- [ ] 作业清单可从实际代码与启动配置中验证。
- [ ] README 和运行模式文档之间不存在相互矛盾的描述。
- [ ] 文档明确 Standard 模式与 Production 模式的数据处理能力差异。
- [ ] 不再使用含义模糊的单一数字概括不同运行对象。

## GOV-001：收敛 TODO 与历史审计记录

**状态：** 待核实

**问题背景**

主 TODO 文件包含重复的巡检证据、CDC 前置条件、演示站点故障和 ML 触发条件记录。这些记录具有审计价值，但会增加 Code Agent 判断当前任务状态的难度。

**执行步骤**

- [ ] 检查当前 `todo.md`、历史评估文件和相关故障记录。
- [ ] 为每个未完成事项建立唯一的任务 ID。
- [ ] 合并相同问题的重复条目，保留当前状态和最新证据。
- [ ] 将长期有效的架构决策迁移至决策文档。
- [ ] 将详细故障时间线迁移至独立的运维事件记录。
- [ ] 将外部依赖未满足的任务明确标记为 `BLOCKED`。
- [ ] 为每项任务补齐负责人或执行主体、优先级、依赖关系和验收标准。
- [ ] 检查是否已有文档构建或链接检查 CI；仅在缺失且确有价值时补充。
- [ ] 确保归档评估快照不会被误认为当前项目状态。

**验收标准**

- [ ] 主 TODO 聚焦当前未解决的任务。
- [ ] 每项任务只有一个权威状态。
- [ ] 历史证据可以追溯，但不会重复出现在多个待办条目中。
- [ ] 已完成任务具有验证证据。
- [ ] 阻塞任务明确说明解除阻塞所需的条件。
- [ ] Code Agent 可以据此独立判断下一项应执行的工作。

## ML-001：明确 ML 能力的真实运行状态

**状态：** 待核实

**问题背景**

项目文档包含模型训练与批量评分能力，但训练管线存在并不意味着在线异常检测已经投入生产运行。

**执行步骤**

- [ ] 核实各模型当前实现的训练、评分、部署和调用方式。
- [ ] 区分离线训练、批量评分、实时特征计算和在线模型推理。
- [ ] 明确哪些能力已经实际接入生产链路，哪些仍为待实施能力。
- [ ] 验证指标历史、训练数据和模型评估流程是否具备实际数据基础。
- [ ] 对尚不具备数据条件的在线异常检测任务保持 `BLOCKED`。
- [ ] 明确未来启动该任务所需的真实流量、基线、误报率评估和验收条件。

**验收标准**

- [ ] 文档准确描述当前 ML 能力及运行模式。
- [ ] 不将训练完成或离线评分成功宣称为在线检测已上线。
- [ ] 在线异常检测的前置条件和启用条件明确。
- [ ] 不为满足功能数量而引入没有业务数据支撑的复杂模型。

## SITE-001：复核公开演示站点的可访问性

**状态：** 待核实

**问题背景**

历史 TODO 记录中存在公开演示站点返回 502 的问题，记录将原因指向 Cloudflare 回源配置，但本次审计未能独立确认当前状态。

**执行步骤**

- [ ] 在具备网络访问权限的环境中检查演示站点当前 HTTP 状态。
- [ ] 检查 DNS、TLS、Cloudflare 回源、反向代理和应用健康状态。
- [ ] 区分 DNS 问题、TLS 问题、代理连接失败和应用本身不可用。
- [ ] 如果问题依赖云平台或 DNS 权限，整理证据与所需操作。
- [ ] 未获得授权前，不修改生产 DNS、Cloudflare 配置或云端资源。

**验收标准**

- [ ] 当前站点状态有可复核的检查结果。
- [ ] 故障原因有证据支持，不以历史记录替代当前验证。
- [ ] 修复后能够从预期外部网络正常访问。
- [ ] 如果依赖用户操作，任务保留为 `BLOCKED` 并明确所需操作。

---

# 9. 推荐执行顺序

按照风险和依赖关系，优先采用以下顺序：

1. `AUDIT-000`：确认当前工作区、分支、已有修复及所有任务的真实状态。
2. `SEC-001`：修复生产默认凭据及网络暴露风险。
3. `DATA-001`：统一事件幂等边界并验证下游重复数据语义。
4. `DEPLOY-001`：修正发布契约和生产健康检查。
5. `DR-001`：验证异地备份及完整恢复闭环。
6. `DATA-002`：统一采集、分析和风控的就绪状态。
7. `OBS-001`：补齐业务指标和关键故障告警。
8. `DEPLOY-002`：明确 Flink 运行和扩容边界。
9. `DOC-001`：统一作业清单与部署模式文档。
10. `GOV-001`：收敛 TODO 和历史记录。
11. `ML-001`、`SITE-001`：根据前置条件和实际状态处理。

如果核实发现某个问题已经修复，应先执行相关回归测试并附上证据，再跳过该项。

---

# 10. 最终验收标准

在本轮整改结束前，Code Agent 必须完成以下检查：

- [ ] 生产环境不会隐式使用已知开发密码或 Token。
- [ ] 事件幂等边界符合 `game_id + environment + event_id` 的业务定义。
- [ ] 重试、重放和下游重复写入具有明确且经过测试的语义。
- [ ] Control 与 Gateway 的发布版本及兼容关系可验证。
- [ ] 生产发布后的健康检查和真实事件采集冒烟测试通过。
- [ ] 必须异地保存的备份具备明确的成功、失败和告警状态。
- [ ] 核心数据库至少完成一次隔离环境中的真实恢复验证。
- [ ] Lite、Standard、Production 的实际能力与文档一致。
- [ ] 分析和风控不可用时，不会伪装成正常运行。
- [ ] 关键业务指标可查询，重要故障具备可验证的告警。
- [ ] README、部署文档、作业清单与当前代码一致。
- [ ] TODO 中所有完成项都有测试或运行证据，所有阻塞项均有明确原因。

## 交付报告

完成一轮工作后，Code Agent 应输出以下内容：

1. **已完成任务：** 任务 ID、修改文件、修改摘要、测试结果。
2. **未完成任务：** 任务 ID、当前状态、阻塞原因和下一步操作。
3. **验证结果：** 实际执行的命令、通过或失败的测试及关键输出。
4. **风险说明：** 尚未解决的安全、数据一致性和生产运行风险。
5. **后续建议：** 按优先级列出下一轮最有价值的工作。

**完成定义：代码修改不等于任务完成，测试通过不等于生产验证通过。每项任务必须以其对应的验收标准和实际证据为准。**
`</WritingBlock>`

建议将这份文件作为 Code Agent 的**执行计划**，并要求 Agent 每次只推进一组有明确边界的任务。尤其是 `SEC-001`、`DATA-001` 和 `DR-001`，应先完成验证与修复，再进入后续工作。

---

If you want, I can:

- 继续完善 P2 项文档与工程治理任务
- 详细展开 P1 的灾备与恢复章节内容
- 继续补充 P1 可观测性改进的验收标准和执行细节

====

}
# Oddsmaker — Code Agent TODO

> **项目目标：** 优先解决生产安全、数据一致性、部署可靠性、灾备恢复和可观测性问题，将 Oddsmaker 从功能较完整的工程项目推进到可验证、可运维的游戏数据分析与风控平台。
>
> **审计日期：** 2026-10-18
>
> **执行原则：** 本文件中的问题来自静态审计。Code Agent 必须先检查当前分支的真实代码、配置、测试和提交记录。已经修复的问题应验证后标记完成，不得重复修改或仅凭文档宣称完成。

---



---

## 核验结论（待核对，勿直接实施）

| 审核条目 | 核验结果 | 证据 file:line |
|---|---|---|
| SEC-001 docker-compose.yml 存在默认密码/Token | **属实**（compose 面；k8s 面干净） | `docker-compose.yml:30,141,190,197`（POSTGRES/CLICKHOUSE_PASSWORD=oddsmaker）、`:200-201,246-247`（dev-admin-token/dev-internal-token/dev-secret）、`:565-567`（superset 占位/admin）；k8s 走 secretRef（`deploy/k8s/gateway-service.yaml:85-86`、`deploy/k8s/backup-cronjob.yaml:111-112,164-165`）；fail-closed 校验缺失待整改 |
| DATA-001 事件幂等键与去重边界不统一 | **属实** | `ReplayGuard.java:86-88` `mark(eventId)` 仅按裸 event_id 去重、键无 game_id/environment；调用方 `BatchController.java:200` 直传 `event.eventId`——跨游戏/跨环境同 event_id 会互相抑制；下游 `EventsEnrichJob.java:112` keyBy 同为裸 `event_id`（7 天 TTL 去重，跨游戏同号误入 DLQ） |
| DEPLOY-001 Control 与 Gateway 发布契约不一致 | **属实** | `release.yaml:172` 仅 set image `deployment/oddsmaker-control`；`:178` 健康检查 `https://control.oddsmaker.local`（.local 仅同网 runner 可解析）；`deploy/k8s/gateway-service.yaml` 存在但发布流程不更新 gateway |
| DR-001 异地备份与恢复闭环未验证 | **部分属实**（降级系既有拍板） | `deploy/backup/postgres-backup.sh:96-101` 与 `deploy/k8s/backup-cronjob.yaml:34-37`：aws 缺失 WARN 跳过离站、任务仍 exit 0——a0c1b9f 有意降级（防 set -e 整条掐断留存清理）；缺口=离站失败仅日志 WARN、无告警/非零退出；隔离环境恢复演练未做过 |
| OBS-001 业务指标/告警未接通 | **部分属实**（文件面缺口已补，接线面仍待实测） | control 已暴露 prometheus 端点（`application.yaml:171-177`）+ Prometheus 抓 8 job（`prometheus.yaml:21-78`：control/PG/Redis/Kafka/CH/Flink/node）+ 告警 7 条（`oddsmaker-alerts.yml:6-67`）；缺口 1（已补）：gateway 无抓取 job——k8s 面 Service `oddsmaker-gateway:80`→8080、actuator 同端口（`gateway-service.yaml:65-67,138-140`），已补 job+down 告警；缺口 2（待实测）：compose 两件套面 infra/prometheus.yml 只抓 otel-collector（`infra/prometheus.yml:1-4`），业务服务指标未接、真实流量下告警触发未验证（demo 面无监控栈） |
| ML-001 ML 能力真实运行状态不明 | **已有结论**（与账本一致） | 训练管线在 CI（`ci.yaml:83-84` ml-tests，68 例）；在线异常检测已 BLOCKED（`todo.md` ML 条——指标表 0 行、无部署面）；训练完成≠在线检测上线，账本口径已满足本条要求 |
| DATA-002 分析接口就绪状态与链路不一致 | **属实**（语义面，2026-10-18 增量整改已落地【已代决=向后兼容增量】） | 原诊断属实：`FunnelResultsService` available 反映「CH 可达且查询成功」而非「分析链路在产数据」——Standard 档无 Flink（`docker-compose.quickstart.yml:3`）时空数据仍 available=true。整改：新增 `ClickHouseClient.analysisLastEventAt()`（`ClickHouseClient.java:64-89`，events.max(ts_server)，分区裁剪廉价查询，失败 warn→null 不抛）+ 漏斗结果出参携带 `analysisLastEventAt`（`FunnelResultsService.java:59`，ISO-8601 UTC，null=分析管道从未产出）+ 统一读口 `GET /api/games/{gameId}/readiness`（`GameReadinessController`/`GameReadinessService`）——available 语义不变，不牵动 web/SDK/e2e；降级面零额外查询、字段整体省略。`CohortService.java:301` CH 未配置抛错**保留**：显式 calculate 动作 fail-loud 与读路径降级语义不同，不视为同类缺陷。其余分析出参（finance/crash/risk/prediction 等）叠加同字段属机械扩展，后续批次 |
| DEPLOY-002 Production Flink 运行边界未声明 | **属实**（文档缺口，本轮已补） | `docker-compose.yml:7`（local executor 形态头注）+ 7 作业容器 `java -jar/-cp` 直跑（`:298,342,380,418,455,503,545`）；全 compose 无 restart 策略、7 模块 checkpointing 均进程内（`jobs/flink/*/src/main` enableCheckpointing）。形态有记录、能力边界无声明——已补入 `deploy/MODES.md` Production 节 |
| DOC-001 Flink 作业数量/口径不一致 | **属实**（本轮已统一） | 三套口径并存：`jobs/flink/` 源 7 模块、根 compose 7 容器（dimension-sync 不在 compose、funnels fatJar 双跑 FunnelsJob/ConfigurableFunnelsJob——`docker-compose.yml:418,455-456`）、nightly 6 fatJar+dimension-sync agent（`README.md:217`）；`README.md:276`「seven jobs」列名与运行面不一致。已按口径重写 README:137/276（中英）并在 MODES.md 定义三口径 |

## 落地跟踪

- [x] 核对上表六条，填证据（2026-10-18 本仓复核：6/6 已填结论+file:line；SEC-001/DATA-001/DEPLOY-001 属实，DR-001/OBS-001 部分属实，ML-001 与账本 BLOCKED 一致。同日补核 DATA-002/DEPLOY-002/DOC-001 三条并填证据；GOV-001 待用户拍板、SITE-001 与账本 502 条同源去重）
- [x] SEC-001 移除生产默认凭据（fail-closed 校验）（2026-10-18 整改：根 compose + infra compose 全部凭据改 `${VAR:?}` 必填；control-service 新增 `ProductionSecretsValidator`（`ODDSMAKER_REQUIRE_PRODUCTION_SECRETS=true` 时启动校验非空白且非开发默认值，不合规拒绝启动）；quickstart/demo 开发档不动；`deploy/MODES.md` Production 节补 .env 必填变量清单与 `--project-directory .` 起动方式；`docker compose config -q` 双向实证：缺变量 exit 15 报错点名、全设 pass）
- [x] DATA-001 幂等键统一（2026-10-18 整改：入口 `ReplayGuard.consumeEventId` 改为 `consumeEventId(gameId, environment, eventId)`，幂等键=逻辑事件身份三元组，长度前缀编码（hex 长度定界）杜绝裸拼接/分隔符撞键，调用方 `BatchController.java:200` 同步；下游 `EventsEnrichJob.java` 去重 keyBy 原为裸 `event_id`（同款缺陷），改命名方法 `dedupKey` 三元组编码同口径；其余 Flink 作业 keyBy 均为命名复合键、无裸 event_id；回归测试覆盖跨游戏/跨环境不互斥 + 分隔符敌意输入不撞键，gateway 全量 239 绿、enrich 49 绿）
- [x] DATA-002 就绪状态·增量面（2026-10-18【已代决】：不改 available 语义（向后兼容，web/SDK/e2e 零牵动），`ClickHouseClient.analysisLastEventAt()` 中心能力 + 漏斗结果出参携带（ISO-8601 UTC；null=分析管道从未产出或查询失败），Standard 档无 Flink 时调用方可区分「CH 可达」与「分析在产数据」；降级响应零额外查询、字段整体省略；就绪度查询失败 warn→null 不破坏主链路。测试先行：`ClickHouseClientTest` 4 例新增/`FunnelResultsServiceTest` 2 例新增+降级断言扩展，control-service 全量 2058 绿。CohortService 抛错保留——显式 calculate fail-loud 与读路径降级语义不同，不统一。三态统一/其余分析出参叠加=后续批次，字段向后兼容可随时叠加）
- [x] DATA-002 就绪状态·统一读口（2026-10-18【已代决=独立端点，替代逐出参散播】：新增 `GET /api/games/{gameId}/readiness`（`GameReadinessController`，game:read 行内鉴权）→ `GameReadinessService` 出 available + analysisLastEventAt（透传 `ClickHouseClient.analysisLastEventAt`，不发明新鲜度阈值）。**逐出参 fan-out 否决**：就绪度是 game 级属性，finance/crash/risk/prediction 等 ~15 个读出参各带一份既冗余又每调用加一条 CH 查询。采集/风控独立状态不编造——events 即端到端产物（Standard 档无 Flink 恒 null）；risk_events 以规则命中为前提（流量条件性）不能当链路存活信号，待真实信号（Kafka lag 导出等）接入后在本出参叠加。测试先行 5 例（service 3 + controller 2），control-service 全量 2063 绿，api-reference.md Games 表补行）
- [ ] DEPLOY-001 发布契约（属实，涉发版面：release workflow 改动需用户确认后动）
- [x] DEPLOY-002 运行边界声明（2026-10-18：MODES.md Production 节补 local executor 形态能力边界——无集群调度/自动恢复/水平扩容、checkpoint 仅进程内、无 restart 策略；集群化改造单列后续，不与本矩阵混同）
- [x] DR-001 备份离站失败可见性（脚本面，2026-10-18 整改：`postgres-backup.sh`/`backup-cronjob.yaml` 内嵌脚本/`clickhouse-backup.sh` 离站状态显式化——缺 aws CLI 或上传失败时本地备份+留存清理照常完成（a0c1b9f 降级意图保留），但结尾 exit 2 非静默失败（exit 0=本地+离站全成，1=本地校验失败，2=离站缺失），webhook 通知同步报 ❌；`BackupScriptTest` 10 例覆盖三态，control-service 全量绿）。**剩余外部件**：恢复演练的离站副本腿与 ClickHouse 腿仍需真实对象存储凭据与 CH 环境（PG+Redis 本地隔离腿 2026-10-19 已执行，见下条）；RPO/RTO 业务目标待用户拍板（审计亦要求缺目标记待决策、不编造）
- [ ] DR-001 恢复演练闭环（2026-10-19 R1019 本地隔离演练 PG+Redis 两腿完成：PG 用产线脚本本体在 postgres:16-alpine 容器内备份（exit 0，custom 格式，`pg_restore --list` 校验过）→ 全新空 PG16 容器 pg_restore → **102/102 表逐表行数与源库一致**、flyway 53/53 success 双库一致 → control-service bootRun 直连恢复库（`ddl-auto=validate`）32s 启动、health UP、零 ERROR；Redis 用 CronJob 同款 `redis-cli --rdb` 流式导出 → 新容器加载 → **43/43 键恢复**抽验逐键一致；流程与证据记入 `deploy/backup/backup-strategy.md` §Testing，并修正 Scenario 1 恢复步骤与 CronJob 实际产物（pg_dump custom→pg_restore）的对账。剩余外部件收窄：ClickHouse 恢复腿（本机无 CH 环境+Altinity CLI）+ 离站副本恢复（需真实对象存储凭据）+ 全栈 RTO/RPO 实测）
- [x] OBS-001 文件面缺口（2026-10-18：k8s 面补 gateway 抓取 job（`deploy/monitoring/prometheus.yaml` oddsmaker-gateway:80 /actuator/prometheus）+ GatewayDown 告警（`oddsmaker-alerts.yml`）；control 8086 抓取口经 k8s 清单核实正确（`control-service.yaml:18,162` MANAGEMENT_SERVER_PORT=8086）非笔误）
- [x] OBS-001 接线面·文件（2026-10-18【已代决=host-gateway 解耦】：`infra/prometheus.yml` 补 oddsmaker-control/gateway 两 job 抓 `host.docker.internal:28085/28080` 的 `/actuator/prometheus`（根 compose 业务栈已发布宿主端口；两 actuator 均与业务同端口、exposure 含 prometheus 已核 `control-service/application.yaml:170-174`、`gateway-service/application.yaml:11`），`infra/docker-compose.yml` prometheus 容器带 `extra_hosts: host.docker.internal:host-gateway`（需 Docker ≥ 20.10）——与业务栈分属两 compose project，不共享网络、不改根 compose、互不重启。验证：promtool check config 绿 + `docker compose config -q` 绿；`deploy/MODES.md` Production 观测段同步改写（原「接线不在矩阵声明内」表述作废）。**剩余外部件**：真机抓取验证（Production 宿主机实测 up{job="oddsmaker-control/gateway"}=1）+ 真实流量告警触发。**取证通道**：`demo-diagnose.yml` 新增 Prometheus 取证腿（2026-10-18，只读：`docker ps infra-prometheus` + `up` 全量 + `prometheus_tsdb_head_min_time` 历史回看起点）。**首测读数（run 39136336862，2026-10-18T14:31Z）**：Production/Demo 机上 infra 栈未部署（无 infra-prometheus 容器、127.0.0.1:9090 拒连；机上仅 demo 三件套）——真机验证的前置不是「重拉」而是「首次部署 infra 栈」（部署位置属用户/运维决策）；该时刻即业务指标积累与 ML-001 条件 a 的起点，读数见 todo.md ML 条目）
- [ ] GOV-001（账本收敛待用户拍板）/ SITE-001（与账本 502 条同源，等 CF 改源）/ ML-001（与账本 ML 条同源，已 BLOCKED）
- [x] DOC-001 作业口径统一（2026-10-18：`README.md:137,276`+`README.zh.md:137,276` 重写——7 模块=6 fatJar+dimension-sync（兼 agent 形态）、compose 7 容器（funnels 双跑、dimension-sync 不在 compose）；`deploy/MODES.md` 新增「作业计数口径」定义三口径；`README.md:217` 六 fatJar+agent 表述与该口径对齐核实无误）
