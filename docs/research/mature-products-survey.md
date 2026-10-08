# 成熟数据分析与风控产品调研（功能、交互、SDK）

> 调研时间：2026-10 ｜ 方法：各产品官方文档与官网产品页 + GitHub 仓库（协议与开源状态）+ 开发者中心下载页，页面截图为 2026-10-08 抓取，出处见 §7
> 覆盖 14 家：分析类 10 家（GameAnalytics、Unity Analytics、Adjust、AppsFlyer、Firebase Analytics、Amplitude、友盟+、TalkingData、神策、GrowingIO），风控类 4 家（数美、网易易盾、阿里云风险识别、顶象）
> 结论用途：补充 `docs/competitive-analysis.md`（5 家竞品的能力差距矩阵）与 `docs/mmp-attribution-evaluation.md`（MMP 数据通道）没有展开的两块：**界面交互的具体做法**和**SDK 落地形态（接入方式、上报协议、端覆盖、开源情况）**

## 1. 调研对象与口径

| 产品 | 厂商 | 形态 | 收费方式 | 本文关注点 |
|------|------|------|----------|------------|
| GameAnalytics | GameAnalytics | 游戏分析 SaaS | 免费为主，增值付费 | 游戏原生事件分类、Collection API |
| Unity Analytics（UGS） | Unity | 引擎内嵌分析 | 随 UGS 用量 | UPM 包接入、Legacy 版本退场 |
| Adjust | Adjust | MMP 归因 | 付费 | 开源 SDK、归因与反作弊控制台 |
| AppsFlyer | AppsFlyer | MMP 归因 | 免费起步 + 付费 | 自定义许可 SDK、数据出口 |
| Firebase Analytics | Google | 移动分析 | 免费 + 按量 | DebugView、开源边界、上报格式 |
| Amplitude | Amplitude | 产品分析 | 免费档 + 付费 | 开源 SDK、图表级告警配置 |
| 友盟+ U-App | 友盟同欣（阿里系） | 移动统计 | 免费 + 付费 | 鸿蒙/小游戏端覆盖、告警计划 |
| TalkingData | TalkingData | 移动统计 + 广告监测 | 付费 | 下载式 Native SDK、部分开源封装层 |
| 神策数据 | 神策 | 分析 + 私有化部署 | 付费 | 分端差异的开源策略、智能预警交互 |
| GrowingIO | GrowingIO | 无埋点分析 | 付费 | 全埋点 SDK、看板与订阅交互 |
| 数美 | 北京数美时代 | 风控 + 内容审核 | 付费 | 区域集群上报、决策引擎后台 |
| 网易易盾 | 网易智企 | 智能风控 | 付费 | 可视化策略编排、后台下载式 SDK |
| 阿里云风险识别 | 阿里云 | 风控平台 | 按量 + 模型计费 | 决策引擎（事件→变量→策略） |
| 顶象 | 顶象 | 实时风险决策 | 付费，支持私有化 | 设备指纹 token 链路、前端 SDK 谱系 |

口径说明：本文只写公开文档能查到的事实，各厂商文档会随时改版，未查到的部分写"未公开"，不做推断。老的阿里云"数据风控"文档（2016/2017 年 PDF）与 WAF 里内置的"数据风控"（Bot 防护）是两回事，本文只覆盖现行的"风险识别"产品。

## 2. 分析类：功能与交互

### 2.1 看板与分析入口

