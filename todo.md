# TODO

需求清单：一条一句；完成项留一行证据（commit + 测试数）。重构契约 = `docs/zh/redesign/06-od2-restructure-plan.md`。

## 开放需求

- [x] docs 内部链接指向一致性（P2，P1016AM 挂账后续项，R2PATROL 完成）：兄弟页内部链接改指同语言对应页——4 处真 404（`docs/reference/api-reference.md` 与其 `.zh.md` 兄弟各 2 处指向英文 locale 下从未存在的 `/reference/dimension-sync`；英文侧改指 `/zh/reference/dimension-sync.en`、中文侧改指 `/zh/reference/dimension-sync`；该设计文档自 9ba45f9 起只在 `docs/zh/reference/`）+ 12 处中文兄弟页静默解析到英文基名（`docs/reference/index.zh.md` 8 处、`docs/operations/index.zh.md` 4 处 → 同目录 `.zh` 兄弟）。理由/影响面/推翻方式见 docs/决策记录.md。验收：全仓 46 条路由链接 + 140 条相对 `.md` 链接 0 失效；三个被改页对结构计数一致（行数/标题/表格/围栏）+ 代码块逐字节相同 + 切换器双向对称不变；围栏内链接未动；`pnpm docs:build` 绿

- [x] docs 英文 locale 正文语言缺口（P2，PATROL016 巡检发现，P1016AM 完成）：英文 locale 下 18 页正文仍是中文（子目录 14 + 根目录 4：competitive-analysis、mmp-attribution-evaluation、决策记录、审计-文档一致性）→ 与其已有 `.en.md` 译版**互换**（基名改英文、中文原件移同目录 `.zh.md`），零新增翻译。正本裁断**已代决**（P1016AM）：英文 locale 槽位以英文为正本、中文归 `.zh.md`；`docs/zh/**` 分叉文档一律不动（理由/影响面/推翻方式见 docs/决策记录.md）。验收：全仓 EN locale 页正文无中文残留（仅余厂商专名 `research/mature-products-survey.md` 与文件名引用 `审计-文档一致性.md` 两类允许项，均代码块/专名规则内）；每个基名页都有 `.zh.md` 兄弟（`index.md` 与 `zh/index.md` 为跨 locale 首页配对）；36 文件互换后切换器双向对称、`pnpm docs:build` 绿

- [x] docs 子目录双语配对（P2，2026-10-16 执行单登记，PATROL016 完成）：52 个 .md 全配——51 个新增兄弟文件（中文源 45 个补 `.en.md`、英文源 6 个补 `.zh.md`）+ `zh/index.md` 已随根目录轮配对；51 个源文件各插 1 行切换器。配对方式**已代决**（PATROL016）：同目录兄弟文件而非 locale 镜像（locale 同名文件非互译，差异 164/198/738 行，见 docs/决策记录.md）。验收：59 对全部通过结构计数一致（行数/标题/表格/围栏）+ 代码块逐字节相同 + 切换器第 3 行且双向对称；英文侧正文无中文残留（仅切换器「中文」二字、代码块内原样注释、厂商专名如 友盟+/神策）；`pnpm docs:build` 绿。顺带按对账规则修 06 §4.1 笔误（「未携带废弃字段」→「携带废弃字段」，与 §3.3.3 及 schema `additionalProperties:false` 对齐，中英同批）

> R1015F 代决（用户可推翻）：以下两条维持卡点、不重启；2026-10-11 parked 拍板项（provider+token_ref、HarmonyOS SDK）维持 parked。

- [ ] Debezium CDC 维度同步：等真实游戏方开 binlog 并部署 Kafka Connect 后排期，消费端复用 dimension-sync-job 事件入口
  - **卡点类型**：外部输入缺失——仓内无可推进前置项，属「等外部基建」而非「等时间」
  - **解锁条件**：游戏方开 binlog + 部署 Kafka Connect + 提供 CDC 事件格式与目标表映射（三者齐备即开工）
  - 复核留痕（2026-10-15）：仓内 debezium/Kafka Connect 检索零信号（命中全为文档 + `DimensionSyncJobTest` 一处方法名）；演示栈无 Kafka、无 gateway
  - 复核留痕（2026-10-16）：解锁条件仍不成立——`grep -rl "binlog" deploy/` 零命中；`kafka.connect|connect-api|debezium` 全仓零命中（仅 `DimensionSyncJobTest` 一处方法名）；演示栈 3 容器（control/postgres/redis）无 Kafka。三项解锁件（游戏方 binlog、Kafka Connect、CDC 事件格式与目标表映射）仍为外部输入缺失，维持卡点
  - 复核留痕（2026-10-16 PATROL016）：无变化——`binlog` 在 `deploy/` 仍零命中；`kafka.connect|connect-api|debezium` 全仓仍仅 `DimensionSyncJobTest` 一处方法名；本机无任何 oddsmaker 容器在跑。三项解锁件仍为外部输入缺失，维持卡点
  - 复核留痕（2026-10-16 R2PATROL）：无变化——`grep -rl binlog deploy/` 仍零命中；`kafka.connect|connect-api|debezium` 全仓仍仅文档 + `DimensionSyncJobTest` 一处方法名。demo 栈今有 3 容器在跑（control/postgres/redis，均 healthy、已 3 天），但仍无 Kafka、无 CDC 事件格式与目标表映射文件，三项解锁件仍为外部输入缺失，维持卡点
  - 复核留痕（2026-10-18 PATROL1018A）：无变化——`grep -rl binlog deploy/` 零命中；`kafka.connect|connect-api|debezium` 全仓仍仅 `DimensionSyncJobTest` 一处方法名；`deploy/demo/docker-compose.yml` 服务面（postgres/redis/control-service）无 Kafka、无 CDC 组件。三项解锁件仍为外部输入缺失，维持卡点
  - 复核留痕（2026-10-18 PATROL1018B）：无变化——`grep -rl binlog deploy/` 零命中；`kafka.connect|connect-api|debezium` 全仓仍仅 `DimensionSyncJobTest` 一处方法名（1018A 同日复核，期间无新提交）。三项解锁件仍为外部输入缺失，维持卡点
  - 复核留痕（2026-10-18 PATROL1018R1）：无变化——同日第三巡，仓内零新提交（HEAD=cdab480 同步）、binlog 零命中。三项解锁件仍为外部输入缺失，维持卡点
  - 复核留痕（2026-10-18 PATROL1018 第四巡）：无变化——R1 后零新提交、binlog 零命中，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 PATROL-R1018 第五巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 PATROL-R1018C 第六巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第七巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第八巡 r1018）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第九巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第十巡 patrol-sweep）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第十一巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第十二巡 PATROL1018A）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第十三巡 PATROL1018）：无变化，证据同上轮。维持卡点
