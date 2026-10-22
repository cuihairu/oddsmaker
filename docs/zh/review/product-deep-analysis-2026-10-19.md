# Oddsmaker 企业产品深度分析

> 审查日期：2026-10-19  
> 范围：产品交付、企业部署与运维、权限与合规、发布、生态差距。只读审查；未修改业务代码，未执行测试。  
> 证据口径：下文路径后的行号对应本次审查提交前的仓库版本；外部产品资料按官方页面核查于 2026-10-19。没有运行环境证据的能力明确标注“未核实”。

## 1. 执行摘要

Oddsmaker 当前真实定位是**单一游戏公司的游戏实时分析与风控平台**，不是赔率计算、体育赛事数据或博彩交易服务。README 将它定义为单公司部署、多游戏、多环境，并明确 game_id + environment 是数据隔离边界（README.zh.md:15-18,28-34）。Oddsmaker 名称与下注赔率行业的语义容易让采购方误判产品类别（README.zh.md:21-26），建议在首页、仓库简介和产品演示中增加直白副标题“游戏数据分析与风控平台”。

交付完整性结论：nightly Release 五个平台压缩包确实包含 Web 控制台静态文件（构建后复制到包的 web/，再随压缩包发布，.github/workflows/nightly-build.yml:216-217,261-263,342-349,489-501）。但标准 Quickstart 没有 Web/Nginx 静态服务，正式 Dockerfile 也未复制 web/dist；README 的 Quickstart 因此只能启动后端与依赖，不能据此获得可登录控制台（docker-compose.quickstart.yml:17-18,145-236；Dockerfile:72-85）。Demo 有独立构建与 nginx 发布流程（.github/workflows/deploy.yml:28-64,84-100,150-160）。**所以静态文件被打包，不等于控制台随镜像/Quickstart 可直接访问。**

做得好的部分：仓库具备可运行的多模块服务、五端 SDK、控制台、完整 CI 测试腿、带校验和与构建信息的 nightly 包、Docker 与 K8s 资源、RBAC、操作审计、监控配置和多数据库备份脚本。项目已有较强工程实现与修复记录；当前主要短板是这些能力没有收敛成一条清晰、可信、具备边界说明的企业交付路径。

## 2. 企业产品完整度盘点