| 产品 | 看板形态 | 漏斗/留存入口 | 值得记的一处交互 |
|------|----------|---------------|------------------|
| GameAnalytics | 预置 Overview（实时）+ 各分析模块 | 漏斗、留存、进度（Progression） | 八类标准事件（Ad/Business/Design/Error/Health/Impression/Progression/Resource）在采集侧就分好了类，看板按事件类型分组 |
| Unity Analytics | UGS Dashboard 内嵌于游戏服务控制台 | 漏斗、参与度视图 | 旧版 Unity Analytics 在 2022.3 手册中已标注 Legacy，由 UGS Analytics SDK 接管 |
| Adjust | 控制台按"归因—验证—分析"分区 | Cohort、事件报表 | 流量验证与作弊拦截（Detections）与报表同层，不是单独的安全产品入口 |
| AppsFlyer | Dashboard + 集成中心 + 数据出口 | 事件报表、受众 | 原始数据出口（Push API / Data Locker / Raw Data Pull）在控制台里是独立一级入口，与报表并列 |
| Firebase Analytics | GA4 首页卡片 + Realtime + Explore | Realtime 卡片内嵌漏斗、Audience | DebugView：连调试设备打的每条事件实时可见，接入期验证靠它 |
| Amplitude | 图表（Chart）→ 看板两层 | 事件分段、漏斗、留存、路径 | 图表右上角铃铛直接"Set Alert"，告警绑定在图表上而不是独立配置页 |
| 友盟+ U-App | 首页看板 + 深度分析 | 漏斗、自定义留存、用户分群 | 深度分析里带自定义 SQL 与自定义看板、看板市场（官网产品页） |
| TalkingData | 官网文档以 SDK 接入为主 | 分析界面公开文档少 | 本文对它的分析界面不下结论 |
| 神策 | 看板 + 分析模型（事件/漏斗/留存）+ 画像 | 分析模型独立入口，可存书签 | 书签是分析结果的最小复用单元：预警可直接从已有书签创建 |
| GrowingIO | 看板 = 图表集合，登录默认进看板 | 漏斗步骤可拖拽、留存表可下钻成分群 | 看板有全局过滤与时间过滤（作用于板内所有图），共享分"阅读者/编辑者"两级，全屏支持深色模式与分钟级定时刷新 |

### 2.2 告警配置界面

告警是各家差异最大的一块，四种做法：

1. **Amplitude：告警挂在图表上，三档自动程度**。automatic（每个事件默认监控，日量 ≥100 且近 30 天有 15 天有数据才启用，99% 置信区间外判定为异常，120 天训练期）、smart（99% 置信区间）、custom（高于/低于指定值、变化幅度、95%/98%/99% 置信度）。入口是图表铃铛图标，触发记录进 Notifications → Alerts；日粒度训练 120 天、小时粒度 14 天；免费档 1 条告警、Plus 档 2 条。
2. **神策：预警是独立模块，但指标来自事件分析**。路径"分析 > 其他 > 智能预警分析"，新建时可从书签跳转到事件分析建指标。通知三通道：系统消息（默认勾选）、企业群 webhook（飞书/钉钉/企业微信）、邮件（带"测试发送"）。触发后自动跑智能分析：异常维度分层定位、异常用户定位（主体默认 Distinct ID 与 IP，最多加 5 个事件属性）、同期预警指标重合度排序。另有"平台管理 > 运维管理 > 报警管理"管通道与历史，单条规则最多配三种通知方式。截图见 `assets/sensors-alert-config.png`。
3. **友盟：告警按"计划"配条件，通道贴国内 IM**。U-APM 的新建告警计划可勾选"已忽略的错误不参与触发条件计算"，条件形如"过去 n 分钟新增且累计错误数/影响用户数达 y"；通道覆盖邮件、钉钉、飞书、企业微信。
4. **Firebase / GA4：异常检测是系统算的，人只订阅**。Analytics Intelligence 按历史规律给出期望区间，超出即异常，用户在自定义卡片（Custom Insights）上配提醒，不需要自己定阈值。

共同点：通知通道都收敛到"webhook + 邮件 + 站内/IM"三件套；阈值告警与异常检测分开做，谁都不想让用户手填所有阈值。

### 2.3 风控类：规则引擎界面