- [ ] Demo 站公网 502（交付面故障，PATROL1017 发现）：源站全链路已证健康（on-box 与公网直连均 200），502 定位为 Cloudflare 回源配置（用户侧 CF 面板/Aliyun 安全组），源站与仓内无可修项
  - **卡点类型**：用户侧配置缺失——CF 回源链（CF 面板内 origin A 记录指向 / SSL 模式 / Aliyun 安全组对 CF 边缘 IP 段的入站放行）不正确，须 CF/Aliyun 控制台凭据才能动，仓内与部署机均无可操作项
  - **解锁条件**：用户在 CF 面板把 `oddsmaker.cuihairu.site` 的 origin A 记录指到部署机现公网 IP `106.14.216.177`（保持橙云代理）——这是唯一剩余解锁动作：安全组/境外拦截假说已被境外视点探针排除（见 PATROL1017C 留痕），LE 证书有效至 2027-01-01、80/443 双栈从境内与境外实测均 200，无第二分支。CF 侧操作属用户侧（见边界「CF DNS 域名切换属用户侧操作，本仓不动」）
  - 复核留痕（2026-10-17 PATROL1017）：发现——公网 `GET /` 与 `/api/*` 均 `error code: 502`（http:80 同样 502；非 521/522，说明 CF 侧拿到的是无效源站响应而非连接拒绝，故障面在源站 nginx/证书/回源配置）；而 Deploy Demo run 38844622658（R1025 rerun）与手动触发 38931937869 均 success、on-box `curl 127.0.0.1:38085/actuator/health`=UP、镜像已滚至 digest sha256:a99f2c9a…，手动重部署后 60s 再探仍 502 ×3。已证本机非部署机（无 `/opt/oddsmaker`；本机 `oddsmaker-demo-*` 为本地测试遗留栈），当时结论「无部署机访问通道」
  - 诊断留痕（2026-10-17 PATROL1017B）：故障定位完成，源站无罪——① 上轮「无部署机访问通道」结论修正：部署机上 self-hosted runner 本身就是受控通道，新增只读诊断工作流 `.github/workflows/demo-diagnose.yml`（拍板见决策记录 PATROL1017B）上机取证：nginx active、:80/:443 双栈监听、compose 栈 3 容器 healthy、control 直连 UP、README §3 回源自查 :80 带 Host=200、:443=200、LE 证书 CN=oddsmaker.cuihairu.site 有效至 2027-01-01；② 本席公网直连源站 IP `106.14.216.177` 带 Host 同法 :80=200、:443=200（对照组 cockpit :80=301 同样健康）；③ 兄弟域名 DNS 姿态给出分野：cockpit/wingman 灰云直解 `106.14.216.177` 公网 200，coding 灰云直解但 502（其 vhost 自身上游死，独立问题、非本仓范围），唯 oddsmaker 橙云走 CF 代理且 502。对照 README §3 判别式「CF 报 502 而这里 200，就是 DNS 或 SSL 模式没配对」：源站与仓内零可修项，剩余动作用户侧 CF 面板/Aliyun 控制台，维持卡点待用户操作
  - 诊断留痕（2026-10-17 PATROL1017C）：解锁分支收敛为一——demo-diagnose 增第二条腿 `foreign-vantage`（GitHub-hosted runner，境外 DC，与 CF 边缘同类入站路径；run 38949053623 双 job 绿）直探源站 `106.14.216.177`：:80 带 Host=**200**、:443=**200**、cockpit 对照=301，同 run 公网经 CF 仍 502。「安全组拦截境外/CF IP 段」假说排除——源站对境外入站完全开放，且此前 CF origin 若指向本 IP 本应通。剩余唯一原因：**CF 面板内 oddsmaker 的 origin 记录指错**（大概率沿用部署机旧 IP），解锁动作收敛为用户在 CF 面板改一条 A 记录指向 `106.14.216.177`
  - 复核留痕（2026-10-18 PATROL1018A）：无变化——公网经 CF `GET /` 与 `/actuator/health` 仍 502（http:80 与 https:443 双栈同 502），唯一解锁动作（用户在 CF 面板把 origin A 记录改指 `106.14.216.177`）仍待用户操作，源站与仓内无可修项，维持卡点
  - 复核留痕（2026-10-18 PATROL1018B）：无变化且源站今日再证健康——demo-diagnose run 39052955219 双腿绿：上机腿（ali）compose 3 容器 healthy、control 直连 UP、nginx active、:80 带 Host=200、:443=200、证书 CN=oddsmaker.cuihairu.site 有效至 2027-01-01（日志中 `nginx -t` 报 cockpit 证书 Permission denied 系 runner 非 root 取证噪音，nginx 服务本身 active 正常出 200）；境外腿（GitHub runner，与 CF 边缘同类入站）直探 `106.14.216.177` :80=200、:443=200、cockpit 对照=301、同视点公网经 CF 仍 502。CF origin 记录指错为唯一剩余原因今日复现成立，解锁动作不变（用户改 CF origin A 记录），维持卡点
  - 复核留痕（2026-10-18 PATROL1018R1）：无变化——公网 https/http 仍 502，用户侧 CF 动作未见；源站健康同日已由 run 39052955219 双腿再证（见上条），本轮不重复触发 CI 取证。维持卡点，解锁动作不变
  - 复核留痕（2026-10-18 PATROL1018 第四巡）：无变化——公网仍 502，证据同上轮。维持卡点，解锁动作不变
  - 复核留痕（2026-10-18 PATROL-R1018 第五巡）：无变化——公网仍 502。维持卡点
  - 复核留痕（2026-10-18 PATROL-R1018C 第六巡）：无变化——公网仍 502。维持卡点
  - 复核留痕（2026-10-18 第七巡）：无变化——公网仍 502。维持卡点
  - 复核留痕（2026-10-18 第八巡 r1018）：无变化——公网仍 502。维持卡点
  - 复核留痕（2026-10-18 第九巡）：无变化——公网仍 502（首轮探针 000 经对照甄别为本机出口单次抖动：cockpit 301 / cloudflare 200 / github 200，重试即 502）。维持卡点
  - 复核留痕（2026-10-18 第十巡 patrol-sweep）：无变化——公网仍 502。维持卡点
  - 复核留痕（2026-10-18 第十一巡）：无变化——公网仍 502。维持卡点
  - 复核留痕（2026-10-18 第十二巡 PATROL1018A）：无变化——公网仍 502。维持卡点
  - 复核留痕（2026-10-18 第十三巡 PATROL1018）：无变化——公网仍 502。维持卡点