| 采购维度 | 当前结论 | 仓库证据 / 边界 |
|---|---|---|
| 产品定位 | 已明确为单公司、多游戏、多环境；不是多企业 SaaS。 | README.zh.md:15-18,28-34。适合一家公司内的游戏/环境权限，不适合要求多个客户组织在一个实例内强隔离的托管服务商。 |
| 10 分钟上手 | 有 Quickstart 指令，未证明 10 分钟可完成端到端价值体验。 | README.zh.md:162-178 声称一条 Compose 命令拉依赖并检查健康；但没有控制台服务，依赖有 PostgreSQL、Redis、Kafka、Apicurio、ClickHouse，镜像需拉取且控制台需另找发布方式。docs/operations/startup.zh.md:5-40 记录的是 15 容器、本机 Podman/镜像加速与人工检查路径，并不是标准 Docker 10 分钟验收。耗时未核实。 |
| 安装形态 | 源码、单镜像、Compose、K8s YAML、Helm 均有相关入口；交付边界不统一。 | README.zh.md:133-151,162-213；docs/zh/operations/deploy.k8s.md:5-46。Nightly tar/zip 有服务端脚本；Quickstart 没 web；Demo web 单独部署。Helm chart 当前可部署性未核实。 |
| 配置与密钥 | 配置面较丰富，支持 env、配置模板及 K8s Secret；默认配置不能直接作为生产基线。 | services/control-service/src/main/resources/application.yaml:11-15,37-42,60-71,92-99 含开发口令/JWT 默认值，生产 fail-closed 开关默认 false；Quickstart 有 dev-admin-token、dev-internal-token、dev-secret 和 Superset admin/admin（docker-compose.quickstart.yml:168-169,216-217,245-250）。K8s Secret 文件明确是 CHANGE_ME 模板，部署文档要求首次 apply 前替换（docs/zh/operations/deploy.k8s.md:25-43）。 |
| 升级与回滚 | 有 Flyway 自动迁移、tag 镜像、K8s rollout 和回滚脚本；缺客户可用的兼容矩阵、升级前检查和数据迁移回退承诺。 | application.yaml:17-22,33-36；.github/workflows/release.yaml:164-213；deploy/k8s/rollback-release.sh。Nightly 固定 tag 滚动覆盖（README.zh.md:213,217）；README 当前版本 v0.2.0 (unreleased)（:270），Gradle 全模块仍设 0.1.0（build.gradle.kts:6-9），发布 tag 与 JAR 内版本一致性未核实。数据库迁移回滚能力未核实。 |
| 权限 / 多租户 | 应用内权限能力较完整：单公司边界下支持多游戏、环境范围和角色权限；无企业间租户隔离。 | README.zh.md:28-34；权限迁移包括 services/control-service/src/main/resources/db/migration/V0.2.3__rbac_permissions_roles.sql 与 V0.9.8__rbac_guard_permission_seeds.sql。K8s 部署指南仍建议控制面换强令牌或对接 OIDC（docs/zh/operations/deploy.k8s.md:41-46）。OIDC/企业 SSO 是否已可直接配置未核实。 |
| 审计日志 | 应用有结构化审计表与关键资源审计；保留策略、不可篡改性、审计导出/外送与企业合规验收未核实。 | V0.2.4__audit_logging.sql:4-36 建表及索引；CHANGELOG.md 有 API Key、环境、Game、RiskRule、导出等操作审计记录。表有 expire_at 索引，但实际保留/清理配置未核实。 |
| 可观测性 | 指标和告警具备；Trace/Logs 的应用级接线不完整。 | Prometheus 抓取 Control、Gateway、数据库、Kafka、Flink（deploy/monitoring/prometheus.yaml:18-95）；服务依赖 Actuator/Prometheus（services/gateway-service/build.gradle.kts:14,18；services/control-service/build.gradle.kts:9,24）。观测文档注明 OTel Agent 需手工加入、Flink Reporter 未集成（docs/zh/operations/observability.md:11-37）。K8s Control management 端口/探针已配置（deploy/k8s/control-service.yaml:17-19,55-110）。 |
| 备份与灾备 | PG、Redis、ClickHouse 脚本/策略和部分恢复演练记录存在；完整企业 DR 仍需环境侧闭环。 | deploy/backup/backup-strategy.md:14-28,96-124,285-315；K8s CronJob 有 PG/Redis 任务（deploy/k8s/backup-cronjob.yaml:101-149,204-220）。同一策略明确 ClickHouse 与对象存储链路未部署，恢复演练需在完整环境完成（:294）。PG/Redis 本地恢复演练记载于 :296-312。RPO/RTO 业务目标未见已签署值。 |
| 高可用 | 应用层有多副本/探针/滚动更新；有状态依赖是单副本，不能称为整栈 HA。 | Control replicas=2、滚动策略与资源限制（deploy/k8s/control-service.yaml:40-48,80-110）；PostgreSQL 与 Redis replicas=1（deploy/k8s/postgres.yaml:38-54；deploy/k8s/redis.yaml:20-35）。Flink 集群/多副本恢复边界需看 deploy/MODES.md。 |
| 容量规划 | 有性能调优、压测工具与 K8s requests/limits；没有采购可用的规模承诺矩阵。 | docs/zh/operations/perf-tuning.md、tests/load/README.md、deploy/k8s/control-service.yaml:80-86。每秒事件数、游戏数、查询 SLA 下的已验证容量未核实。 |
| 二进制/容器 | 有 Linux/macOS/Windows nightly 归档；容器镜像只含 Control/Gateway。 | nightly-build.yml:236-263,342-355；Dockerfile:72-85。Flink 六个 fatJar 与 Agent 在压缩包，运行仍需基础设施并需配置。 |
| 控制台 | 静态资源随 nightly 归档，但无标准镜像或 Quickstart 的静态服务。 | nightly-build.yml:261-263,475,498-501；Quickstart 没 web/nginx 服务（docker-compose.quickstart.yml:17-18,145-236）；Demo 由独立 deploy.yml 编译与发布（:28-64,150-160）。包内文件能否按公开说明连上正确 API URL 未核实。 |
| SDK | Web、Android、iOS、Unity、Server 源码及测试存在；nightly 归档未见 SDK 包发布。 | README.zh.md:133-145；CI 测试腿见 .github/workflows/ci.yaml:64-81,134-161；nightly 只打包服务端分发目录（nightly-build.yml:241-263）。各端制品仓/包管理器正式发布策略未核实。 |
| 文档 | 中英文 README、API、运维、灾备、排障、运行模式、竞品分析等文档量足；部分仍是旧路径/理想状态/建议语气，可能误导落地。 | README.zh.md:153-232；K8s 文档 docs/zh/operations/deploy.k8s.md:9-22 将 Helm 列为推荐。与 Quickstart 无 web、应用 OTel 未完整接线、备份链路未部署等存在边界差异。 |
| 演示数据 / Docker / K8s | Demo 账号及演示环境存在；Quickstart 有 Docker Compose；有 Raw K8s 与 Helm 入口；未确认发布包有演示数据装载路径。 | README.zh.md:155-160；docker-compose.quickstart.yml；docs/zh/operations/deploy.k8s.md:9-39。演示数据重置周期、部署包 seed 数据完整性未核实。 |