| 产品 | 控制台分层 | 界面上能看到什么 |
|------|------------|------------------|
| 数美 | 设备指纹 → 实时可阻断事件 → 账号风险结果 → WEB 控制台 | 决策引擎后台可配置变量、策略、用户，支持误杀/漏杀案例分析；策略预制场景模板，10 余种运算符，支持外部特征接入与策略灰度、A/B、冠军挑战 |
| 网易易盾 | 风控引擎 → 服务管理（SDK 下载在此） | 官网产品页明确"策略可视化编排"（低代码规则配置，分钟级上线）、毫秒级实时决策、全景态势感知、决策白盒化溯源；截图见 `assets/yidun-risk-engine.png` |
| 阿里云风险识别 | 决策引擎 → 字段管理 / 事件管理 / 变量管理 / 策略管理 / 策略实验室 / 分析中心 | 官方四步流程：创建事件 → 关联变量（简单变量、复杂变量、名单、累计指标）→ 策略编辑（一事件多策略、一策略多条件）→ 调 API；另有样本管理与模型白盒策略配置；截图见 `assets/aliyun-decision-engine.png` |
| 顶象 | 应用管理 → 实时风险决策 / 设备指纹 | 文档里"字段"是判断风险的最小单位（ID、IP、账号、设备指纹），配置指标与策略都基于字段；设备采集监控模块可按设备指纹、来源、token、风险标签、IP、请求时间查明细 |

四家的共同形状是 **事件 → 字段/变量 → 策略 → 决策**，与本仓 Gateway 前置 + Flink 实时的规则链路同构；差异在阿里云把"样本管理和策略实验室"做成了一等公民，数美把"误杀漏杀案例分析"摆在后台首页，这两处是做复盘时最容易缺的。

## 3. SDK：接入方式、上报协议、端覆盖、开源情况

### 3.1 分析类 SDK

| 产品 | 接入方式 | 上报协议 | 端覆盖 | 开源情况 |
|------|----------|----------|--------|----------|
| GameAnalytics | 客户端 SDK，或服务端直接打 Collection API | HTTPS `POST /v2/{game_key}/init` 与 `/v2/{game_key}/events`，批量 JSON，SDK 与服务端共用同一套 API，接口带签名 | Unity、GameMaker、Construct、Stencyl、Android、iOS、JavaScript、C#（Mono/UWP）、Flutter（pub 官方包） | SDK 仓库在 GitHub `GameAnalytics` 组织下（114 个仓库），多为 MIT |
| Unity Analytics | UPM 包 `com.unity.services.analytics`，另可走 Collect API 服务端直传 | HTTPS 收集端点（Collect API 文档 services.docs.unity.com） | Unity 生态，Editor 最低支持 2022.3（UGS Release Notes） | 以 UPM 包分发，公开可见的是 needle-mirror 只读镜像 |
| Adjust | 客户端 SDK + S2S 事件接口 | 客户端 SDK HTTPS 直连 Adjust 采集端点；S2S 由业务后端推事件 | Android、iOS、Unity、Flutter、React Native、Cordova、Xamarin 等 | GitHub `adjust/android_sdk` 等仓库公开，Maven Central 标注 **MIT** |
| AppsFlyer | 客户端 SDK（`initSDK(devKey, appID)`）+ 服务端 API | SDK 加密上报至 AppsFlyer 采集端点；对外数据走 Push API / Data Locker / Raw Data Pull API | 原生 Android/iOS + Unity、React Native、Flutter、Cordova、WebView 等 | GitHub `AppsFlyerSDK` 组织仓库公开，但 LICENSE 是 **AppsFlyer SDK 自定义服务条款**，不是 OSI 许可 |
| Firebase Analytics | Firebase SDK（`logEvent` 等）+ DebugView 调试 | 走 Google 的 logging API，社区抓包结论是 **iOS 用 Protobuf、Android 用 JSON**（TrackHAR issue #52） | Android、iOS、Web、Unity/C++ 等 | `firebase-ios-sdk` 仓库开源，但 README 写明 **FirebaseAnalytics 库不在开源范围**；其余平台以预编译依赖分发 |
| Amplitude | 客户端 SDK，或服务端 HTTP V2 | HTTPS `POST` 到 `api2.amplitude.com`（HTTP V2，已替代旧 HTTP API），批量 JSON；另有 Ampli 生成式强类型封装 | Web/Node/Python/iOS/Android 及主流跨端框架 | GitHub `amplitude` 组织多数仓库 **MIT**（如 `@amplitude/analytics-browser`） |
| 友盟+ U-App | 开发者中心按平台下载二进制 SDK，AppKey 标识应用 | 未在公开文档中描述 | Android、iOS、HarmonyOS NEXT、Flutter、React Native、Unity3D、Cocos2dx-C++/lua、快应用、微信/支付宝小程序、H5/Web、小游戏 | GitHub `umeng` 组织有官方仓库，主要端以二进制包（jar/aar）+ 包名 MD5 的形式发布 |
| TalkingData | 官网下载中心按平台"定制"生成 Native SDK，业务侧再拼装 | 未在公开文档中描述 | Android、iOS、H5、微信小程序，另有 uni-app / DCloud 第三方插件 | 部分开源：GitHub `TalkingData/TalkingDataSDK_Unity` 只放封装层，`.jar/.a` 原生库要回官网下载 |
| 神策 | 客户端 SDK + 服务端 SDK + 第三方对接 | SDK 上报至神策分析服务（SaaS 或私有化实例），协议未在公开手册中展开 | 客户端：Android、iOS、Web JS、小程序、Flutter、RN、Weex、APICloud、uni-app、Unity、cocos2d-x、C++；服务端：Java、Python、C、C#、Golang、PHP、Ruby、Lua、Node | 分端不同：`sa-sdk-android-plugin2` 标 Apache-2.0，`sa-sdk-unity` 的 LICENSE 是"商业用途需购买许可"的自定义协议 |
| GrowingIO | 客户端 SDK + 服务端 SDK，项目侧要拿到 AccountId/UrlScheme/ServerHost/DataSourceId | 上报至配置的 ServerHost，14.3.0 起支持采集数据加密传输 | 客户端：Android、Apple、Web JS、9 家小程序、HarmonyOS、RN、Flutter；服务端：Java、PHP、Python、Go、Ruby、Node | GitHub `growingio` 组织开源，小程序 SDK README 明确"完全免费并开源"，同时注明开源版**移除了性能监控与厂商适配等商业化内容** |