- [ ] ML 异常检测进评估批次：任一触发条件成立即开工——指标历史可回看 7 天以上且 7d/28d 分位基线可算；单游戏阈值告警规则月均调整 3 次以上；告警误杀率超 30% 持续 2 周
  - **卡点类型**：触发条件未成立——条件 (a) 非等待可解，需先补部署面
  - **解锁条件**：四项触发条件任一达标；其中条件 (a) 需先有**带网关流量的部署面**，并自指标开始落库起算满 **7 天**（7d/28d 分位基线方可算）
  - 复核留痕（2026-10-15）：演示库 health_metrics / data_quality_metrics / metric_alert_rules 三表皆 0 行、最早指标日期为无；health_metrics 无产线写入方（`collectMetric` 仅模拟开关路径、默认关），data_quality_metrics 需网关真实流量而演示栈无 gateway
  - 复核留痕（2026-10-16）：解锁条件仍不成立——演示栈实跑 3 容器（control/postgres/redis，无 gateway、无 Kafka），指标历史全空：health_metrics/performance_metrics/ml_model_metrics/metric_alert_rules 皆 0 行、health_metrics 最早日期为无（data_quality_rules=3、health_checks=4 为规则定义与服务探活，非指标历史）；更正 10-15 留痕表名笔误：库中无 data_quality_metrics 表，结论不变。无网关流量部署面则指标不落库，条件 (a) 的 7 天起算无从开始，维持卡点
  - 复核留痕（2026-10-16 PATROL016）：无变化——本机无 oddsmaker 容器在跑（部署面未起，指标历史无从新增），四项触发条件仍无一达标，维持卡点
  - 复核留痕（2026-10-16 R2PATROL）：解锁条件仍不成立，但前提有更新——demo 栈今有 3 容器在跑（control/postgres/redis，均 healthy、已 3 天），指标表仍全空：health_metrics / performance_metrics / ml_model_metrics / metric_alert_rules 皆 0 行（health_metrics 0 行故无 collected_at 区间），`data_quality_metrics` 表中无此表（与 10-15 更正一致）。本机唯一 gateway 容器 `spawn-api-gateway-1` 属无关 compose 项目 `spawn`（2026-10-12 创建），非 oddsmaker 网关，不构成「带网关流量的部署面」；无 Kafka。条件 (a) 的 7 天起算无从开始，维持卡点
  - 复核留痕（2026-10-18 PATROL1018A）：无变化——`deploy/demo/docker-compose.yml` 服务面（postgres/redis/control-service）无 gateway，本机运行栈 3 容器（control/postgres/redis）均 healthy；指标表 health_metrics / performance_metrics / ml_model_metrics / metric_alert_rules 皆 0 行、health_metrics 最早 collected_at 为无。条件 (a) 无部署面故 7 天起算无从开始，四项触发条件仍无一达标，维持卡点
  - 复核留痕（2026-10-18 PATROL1018B）：无变化——demo compose 服务面仍无 gateway；指标表 health_metrics / performance_metrics / ml_model_metrics / metric_alert_rules 皆 0 行（1018A 同日复核，期间无新提交）。维持卡点
  - 复核留痕（2026-10-18 PATROL1018R1）：无变化——demo compose 仍无 gateway、health_metrics 0 行（同日第三巡，期间无新提交）。四项触发条件仍无一达标，维持卡点
  - 复核留痕（2026-10-18 PATROL1018 第四巡）：无变化——demo 无 gateway、指标 0 行，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 PATROL-R1018 第五巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 PATROL-R1018C 第六巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第七巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第八巡 r1018）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第九巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第十巡 patrol-sweep）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第十一巡）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第十二巡 PATROL1018A）：无变化，证据同上轮。维持卡点
  - 复核留痕（2026-10-18 第十三巡 PATROL1018）：无变化，证据同上轮。维持卡点