## 3. 主要问题（采购阻断项）

严重度定义：**高** = 可能直接阻止企业安全上线、影响数据/决策可信度或形成关键交付缺口；**中** = 需要客户自建流程或有明确运营风险；**低** = 可接受但影响采购体验/维护效率。

### 3.1 前五项最严重问题

1. **产品控制台没有成为默认交付路径**  
   **证据：** nightly 压缩包将 web/dist 复制到每个资产（.github/workflows/nightly-build.yml:216-217,261-263），而镜像仅复制 Control/Gateway JAR（Dockerfile:72-85），Quickstart Compose 只有基础设施、Control、Gateway、Superset，没有 web 或 nginx 服务（docker-compose.quickstart.yml:17-18,145-245）。实际 Demo 依赖另一条 workflow 构建并发布静态目录（.github/workflows/deploy.yml:28-64,150-160）。  
   **影响：** 采购方按“一条命令”启动后无法直接使用控制台，必须自己补 Web server、反向代理、SPA fallback、API/CORS 路由与升级同步；会被视为产品未打包完成。README 说 Web 随 release 发布，却没有给出静态文件服务方法。  
   **严重度：高。**

2. **Quickstart 与默认配置不适合生产，生产拒绝弱密钥不是默认行为**  
   **证据：** Control 默认 DB 密码 oddsmaker、Spring 用户 admin/admin123、管理 token admin、JWT 开发密钥（services/control-service/src/main/resources/application.yaml:11-15,37-42,60-71）；require-production-secrets 默认 false（:92-99）。Quickstart 中多项默认凭据可直接复用（docker-compose.quickstart.yml:25-26,168-169,216-217,245-250）。README 文案称 .env 复制为“可选”，但示例 admin token 仍是开发值（README.zh.md:166-170）。  
   **影响：** 用户容易把 healthy 误解为已完成安全配置；若把示例环境暴露到网络，管理 API、数据库和 BI 会面对可猜测凭据。K8s Secret 模板有提示替换，但只是人工约定，缺少默认 fail-closed 强制。  
   **严重度：高。**