### 3.2 风控类 SDK

| 产品 | 端侧 | 服务端 | 协议与链路 | 开源情况 |
|------|------|--------|------------|----------|
| 数美 | 设备指纹 SDK `smsdk`（3.14.*，Android/iOS/鸿蒙） | 事件接口（注册、登录、下单、提现等） | 按机房分集群，HTTPS 上报到 `api-*`.fengkongcloud.com（如北京 `api-skynet-bj.fengkongcloud.com/v4/event`）；标准接入（SaaS 托管）与定制接入两种模式 | 不开源，SDK 从数美侧获取 |
| 网易易盾 | Android、iOS、小程序、WEB/WAP/H5，游戏侧还有 Windows（C#） | `check` 接口 | 前端 SDK 采集 + 服务端 check 双通道；游戏场景可只走客户端 | 不开源，登录易盾后台"风控引擎 - 服务管理"下载，游戏端要求配合加固工具 |
| 阿里云风险识别 | 设备风险 SDK（文档有"设备风险 SDK Android 接入"） | 以服务端 API 为主，AccessKey 签名 | RPC/POP 风格 API + 决策引擎事件调用 | 不开源 |
| 顶象 | Web（JS，IE8+ 及主流浏览器、内嵌 WebView）、Android（`DXRisk.java`）、iOS（`DXRisk.xcframework` / `DXRiskWithIDFA`）、微信/支付宝/百度/抖音小程序、uniapp | 后端 SDK 下载接入 | 端侧采集设备信息上传风控后台换 token，业务后端带 token 调决策接口 | 不开源，支持快速私有化部署 |

三条对本仓有用的观察：