## 已完成需求

- [x] ruleConditions 引 EventSchema 字段（P2，**已代决** FW1016D 2026-10-16，契约行 74；用户可推翻）：FEATURE 条件 feature 名合法取值域 = risk-job 计算面特征登记表（`RiskFeatures` 两侧清单 6 特征），写路径未知名显式拒绝；不做 EventSchema 交叉校验（特征名是「信号 × 窗口」派生量、非事件契约字段）。理由/影响面/推翻方式见 docs/决策记录.md。验收：两侧清单常量齐备且测试钉同一份名单、未知名写路径拒绝测试绿、JSON 形态/列/存量行/读路径跳过语义/既有指标名全不变 ✅（5c501aa：control-service 174 suites/2039 例 0 失败、risk-job 10 suites/99 例 0 失败；顺带修 control.md FEATURE 示例 `threshold`→真源 `value`）
- [x] Signal 显式信号清单（P2，重构契约 §2.4「2.0 动作」代决开工）：按契约枚举 gold_gain / login_ip / device / … 显式声明（snake_case 沿用仓内惯例），只补显式声明与文档，**不改既有暴露面**（序列化格式与既有指标名不变）；control-service 与 Flink risk-job 两侧同批对齐。验收：两侧清单常量齐备且被测试钉住、契约文档行同步、相关测试全绿 ✅（7602a72：两侧 `RiskSignals` 9 信号清单 + 各 3 例测试同钉 `DOCUMENTED` 名单；契约 §2.4 Signal 行已落地注记；risk-job 全模块 96 例 0 失败）
- [x] ruleConditions 结构化 JSON schema（P2，重构契约 §2.4「2.0 动作」代决开工）：`RiskRuleEntity.ruleConditions` 自由文本收敛为类型化结构 + 写路径校验测试钉住（FEATURE 条件结构校验收紧；PATTERN 既有 2-8 序列约束不动；THRESHOLD 任意串既有口径不动）；存量行不改写、读路径保持容忍（可解析即兼容，不可解析在类型化读取上显式报错），Flink 侧既有跳过语义不变。验收：类型化结构 + 校验测试全绿、replay/fetcher 既有测试原样绿（存量行为不变） ✅（7602a72：`dto/RuleConditions` 严格/容忍双档 + `conditions()` 实体访问器；FEATURE 写路径收紧、PATTERN/THRESHOLD 口径不动；受影响 5 测试类 25/25 绿、control-service 全模块 173 suite/2034 例 0 失败；契约 §2.4 RiskRule 行已落地注记，EventSchema 引用留后续）
- [x] B1 产品定位与文档对齐：GitHub description 一句话定位、三份旧评估归档横幅（PIT 改 Oddsmaker、完成度数字改指批次跟踪）、redesign index 补 06 行（0f8617a、4a86775、568ffcb）
- [x] B2 权限单真源收敛：user_role_assignments 回填迁移 V0.9.16、UserService 唯一写路径、demo README 删手工 psql 兜底、真 PG 复现验证（ace946f，208 suite 绿）
- [x] B3 Server SDK 骨架：Java SDK 四方法、内存溢出落盘队列、批量 gzip HMAC 上报、网关验签正负臂、SERVER key 发放校验（115b51f，211 suite 绿）
- [x] B4 事件契约 v2：event_version/source/trust_level/event_origin 四字段、三枚 schema 同步、网关 TrustPolicy 防自抬、Flink 全 job 透传（70c9929，213 suite/2456 例绿）
- [x] B5 风控 Feature 层：risk_features 表 V0.9.18、risk-job 首 6 特征滑动窗口、规则条件从特征取值三段解耦、真 PG 端到端（3c592f2，特征测试 16 例加全量绿）
- [x] B6 RiskScore 独立与 Decision 状态机：CH risk_scores 主体快照、状态机严格升层、trust 门槛双侧双保险、PG/CH 端到端各一（980ad8b，全量绿加状态机 11 例）
- [x] B7 EventSchema 一等资源化：五字段与发布兼容门、未知事件默认拒收、web schemas 页（88f99d7，发布 10 例、视图 13 例、全量绿）
- [x] B8 实验平台形式化：ExperimentEntity 字段化、schema 升级旧配置兼容、exposure 入平台事件清单（7b2c1f6，221 suite/2531 例绿）
- [x] B9 运行模式矩阵：deploy/MODES.md 三档组件矩阵、四处编排头注档位标记（0a16991，纯文档，compose config 四文件全过）
- [x] B10 数据质量与共享特征收口：五指标落库与健康页、feature_store 双写、DLQ 消费者、网关 14 维埋点（8eba96c，228 suite/2551 例绿）
- [x] 策略实验室 V0.2 复盘聚合：规则维度聚合 API、案例样本下钻、/risk-lab 页（f4cef12，236 suite/2591 例、web 165 绿）
- [x] 策略实验室 V0.3 试算回放 dry-run：批量样本 THRESHOLD/FEATURE 逐事件试算、流式类明示跳过、replay API 加试算面板（b2bb8a3，237/2599、web 168 绿，实机走查过）
- [x] 策略实验室 V0.4 样本集持久化：命名留档、载入重放对比、删除重建（6e99248，239/2612、web 171 绿，实机走查过）
- [x] 主体累计风险分读取端点：CH risk_scores 最新快照、reasons 三形态兼容解析（5135dc3，7 例绿）
- [x] 案例详情注入主体累计分：详情响应 subjectRiskScore、CH 读取去事务防 rollback-only、控制台累计分行（17c521a、62d6424、80ae782，web 174 绿，实机走查过）
- [x] Webhook 派发事务隔离：sendCustomWebhook 标 REQUIRES_NEW，同族陷阱全仓 21 处扫清（42cffa7，13 例绿）
- [x] PII 优先级链生效：环境级 ACTIVE Schema piiPolicy 下发网关、字段级收敛压 ApiKey 级（7ed0d0f，241 suite/2627 例绿）
- [x] PII 策略控制台入口：SchemasView 策略列与 DRAFT 编辑弹层（c919626，web 180 绿）
- [x] 维度同步 HTTP Pull 链路：dimension_pull_config 表 V0.9.25、凭证 AES-GCM 托管、断点续传调度、手动触发 API（97b491e，24 例绿）
- [x] 维度同步 Control YAML 配置面：export/import 两端点、字段白名单、方法级事务（8b6c49c，22 例绿）
- [x] 维度同步控制台 Pull 管理面：配置表、建档编辑弹层、立即拉取、YAML 导入导出弹层（5be3931，web 191 绿）
- [x] 维度同步 Agent 属性面 Control 托管：dimension_agent_config 表 V0.9.26、props 白名单加六密钥 AES-GCM、CRUD 与下发口（cd26de6，25 例绿）
- [x] 维度同步 Agent 端取配置链路：ControlConfigFetcher 启动拉取、本地 Control System property 三层合并（f9b41be，8 例绿）
- [x] 维度同步 Agent 托管配置控制台入口：配置表、新建编辑弹层、secrets 缺省保留或整体轮换、删除（d0ee2d2，web 198 绿）
- [x] 托管配置轮询热更新：control.config-refresh-seconds 门槛轮询、下发原文有变换组件、断点不动（a2b50e0，agent 211 例绿）
- [x] ml 训练管线测试纳入 CI：ml-tests job、Python 3.12、editable 安装含 dev extras（a707a32，68 例绿）
- [x] API Key 自动轮换执行端：`auto_rotate`/`rotation_days` 死字段接活（V0.9.27 `last_rotated_at`、每小时扫描换发 sk_、pk_ 不动、策略 null 不覆盖、轮换写审计）（68ce564，10 例绿，模块 1998 例 0 失败）
- [x] 文档对账勾账：operations 清单「限流」标网关侧未覆盖，实为已实现（`RateLimitFilter` 拦 `/v1/batch`，per-key `rpm` + per-IP `ipRpm` 超限 429 + `Retry-After`）→ 改判 [x]（e9ebbac，网关 237 例 0 失败）
- [x] DR 缺口入库：postgres WAL 归档（`archive_mode=on`/`wal_level=replica`/`max_wal_senders=3`/`wal_archive_timeout=30s` + 30 天就地留存清理，归档独立卷/PVC）、Redis 快照+AOF（`save 60 10000/300 10/900 1` + `appendfsync everysec`）、ClickHouse 全量/增量备份脚本（90 天留存）（39e2a1c，纯配置，YAML 解析与 `docker compose config` 全过）
- [x] Redis 备份 CronJob 去 RWO 共挂：`redis-cli --rdb` 网络流式导出替代数据 PVC 挂载（消除同节点调度耦合 + BGSAVE 完成竞态），补 7 天留存就地清理（backup-strategy.md §Redis 对齐）（6e4bf1d，纯配置，YAML 解析与 shell 语法校验过）
- [x] K8s 控制面清单镜像指真：`deploy/k8s/control-service.yaml` 的 `oddsmaker/control-service:latest`（仓内无任何 workflow 构建）改指 `ghcr.io/cuihairu/oddsmaker:nightly` + ConfigMap 显式 `SERVICE=control`（与 release.yaml:170 `kubectl set image` 实际镜像对齐），MODES.md K8s 形态行补 gateway（58e7b48，纯配置，YAML 解析过）
- [x] K8s 部署文档对账：`deploy.k8s.md` 方式二由 legacy `infra/k8s/` 改指当前 `deploy/k8s/` 清单，前提与镜像构建段改述单镜像双服务 `ghcr.io/cuihairu/oddsmaker:nightly` + `SERVICE`（665d630，纯文档，docs:build 绿）
- [x] Web SDK 游戏事件 helper 出口接线：`sdks/web/src/game-events.ts`（`GameEvents` 19 方法 + 8 常量对象，468 行）自 e9ebbac 起存在但从未从 `index.ts` 桶文件导出，`game-event-dictionary.zh.md:98` 标「待补」——补 `export * from './game-events.ts'` + tsconfig `rewriteRelativeImportExtensions`（tsc 5.9 将 `.ts` 重写为 `.js` 输出，Node 24 原生 TS 运行时需显式扩展名），index.test.ts 补桶文件导出断言（1009ecb，web 47 例 0 失败，docs:build 绿）
- [x] K8s 备份作业留存清理被 aws 缺失整条掐断：`backup-cronjob.yaml` 内嵌 backup.sh 跑在 `postgres:16-alpine`（实测镜像内无 aws CLI），`set -e` 下 `aws s3 cp` 直接 127 中止，其后 `find -mtime +30 -delete` 永不执行——100Gi 备份 PVC 无界增长直到写满，离站副本也从未产生；上传分支加 `command -v aws` 判存 + WARN 降级，同款修 `deploy/backup/postgres-backup.sh`（另修 LOG_FILE 不可写致首条 log 即中止、pg_restore 校验在 set -e 后形同虚设），BACKUP_DIR/LOG_FILE 开放环境覆盖，周档去掉脚本不读的 `BACKUP_TYPE` 死变量（a0c1b9f，新增 BackupScriptTest 5 例，control-service 167 suite/2003 例 0 失败）
- [x] 运维文档三处失效部署指令对账：operations/index.md ① `kubectl apply -f deploy/monitoring/` 改述配置文件挂载/导入语义（目录内是 Prometheus 抓取配置+告警规则+Grafana 看板导出，无任何 k8s manifests，原命令必失败）② Helm 段假仓库 `helm repo add oddsmaker https://charts.oddsmaker.local` + `helm install oddsmaker/oddsmaker` 改指仓内 chart `./infra/helm/oddsmaker`（与 deploy.k8s.md 对齐）③ `kubectl apply -f deploy/k8s/` 目录整体应用改逐清单+备份注释可选（backup-cronjob 非必装）；release.md 镜像构建段补单镜像双服务 CI 路径并注明 Helm 需逐服务镜像、deploy/k8s/ 走单镜像（0550588/996780f/abccfe2，纯文档，docs:build 绿，引用路径全存在）
- [x] BackupScriptTest 沙箱前提在 CI 上被击穿：GH-hosted runner 预装 aws CLI，测试早年把整段宿主目录挂进 PATH，「postgres:16-alpine 镜像内无 aws」这条前提在无 aws 的宿主上才成立，CI 上脚本真去传 S3、无凭证失败被 set -e 带走（实测 exit 254）且留存清理连带跳过，两用例在 nightly 与 CI/CD 双挂（03:04 起持续红，与 Boot 升级无关）；沙箱 PATH 收敛为纯桩 bin（date/tee/mkdir/find/du/cut/rm/tar/gzip 由 SANDBOX_TOOLS 符号链接进桩目录，宿主目录只用于找链接源、不进 PATH），aws 天然不可见，clickhouse 用例同套收紧（4e99450，BackupScriptTest 6 例 0 失败，control-service 2004 例 0 失败）
- [x] Nightly 镜像构建随 Boot 4 双层失效：① 三个 Dockerfile 基础镜像独立钉 `gradle:8.10-jdk21`（版本真源两处），Boot 4 插件要求 Gradle 8.14+ 或 9.x，配置阶段即失败，统一升 `gradle:9.8.1-jdk21` 与 wrapper 对齐；② 版本修好后 Gradle 9 把「settings include 但目录不存在」从告警升为硬错误，暴露 COPY 清单漏了 sdks/server 与 agents/dimension-sync-agent，且 sdks 被 .dockerignore 整段排除，需 `sdks/*` + `!sdks/server` 窄反排除（反排除行须排在 `**/build/` 之前，后匹配优先否则构建产物回流 context）（28c3a20，本机 docker build exit 0 并抽验镜像内双 jar，CI Full Test Suite 在 37c9c66 全绿）
- [x] Boot 4 拆包缺自动配置模块致镜像起不来：control-service 直接声明客户端库而非 starter，漏了 Boot 4 从 spring-boot-autoconfigure 拆出的 `spring-boot-kafka` 与 `spring-boot-flyway`——前者让三个 @KafkaListener 组件找不到 kafkaListenerContainerFactory、整个 context 起不来（NoSuchBeanDefinitionException，容器崩溃重启循环）；后者让 Flyway 静默不执行（老库因 schema 已在而掩盖，空库 validate 直接失败 missing table [ad_analysis]，新迁移将永不落库）。补两模块，并补全量 context 启动烟测（H2 内存库 + migration-smoke 探针迁移，专守「缺自动配置模块」这类单测盲区；control-service 此前唯一入口测试 ApplicationMainTest 显式 mock 掉不启 context）（8f2a898，control-service 2006 例 0 失败、全仓 2736 例 0 失败；真 PG 空库复现：65 条迁移全量落库、/actuator/health 200 UP；Deploy Demo 38540412345 绿，digest sha256:4185781…）
- [x] Deploy Demo 健康预算与回滚重建缺陷（首轮 Boot 4 真机部署暴露）：① 健康探活 300s 预算从 step 起算，compose up 在慢宿主解包镜像约 4 分钟已吃掉大半，探针到期时应用仍在 JPA 初始化中段，被误判不健康；② 回滚 `docker tag` 重指同名 tag 后 `compose up -d` 不重建容器（实测保持 Running，回滚空转 600s）；③ job timeout 15 分钟被 pull 抖动重试 + 解包吃光，回滚腿未跑完即被掐（GH 报 cancelled）。改为探活预算两段化（先等 control-service Running 600s，再计时健康 600s）、job timeout 15→30、回滚显式 `--force-recreate control-service`（只重建应用，不弹 PG/Redis）（214167f，YAML 解析过；随后 8f2a898 部署链实测绿）
- [x] Deploy Demo 假绿：上游 Nightly 失败时 deploy job 被 `if` 跳过，workflow_run 触发的 run 仍报 success——「Deploy Demo 绿」会被读成部署成功，实际什么都没部署（实测 38517953004：deploy=skipped、run=success，本轮排障被误导过）。加 `no-deploy` 哨兵 job，仅在 deploy 被跳过时显式变红，保证该工作流绿 = 真部署过且健康（6ccf1b6，YAML 解析过；同日 38543678647 实测 deploy 绿 + 哨兵 skipped）
- [x] Web 控制台测试纳入 push/PR 门禁：web/ vitest 202 例此前只在 nightly-build.yml 跑，ci.yaml 三 job（Java 服务 / Web SDK / ML）均不覆盖——改坏 web/ 的提交在 CI 上绿、到夜里才红（与 ml 缺 CI 同族，a707a32 已闭合 ml）。ci.yaml 补 web-console-tests job，步骤与 nightly 同形同源（pnpm 12 + Node 24 + frozen-lockfile + test + build）（8435700，全量门禁本机实跑：gradle 全模块 2736 例、web 202 例、ml 68 例，均 0 失败）
- [x] Java 测试门禁扩到全模块：ci.yaml 的 build-and-test 由 control+gateway 两模块（2243 例）改为 bare `test`，此前其余 13 个模块 493 例（agent 211 / Flink 七作业 257 / sdks-server 8 / libs 17）只在 nightly 跑，与 ml、web 缺门同族（f86f218，全量 2736 例 0 失败，254 suite）
- [x] Release 流水线拆弹：release.yaml build job 两步硬钉 gradle-version '8.7'（Boot 4 要求 8.14+，下次发版必在配置阶段炸；28c3a20 只修了 Dockerfile 侧）+ 测试步仅 control 单模块——改走仓库 wrapper + bare `test` 全模块，与 ci/nightly 同源同口径（878dd48，YAML 解析过，零源码变更沿用同源全量 2736 例 0 失败；未打 tag 未触发发版）
- [x] Web SDK 测试门禁补全：ci.yaml build-web-sdk 由只跑 `test:hash` 改为 `npm test` 三套全跑——`test:game` 与 `test:src`（含 1009ecb 桶文件导出断言）此前在 CI 上从未执行，各流水线对 sdks/ 仅此一处引用（1115c6b，本机三套全绿：test:src 47 例 0 失败，hash/game OK）
- [x] Android SDK 测试纳入门禁：`sdks/android/jvm-coverage`（源码直测 70 例，srcDir 链接同一份 Android 源码 + android.* stub，自带独立 settings.gradle.kts 不在根 include 列表，此前四条流水线零引用——改坏 Android SDK 在 CI 上是绿的，与 ci Java 全模块/web 控制台/Web SDK/nightly 口径同族最后一处）：ci.yaml 增 android-sdk-tests job、nightly job 1 补第五腿，均走根 wrapper + `-p` 不另装 wrapper（b757f1d；本机冷树 70 例 0 失败，CI 38661123379 五 job 全绿、nightly Full Test Suite 绿）
- [x] 审计事件分类常量（路线图 §4「未排期」项落地 + 修复 main 编译红）：`AuditEventType` 枚举（RISK_POLICY_CHANGED/API_KEY_ROTATED/DATA_EXPORTED/PERMISSION_CHANGED/EXPERIMENT_PUBLISHED/OTHER，`classify`=资源×动作×details.operation 读时派生、不做新表，`operation` 对非 JSON/非字符串值宁落 OTHER）、`AuditLogEntity.getEventType()`、resource-first `logCreate`/`logUpdate` metadata 落 details（gameId/environment 提列，序列化失败降级留空）——轮换行（68ce564 约定走 metadata）此前 details=null，自此落库即带判定依据。起因：并行会话 wip snapshot（cf23d52）把先行测试提上 main 而实现未跟上，cannot find symbol 打红 CI/CD、Nightly、Deploy Demo 三流水线 6.5h；测试侧两处修正：末参 null 重载歧义加转型（同文件既有写法）、描述去重断言反了 assertFalse→assertTrue（从未跑绿过）（e8d870d，新增 18 例全绿 + control-service 全模块 169 suite/2019 例 0 失败）
- [x] Nightly Full Test Suite 口径补齐：nightly job 1 名为 Full Test Suite 却缺 ml（68 例）与 Web SDK 三套两腿，补齐与 ci.yaml 四腿同口径；顺带 web/ 步骤误标 "Web SDK" 命名对正为 Web Console。首轮 CI 冷树揪出 test:hash 依赖构建产物（先 test 后无 build 报 ERR_MODULE_NOT_FOUND，本机暖树残留掩盖），补 Build 步对齐 ci.yaml build→test 顺序（eaab3f5 + 351b690，YAML 解析过；本机全量：gradle 2736 例 / web 202 例+build / ml 68 例 / SDK 冷树复现+暖树三套，均 0 失败）
- [x] 文档一致性审计台账回填：`docs/审计-文档一致性.md` 的 D8（事件分类常量标「未排期」）与 F11（Web SDK 游戏事件 helper 标「待补」）两条「已修」记录被后续批次打反——e8d870d 已落地 `AuditEventType`、1009ecb 已补 `game-events.ts` 并经桶文件导出；两行修法列改「先标 X；2026-10-15 已落地」，表头加 2026-10-15 现状更新块（a0f3408，纯文档，`pnpm docs:build` 绿 36.98s）
- [x] 审计 API 事件分类出参对账：e8d870d 的派生 `getEventType()` 随实体直出（`AuditLogController` 返回 `Page<AuditLogEntity>`，无 DTO 隔离层）成为对外契约，但既无边界测试、zh 参考文档端点表亦缺 7 条（status/game/failed/auth/sensitive/search/cleanup 及权限列）——补 `AuditLogEntityJsonTest` 钉序列化契约（轮换行出 API_KEY_ROTATED、风控/其余分流），control.md 审计段补全 14 GET + 1 DELETE 与 audit:read/sensitive/manage 权限列、加 eventType 五类+OTHER 判定表（c778133，control-service 170 suite/2021 例 0 失败，docs:build 绿 33.66s）
- [x] docs 站点首页浏览器语言自动跳转（P2，他席提交 67d3cd8 落地、本轮 PATROL1018 第十三巡补登账本并复核）：`docs/index.md` 增 VitePress `<script setup lang="ts">` 块——仅站点根路径（`/oddsmaker/`）生效，localStorage 手动选择优先于 `navigator.languages` 的 `zh` 前缀判定（跳 `/oddsmaker/zh/`），点击内链时记回手动选择；VitePress 无此能力、GitHub Pages 不支持 `_redirects`，故在首页内联。验收：28 新增行零标题/零表格行/零围栏（结构语法命中 0），首页配对计数不受影响（index 20 标题/4 围栏、zh/index 19/2，插入前后一致）；含 `typeof window === 'undefined'` SSR 守卫；`pnpm docs:build` 绿 39.18s。注：commit 标题写 feat: 但改动面仅 docs/index.md 一处，无产品代码变更

## 边界（决定不做，勿再提）

- 多租户/SaaS：Organization/Workspace/Project/Tenant 概念不入新代码
- 不删 Gateway project_id 兼容层：删除破坏 v0 SDK 兼容
- 事件自由流动不做：dev 模式外未注册 schema 的事件默认拒收
- SDK 与业务层不持有物理路由：storage_profile 是唯一桥
- Data Lineage 系统化不做；移动端原生队列之外的新客户端端不做
- provider 多态与 auth.token_ref 密钥引用表不另行排期：inline credential 加 AES-GCM 已覆盖现有 provider 面（2026-10-11 拍板）
- HarmonyOS SDK 不开工：绿地新建而非补缺，维持低优先级（2026-10-11 拍板）
- 站内 IM 不做：webhook 加邮件已覆盖离线触达，IM 属产品级新方向留待用户
- unmerge 身份拆分不做：修正路径已齐（tombstone 反查、GDPR 擦除、再 merge 纠错）
- CF DNS 域名切换属用户侧操作，本仓不动
- P8 MMP 归因接入暂停：前置 MMP 原始数据导出权限未达成
- 旧评估完成度数字（80/80 等）不得再引用为现状