3. **分析与风控降级状态对采购 SLA 的表达不足**  
   **证据：** Demo Compose 明确在 Kafka 缺席时风控/身份消费者持续重试，分析接口按 available=false 降级（deploy/demo/docker-compose.yml:6-10）；ClickHouse 留空时相关功能降级为不可用（application.yaml:107-111）。README 的核心能力包含实时分析、风险检测和处置（README.zh.md:38-57），但运行模式差异需另读 deploy/MODES.md。  
   **影响：** 采购方容易把 API 进程健康误认为实时数据链路和风控就绪；服务仍可登录但关键分析为空或风控处理不可用，难以设定生产 SLA 和安全策略。接口状态是否完整覆盖 Kafka lag、Flink 停机、ClickHouse 故障未核实。  
   **严重度：高。**

4. **“企业部署”示例不是整栈高可用，数据库是单点**  
   **证据：** K8s Control 有两副本（deploy/k8s/control-service.yaml:40-48），PostgreSQL 与 Redis 均单副本且 Recreate（deploy/k8s/postgres.yaml:38-54；deploy/k8s/redis.yaml:20-35）；部署指南称 K8s 为示例，并要求客户另接企业网关/WAF、OIDC、TLS（docs/zh/operations/deploy.k8s.md:1-7,41-46）。  
   **影响：** 数据库故障或升级会影响控制面和采集链；静态 K8s YAML 不提供数据库故障切换、跨区恢复、PDB/拓扑策略的完整承诺。要求持续可用或多可用区的客户需另行设计与验证。  
   **严重度：高。**

5. **灾备策略文档比仓库可部署的恢复闭环更完整**  
   **证据：** DR 文档记载 PG/Redis 本地恢复演练，但同一处明确 ClickHouse、WAL 归档/对象存储完整链路仍需在具备完整环境验证（deploy/backup/backup-strategy.md:285-315，特别是 :294）。K8s CronJob 覆盖 Postgres/Redis（deploy/k8s/backup-cronjob.yaml:101-149,204-220），没有证据表明该 CronJob 覆盖 ClickHouse。  
   **影响：** 不能把“有备份文档/脚本”当成可采购的 RPO/RTO。分析事件与派生数据恢复方式、跨站点保存、定期恢复验收及责任边界仍需实施方补齐。  
   **严重度：高。**

### 3.2 其他必须改进的问题

6. **产品文案与私有部署现实不一致**  
   **证据：** README Quickstart 以“推荐”、一条命令和“全部 healthy 即就绪”呈现（README.zh.md:162-178），但只验证健康端点，没有控制台；K8s 文档推荐 Helm（docs/zh/operations/deploy.k8s.md:9-23），又要求客户接企业网关、TLS、OIDC 等（:41-46）；观测文档写明 Trace/Flink OTel 需人工接线（docs/zh/operations/observability.md:11-37）。  
   **影响：** 客户 PoC 后才发现所谓完成只代表服务进程启动，造成售前承诺与交付范围争议。应区分本地试用、单机演示、生产基线，并标注不含项。  
   **严重度：中。**

7. **版本、nightly 与升级策略混用**  
   **证据：** README 宣称 nightly tag 日更/主干推送更新（README.zh.md:213,217）；同一文档当前版本 v0.2.0 (unreleased)（:270），Gradle 全模块版本仍为 0.1.0（build.gradle.kts:6-9）；Release workflow 从 v* tag 生成镜像 SemVer tag（.github/workflows/release.yaml:30-43,98-147）。  
   **影响：** 客户无法仅凭安装产物判断代码版本、支持周期、兼容范围和修复来源；固定 nightly 被覆盖，不适合生产可复现部署。Release tag、BUILD_INFO、JAR manifest 和数据库 schema 一致性未核实。  
   **严重度：高。**