1. **上报协议只有分析类的海外厂商公开**（GameAnalytics 的 Collection API、Amplitude 的 HTTP V2 都有可对照的接口文档），国内分析厂商与全部风控厂商都不公开协议细节，接入时只能按官方 SDK 走，做兼容层没有参考对象。
2. **开源程度排序：分析类 > 风控类**。风控厂商连二进制都不给公开下载（易盾、数美、顶象都在登录后的后台发 SDK），这类接入的可控性天然低于分析 SDK。
3. **同一个厂商的许可会分叉**：神策按端区分 Apache-2.0 与商业许可，GrowingIO 开源版主动剔除商业模块，AppsFlyer 仓库公开但用自定义条款。判断能不能直接引入，要看具体仓库的 LICENSE 文件，不能只看 GitHub 页面是 Public。

## 4. 交互设计上可以直接抄的做法

1. **告警配置贴着图表走**：Amplitude 的铃铛、神策的"从书签创建预警"，都把配置入口放在用户已经盯着的数据旁边。独立的告警管理页只留启停、历史与通道。
2. **异常检测与阈值告警分两条路**：GA4、神策、Amplitude 都先给一个"系统自动判异常"的默认档，阈值档是进阶选项。只做阈值的告警，用户要么漏报要么被吵麻。
3. **通知通道固定三件套**：webhook、邮件、站内/IM，各家一致。神策把通道配置集中到"报警管理"一处，规则里只选通道类型，比每条规则各配一遍省事。
4. **风控控制台按"事件 → 字段/变量 → 策略"分层**，四家全一样；本仓的规则链路已是同一形状，差的是阿里云的样本管理、数美的误杀漏杀案例回看这两个复盘入口。
5. **接入期的验证入口决定留存**：Firebase DebugView、友盟"测试功能验证上报准确性"、GrowingIO"数据校验"都写进了接入文档的固定步骤，说明各家都认为"数据到没到"是接入期第一问题（与 `docs/competitive-analysis.md` §3 的第一条差距结论一致）。

## 5. 明确不跟进的形态

| 形态 | 来源 | 理由 |
|------|------|------|
| 下载式/后台式 SDK 分发（易盾、数美、TalkingData） | 风控三家 + TalkingData | 与本仓 sdks/ 目录的包管理分发方式冲突；对接这类外部 SDK 只能作为集成项，不进主仓 |
| 无埋点/全埋点采集 | GrowingIO、神策（部分端） | 依赖字节码插桩或 DOM 全量监听，客户端改动面大、隐私面大；本仓以显式埋点 + Tracking Plan 治理为准 |
| 自动异常检测（Prophet/ML 区间） | Amplitude、GA4、神策 | 需要每个指标的历史基线训练与回填，排在阈值告警之后；先把告警配置界面做出来更划算 |
| 归因与反作弊作为主产品 | Adjust、AppsFlyer | 已在 `docs/mmp-attribution-evaluation.md` 定为"接数据而非做产品"，本仓只消费 MMP 的归因结果 |

## 6. 与本仓现状的对应

- 告警：本仓已有阈值告警，缺的是"配置入口贴图表"与 webhook/邮件之外的通道收敛方式，§2.2 可作交互依据。
- 风控控制台：规则链路形状与四家一致，缺误杀漏杀案例回看与样本管理，对应 `docs/competitive-analysis.md` 之外的增量。
- SDK：本仓 5 端 SDK 走 JSON + HMAC，与 GameAnalytics Collection API、Amplitude HTTP V2 同一形态；这两份公开协议可作为对照基准。
- 分发：本仓 SDK 随仓库与包管理走，不采用风控厂商的后台下载制，理由见 §5。

## 7. 截图索引（出处与抓取时间）

截图均为第三方官方页面，版权归各厂商所有，仅供内部对照参考；抓取日期 2026-10-08，目录 `docs/research/assets/`。

