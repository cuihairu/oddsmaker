# TODO

需求清单：一条一句；完成项留一行证据（commit + 测试数）。重构契约 = `docs/zh/redesign/06-od2-restructure-plan.md`。

## 开放需求

- [ ] Debezium CDC 维度同步：等真实游戏方开 binlog 并部署 Kafka Connect 后排期，消费端复用 dimension-sync-job 事件入口
  - R1015 卡点：真实游戏方未开 binlog、未部署 Kafka Connect（2026-10-15 复核：仓内 debezium/Kafka Connect 检索零信号）；所需输入：游戏方开 binlog + 部署 Kafka Connect + 提供 CDC 事件格式与目标表映射
- [ ] ML 异常检测进评估批次：任一触发条件成立即开工——指标历史可回看 7 天以上且 7d/28d 分位基线可算；单游戏阈值告警规则月均调整 3 次以上；告警误杀率超 30% 持续 2 周
  - R1015 卡点：触发条件未成立（2026-10-15 复核三表仍 0 行）——演示库 health_metrics、data_quality_metrics、metric_alert_rules 三表皆 0 行，最早指标日期为无；且 health_metrics 无产线写入方（collectMetric 仅模拟开关路径、默认关），data_quality_metrics 需网关真实流量而演示栈无 gateway，条件 (a) 非等待可解；解锁条件：四项任一达标，其中 (a) 需先有带网关流量的部署面并自指标开始落库起算 7 天

## 已完成需求

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