8. **依赖安装存在非锁定回退，第三方风险治理证据不足**  
   **证据：** Web SDK CI/nightly 用 npm ci || npm i（.github/workflows/ci.yaml:72-81；.github/workflows/nightly-build.yml:128-137），锁文件安装失败时回退到可能更新依赖的安装。仓库未检索到 .github/dependabot.yml、Renovate、SBOM/依赖漏洞扫描工作流或发布签名/来源证明；可见工作流主要为 ci、nightly、release、deploy、docs。Java/JS/Python 依赖清单与部分锁文件存在，但无统一漏洞处置 SLA 证据。  
   **影响：** 同一提交重跑可能解析不同依赖；企业安全审查无法快速取得软件组成、传递依赖许可证和漏洞状态。这是治理缺口，**不等于已证实存在可利用漏洞**。  
   **严重度：高。**

9. **License 有声明，尚非采购级合规材料包**  
   **证据：** 根 LICENSE 是 MIT（LICENSE:1-20），README badge 同样标示 MIT（README.zh.md:9-11）；未检索到随 Release 提供的 SBOM、第三方 notices/许可证汇总或数据处理/隐私责任说明。  
   **影响：** MIT 只说明主仓授权，不自动说明打包依赖、SDK、基础镜像和模型/训练数据的再分发义务。企业法务会要求组件清单、许可证义务和数据责任说明。外部组件许可证兼容性未核实。  
   **严重度：中。**

10. **测试覆盖面广，但单测通过不是客户链路验收，覆盖率也没有门槛**  
    **证据：** CI 分别运行 Java 全模块、Web SDK、ML、Web Console、Android SDK 测试（.github/workflows/ci.yaml:46-55,64-81,83-102,104-161），nightly 也运行 Web Console tests/build（.github/workflows/nightly-build.yml:91-95）。根 Gradle 只生成 JaCoCo 报告，没有覆盖率阈值校验（build.gradle.kts:30-43）。Nightly 包构建阶段的 web 复制失败会被 || true 忽略（nightly-build.yml:261-263），因此可能继续生成缺 UI 的包。真实 PostgreSQL E2E 由 -Drisk.pg.e2e=true 门控（jobs/flink/risk-job/src/test/java/io/oddsmaker/jobs/risk/RiskFeaturePgE2eTest.java:32-40；services/control-service/src/test/java/io/oddsmaker/control/service/RiskDecisionPgE2eTest.java:32-43）；Kafka/Registry broker E2E 在依赖不可达时使用 assume 跳过（services/gateway-service/src/test/java/io/oddsmaker/gateway/kafka/PublishersBrokerE2ETest.java:69,233）。  
    **影响：** CI 绿灯不能推导升级、真实 Kafka/Flink/ClickHouse 链路与备份恢复均已验证；核心静态资源在分发包缺失也可能不阻止 Build job。  
    **严重度：中。**

11. **权限成熟度与身份集成成熟度不等价**  
    **证据：** 项目按单公司多游戏隔离设计（README.zh.md:28-34）；K8s 部署文档将 OIDC 作为对接建议，而非可复制的已配置步骤（docs/zh/operations/deploy.k8s.md:41-46）。RBAC 与审计代码不自动代表有 LDAP/SCIM、MFA、用户生命周期自动化或外部身份源完整支持。  
    **影响：** 有 SSO、离职账号自动禁用、审计集中送 SIEM 要求的企业，需要明确自研还是集成边界；共享实例多法律主体隔离不在当前定位内。  
    **严重度：中。**

12. **错误降级可能掩盖异步链路持续故障**  
    **证据：** Demo 缺 Kafka 时消费者持续重试，分析 API 降级（deploy/demo/docker-compose.yml:6-10）；Gateway data-quality reporting 在未配 control token 时静默关闭（services/gateway-service/src/main/resources/application.yaml:45-47）。这些是有意降级，但产品级告警是否区分“可选能力未配置”和“生产依赖故障”未核实。  
    **影响：** 运维人员可能看到 HTTP 服务健康，却错过事件积压、风险消费者停滞或治理快照未送达；依赖不可用时的风控 fail-safe/fail-open 策略应按业务明确。  
    **严重度：中。**