| 文件 | 来源页面 | 抓取内容 |
|------|----------|----------|
| `sensors-alert-config.png` | manual.sensorsdata.cn/sa/docs/guide_warning/v0300 | 神策智能预警分析页，含通知通道与智能分析说明 |
| `growingio-dashboard.png` | docs.growingio.com/op-help/docs/4.1/product-manual/user-behavior-analytics/dashboards/ | GrowingIO 数据看板界面说明（侧栏、单图、全局过滤、共享） |
| `umeng-uapp-console.png` | www.umeng.com/pages/umeng-uapp | 友盟 U-App 产品页，含分析模块与 ROI 看板示例 |
| `amplitude-alerts.png` | amplitude.com/docs/analytics/insights | Amplitude 告警（automatic/custom/smart）配置文档 |
| `yidun-risk-engine.png` | dun.163.com/product/risk-engine | 易盾风控引擎产品页，策略可视化编排等能力卡 |
| `aliyun-decision-engine.png` | help.aliyun.com/zh/fraud-detection/user-guide/ | 阿里云风险识别决策引擎操作指南，左侧"事件管理/创建字段/策略管理"导航 |
| `shumei-decision-console.png` | help.ishumei.com/docs/tw/risk/api/ | 数美交易风控对接简介（含 WEB 控制台用途说明） |
| `dingxiang-risk-decision.png` | www.dingxiang-inc.com/docs/detail/ctu | 顶象实时风险决策文档，前端接入平台清单 |

未截图的产品（Unity、Firebase、Adjust、AppsFlyer、GameAnalytics、神策的分析界面、TalkingData）原因是控制台需登录或官网无界面图，相关交互描述以文字文档为准，已在正文标注出处。

## 8. 来源清单

分析类：
- GameAnalytics：docs.gameanalytics.com（Event Types、Collection API setup、各端 SDK 页）、apis.io 上的 Collection API OpenAPI 描述、github.com/GameAnalytics 组织
- Unity：docs.unity3d.com（2022.2 手册 Legacy Analytics、com.unity.services.analytics 包 API）、services.docs.unity.com/analytics/v1（Collect API）、UGS Release Notes
- Adjust：dev.adjust.com（SDK 集成指南）、Maven Central `com.adjust.sdk:adjust-android`（MIT）、github.com/adjust
- AppsFlyer：dev.appsflyer.com（API reference、initSDK）、support.appsflyer.com（基础 SDK 对接指南）、github.com/AppsFlyerSDK/AppsFlyerFramework LICENSE
- Firebase：firebase.google.com/docs/libraries 与 firebase-ios-sdk README（FirebaseAnalytics 不在开源范围）、github.com/tweaselORG/TrackHAR#52（上报格式抓包分析）、support.google.com/analytics（异常检测说明）
- Amplitude：amplitude.com/docs/analytics/insights（告警全文，含 Prophet、置信区间、训练期）、amplitude.com/docs/apis/analytics/http-v2（`api2.amplitude.com`）、github.com/amplitude
- 友盟+：www.umeng.com/pages/umeng-uapp、devs.umeng.com（平台与 SDK 清单）、developer.umeng.com 告警计划文档、github.com/umeng
- TalkingData：doc.talkingdata.com（SDK 使用说明、数据接口）、github.com/TalkingData/TalkingDataSDK_Unity、www.talkingdata.com/safety.jsp（SDK 合规与安全指南）
- 神策：manual.sensorsdata.cn（智能预警分析、报警管理、SDK 功能介绍）、github.com/sensorsdata（含 sa-sdk-unity 的 LICENSE）
- GrowingIO：growingio.github.io/growingio-sdk-docs（SDK 简介与端覆盖）、docs.growingio.com（数据看板、漏斗）、github.com/growingio/growingio-sdk-miniprogram-autotracker README

风控类：
- 数美：help.ishumei.com（交易风控对接简介、营销风控集群地址、SDK 隐私声明与 smsdk 版本）、www.ishumei.com 决策引擎产品页
- 网易易盾：dun.163.com/product/risk-engine、support.dun.163.com（智能风控接入步骤与隐私说明）
- 阿里云：help.aliyun.com/zh/fraud-detection（产品简介、决策引擎操作指南、事件管理）、阿里云风险识别决策引擎 PDF 指南、阿里云官网"如何开启风险识别决策引擎"四步说明
- 顶象：www.dingxiang-inc.com/docs/detail/ctu（实时风险决策）与 const-id（设备指纹）、support.huaweicloud.com/dxbss-sag（华为云上的顶象业务安全文档，字段与策略配置）