## 4. 同类开源产品对照

选择 **Matomo On-Premise**（成熟自托管分析产品）和 **PostHog self-host**（开源产品分析平台）作为企业采购流程参照。两者均非游戏风控一比一替代品；比较的是采购方会审查的自托管交付与治理成熟度。

外部证据：Matomo 官方安装页给出 5 分钟安装向导、主机/数据库前置条件、初始 superuser 与用户权限管理（[安装指南](https://matomo.org/faq/on-premise/installing-matomo/)）；官方自托管手册覆盖安装、升级、安全、性能和规模化架构（[On-Premise 指南](https://matomo.org/guide/installation-maintenance/matomo-on-premise-self-hosted/)）；按站点分配 view/write/admin 权限有单独文档（[权限说明](https://matomo.org/faq/general/faq_69/)）。Matomo Releases 页面显示持续发布和多个活跃版本线（[Release 页面](https://github.com/matomo-org/matomo/releases)）。PostHog 官方自托管文档说明 Docker Compose、安装/升级脚本，同时明确 self-host 不提供正式支持/服务保证、无固定 tag 版本（[Self-host 文档](https://posthog.com/docs/self-host)）；仓库页面显示其社区规模较大（[GitHub 仓库](https://github.com/PostHog/posthog)）。

| 维度 | Oddsmaker | Matomo On-Premise | PostHog Self-host |
|---|---|---|---|
| 部署形态 | Docker Compose、K8s YAML、Helm 和多平台 nightly server archive；web 静态文件与后端部署分离，Quickstart 无静态站点入口。README.zh.md:162-217；nightly-build.yml:261-263 | Web server + DB 安装向导，官方 Docker 选项和云部署文档；官方自称有 5 分钟安装路径。仍需满足 PHP/DB/web server 条件。 | 官方 Docker Compose hobby 部署与脚本式升级；自托管需要客户负责基础设施和扩容，官方称此版本不受支持/无保证。 |
| 权限模型 | 单公司实例、游戏/环境 scope、应用 RBAC；OIDC 仅为接入建议，企业组织隔离不支持。README.zh.md:28-34；deploy.k8s.md:41-46 | 全局 superuser + site-level view/write/admin 权限，网站维度委派清晰。 | 产品自身用户和组织能力丰富；self-host 版本/支持边界需按当前政策核对，不能推断 SaaS 企业版保证同等适用。 |
| 监控集成 | Prometheus、Grafana、告警配置已有；应用 traces 和 Flink OTel 需手工接线。observability.md:11-37 | 官方运维资料有安装维护、安全、性能和可扩展部署指南；具体监控栈通常由部署者集成。 | 可观测性产品能力丰富；自托管方需承担扩容与运维。文档建议始终最新镜像，不具固定版本承诺。 |
| 文档质量 | 中文资料较全、细节多，但试用、生产、已实测、待集成边界散落多处，存在推荐路径与真实组成不一致。 | 按安装/升级/安全/扩容分类，采购者容易评估前置条件和运维责任。 | 自托管责任边界明确，但官方直接声明无支持保证；同时提供安装/升级命令。 |
| 社区活跃 / 版本 | 仓库 nightly 活跃，但 nightly 覆盖式 tag；长期支持与企业支持渠道未核实。 | Releases 可见稳定/预发布持续更新，公开仓库有成熟 issue/PR 活动。 | GitHub 页面显示约 39.8k stars、3.4k forks 与大量提交；社区规模不等于 self-host 支持承诺。 |

**最大差距：**不是功能项数量，而是**可验证的产品化交付契约**。Matomo 把安装、首次管理员、升级、安全、扩容拆成可执行客户文档；PostHog 即便自托管不受支持，也公开客户责任和版本策略。Oddsmaker 提供代码与运维文件，但采购方仍需自行发现 UI 如何部署、哪些功能仅降级、K8s 数据库 HA 边界、怎样升级与恢复。差距首先是“边界讲清楚并且交付即闭环”，然后才是增加功能。

## 5. 三期改进路线

工作量按 1 人周粗估，含文档/配置/实现与可审查产物，不含客户环境审批和供应商等待；估算为建议值，不是实测。

### 一期：1–2 周，补采购现场一眼可见的硬伤

| 动作 | 预期收益 | 粗估 |
|---|---|---:|
| 将 Quickstart 拆成“安全本地试用”和“生产前检查”；本地 profile 明示演示凭据与不暴露公网；生产 profile 强制非默认密钥并检查 .env。 | 防止用户把测试栈当生产栈，减少首次安全审查阻断。 | 2–3 人日 |
| 给 Quickstart 加官方 web 静态服务/反代，或提供明确的 Compose/Nginx 发布包；让资产内 web/ 有可执行 serve 指南，打包缺失时失败。 | 登录控制台成为真正的一键路径；解除“包里有但无法访问”。 | 3–5 人日 |
| 加 quickstart → 登录 → 建游戏/环境 → SDK 发事件 → Live Inspector/指标可见的短路径，附可复位 demo seed 和健康/链路状态说明。 | 采购 PoC 可在可控时间形成完整体验，避免只看 actuator health。 | 3–5 人日 |
| 建立版本事实表：tag、镜像 digest、BUILD_INFO、JAR version、Flyway schema 范围；生产说明不推荐 nightly，提供固定版本与回滚步骤。 | 增强可追踪和审计，减少升级时运行版本争议。 | 2–4 人日 |
| 移除 npm ci || npm i 回退；Release/nightly 增加压缩包内容校验，并发布依赖 SBOM 与许可证清单初版。 | 构建可复现、交付缺文件更早失败，安全审查有基础证据。 | 3–5 人日 |
| 修正首页产品副标题及“部署后可用能力”表格，标记已验证、需外部服务、未集成、降级状态。 | 降低品类误解和售前超范围承诺。 | 1–2 人日 |

**一期收益：**让客户按文档安全启动、进入 UI、验证一条事件链，并知道获得了什么、还缺什么。整体约 2 周并行可交付，建议排成 1–2 周小迭代。

### 二期：需要设计的企业能力建设

| 动作 | 预期收益 | 粗估 |
|---|---|---:|
| 定义支持部署矩阵：单机/单集群/外部托管数据库；每种模式的容量范围、依赖、SLO、升级和责任边界。 | 让采购架构评审有支持矩阵，而非把示例 YAML 当 SLA。 | 2–3 人周 |
| 为 PG/Redis/Kafka/ClickHouse 确定 HA/备份拓扑与恢复优先级；将 RPO/RTO 作为客户目标，再做重复恢复演练。 | 从“有脚本”升级到可签署、可验收的灾备能力。 | 4–8 人周，环境另计 |
| 设计统一业务健康模型：进程、依赖、事件摄入、消费 lag、分析新鲜度、风险处置延迟分别暴露并关联 runbook。 | 客户能区分服务健康与业务链路健康。 | 3–5 人周 |
| 设计企业身份接入与审计交付：OIDC/LDAP（拍板后）、用户生命周期、审计留存/导出/SIEM。 | 满足集团身份治理、安全审计和留存要求。 | 4–8 人周，依赖身份方案 |
| 建立正式发布政策：支持窗口、补丁级别、版本兼容矩阵、数据库迁移前置检查/回滚限制、签名与来源证明。 | 快速迭代与企业稳定性可并存。 | 3–6 人周 |
| 做容量基准：按事件大小、游戏数、分区、查询并发建负载模型，公布硬件规格/吞吐/延迟/保留区间。 | 采购可把预计用量转为基础设施规模。 | 3–6 人周，环境和调优另计 |

### 三期：战略级取舍

| 决策/动作 | 建议 | 理由 | 粗估 |
|---|---|---|---:|
| 明确是单公司自托管游戏数据平台，还是多客户 SaaS/托管产品。 | 近期建议坚持单公司自托管，不做共享多租户 SaaS。 | README 已将 game_id + environment 定为内部边界；跨公司租户涉及租户级密钥、数据库隔离、计费、运营、支持和合规，不是简单加 tenant_id。 | 决策 1–2 周；改变方向至少数月级设计 |
| 是否做实时赔率/体育博彩系统。 | 建议不做，除非核心客户明确提出且愿承担独立产品战略。 | 当前实现主线是游戏分析、实验、风控；赔率业务引入赛事数据授权、交易与限额、结算、监管辖区、责任博彩等不同领域，名称不应被当成需求证据。 | 决策 1 周；实现不估 |
| 是否自建完整 Flink/Kafka/ClickHouse HA 与托管控制面。 | 建议近期不承诺托管所有依赖，先验证外部依赖集成和运行边界。 | 会扩大 on-call、升级、容量及数据一致性责任；团队与支持承诺未核实。 | 评估 2–4 周；平台化数月级 |
| 是否提供商业支持、LTS、SLA 与发行版。 | 若目标为企业采购，建议先做有限支持版和明确响应边界，不要只提供无承诺的 nightly。 | 开源可用与客户生产保障是不同产品；需要明确谁负责故障与补丁。 | 商业/运营决策 2–4 周 |
| 是否接入特定 IdP、云、SIEM、对象存储。 | 不泛化支持所有厂商；按目标客户选 1 个主 IdP、1 个云对象存储、1 个告警/SIEM 接口。 | 集成矩阵越宽，兼容和支持成本越高；需客户证据排序。 | 每个集成 3–8 人周 |

## 6. 必须由用户拍板的事项

1. **商业定位：**仅自托管单公司游戏数据平台，还是要提供托管/SaaS？是否接受多公司共享实例需求？本报告建议暂不改变单公司边界。
2. **品牌与品类：**保留 Oddsmaker 名称并加“游戏分析与风控”副标题，还是更名以消除博彩/赔率误解？
3. **License 与商业模式：**是否继续 MIT；是否需要商业发行版/支持合同；谁承担组件许可证复核、SBOM 和安全修复责任？
4. **身份集成：**目标客户必须支持哪个 IdP（OIDC/SAML/LDAP/Keycloak 等）？确定前无法承诺企业 SSO/SCIM。
5. **生产保障：**目标 RPO、RTO、可用性、支持时段/响应时间是什么？没有业务目标不能给恢复和 HA 设计定验收值。
6. **部署依赖：**客户是否接受托管 PostgreSQL/Redis/Kafka/ClickHouse？若全自管，优先支持哪些 Kubernetes/云/对象存储？
7. **风控策略：**依赖不可用时应 fail-open、fail-closed 还是按规则类别配置？这是业务安全取舍，不能由工程默认推断。
8. **发布策略：**是否需要 LTS 和受支持版本窗口？是否把 nightly 限为演示/预览用途？
9. **竞品集成：**是否要求 Superset、Metabase、Grafana、SIEM、邮件/Webhook 中某个集成的正式兼容承诺？

## 7. 审查边界与未核实项

- 本次没有运行服务、Compose、K8s 集群或 CI，也没有重新执行测试；结论基于源码、配置和文档静态证据。
- 未核实生产环境真实资源、可用区、扩容、SLO、监控告警是否已由部署方接线。
- 未核实所有第三方许可证兼容性、已知漏洞状态、SBOM 和外部密钥/证书轮换流程。
- 未核实 OIDC/SSO、审计日志保留清理、备份加密和异地恢复在客户环境的实际状态。
- 外部对照仅用于产品化和采购流程比较，不表示功能范围等价；尤其 PostHog 官方当前明确自托管缺少正式支持保证。

