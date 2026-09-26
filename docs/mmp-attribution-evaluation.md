# MMP 归因接入评估（P7-5 调研报告）

> 对应 `docs/competitive-analysis.md` 差距矩阵第 5 项「归因/买量（MMP 集成、CPI/ROAS）」。
> 结论先行：**技术可行、路径清晰，但强依赖外部 MMP 账号与套餐权限——建议立项（P8）但以真实账号可用性为前置条件**，本文档给出建表草案与推荐接入路径。

## 1. 背景

竞品分析（2026-09）将 MMP 归因列为差距第 5 名：价值 ★★★（买量型工作室 ★★★★★）、成本 ★★★★，标注"先调研后立项"。本仓库当前状态：

- **已有**：`events` 表的 `attribution Map(String,String)` 兜底字段、`ad_network / ad_placement / ad_format / ad_impression_id` 四个回退维度列、在线报表按 `attribution.channel` 回退 `platform` 的渠道聚合。
- **缺失**：MMP 侧原始归因数据（安装归因、归因的 in-app 事件、广告花费）没有接入通道，无法回答"哪个渠道来的用户、花了多少钱、回收了多少"。当前 `attribution` 字段只承载游戏服自己打上来的渠道标签，与 MMP 的归因判定（最后一次点击/展示匹配）无关。

## 2. 主流 MMP 数据源调研

调研了 AppsFlyer、Adjust 两家头部与 Airbridge 一家后起（Branch/Singular 模式与 Adjust 类似，不再展开）。

### 2.1 AppsFlyer

| 机制 | 形态 | 延迟 | 内容 | 备注 |
|---|---|---|---|---|
| **Push API** | 实时推流到你的 HTTPS 端点 | 秒级 | 安装 + in-app 事件归因原始数据（含 SKAN） | 需自建接收端点，处理签名/重试/幂等 |
| **Data Locker** | 定时写到**你自己的** S3/GCS 桶 | 小时/日级分目录 | installs、in-app events、uninstall、SKAN 等 30+ 数据集 CSV | 原始数据最全的批式通道，高阶套餐功能 |
| **Raw Data Pull API V2** | 拉取式查询 API | 按需 | 安装/事件原始明细（带 token 鉴权） | 适合补数，不适合做主链路 |
| **Postbacks by date API** | 拉取式 | 按需 | 按日期的归因 postback 明细 | 对账用 |

### 2.2 Adjust

| 机制 | 形态 | 延迟 | 内容 | 备注 |
|---|---|---|---|---|
| **Real-time callbacks**（global callbacks） | 事件发生时回调你的 HTTPS 端点 | 实时 | 安装、会话、事件归因占位符（`{ad_network}`、`{campaign}` 等） | URL 模板化配置，需自建接收端 |
| **CSV uploads / Cloud storage upload** | 定时 CSV 上传到你的 S3/GCS/Azure | 小时/日级 | 原始数据报表（installs、events 等，列可配置） | help.adjust.com "Raw data export" 章节；Live/Pause 控制 |
| **S2S（server-to-server）** | 反向通道：你的服务向 Adjust 发事件 | 实时 | 自有服务端事件上报 | 与接入归因数据方向相反，但说明同账号可做双向 |

### 2.3 Airbridge

- **Cloud storage export**：定时把原始数据导出到 AWS S3 / Google Cloud Storage，模式与上两家一致。

### 2.4 共性结论

1. **所有主流 MMP 都支持两种交付形态**：实时 Webhook（Push API / real-time callbacks）与定时落你自己的云存储（Data Locker / CSV uploads / cloud storage export）。
2. 原始数据（非聚合报表）通道普遍**锁在高阶付费套餐**——外部成本主要是 MMP 侧订阅，不是开发量。
3. 数据对齐键是 MMP 的 app 标识（`app_id` / app token）+ 设备 ID（IDFA/GAID 或其 hash），需要一张 **MMP app ↔ `game_id + environment` 映射配置**才能落进 oddsmaker 的隔离契约。
4. 广告花费（cost）通常不在安装原始数据里，而在 MMP 的 campaign 报表 API——CPI/ROAS 完整闭环需要额外接一个报表拉取 job。

## 3. 接入路径对比（oddsmaker 视角）

### 方案 A：实时 Webhook [自建接收端点]

MMP Push API / callbacks 打到 Gateway 或 control 新增 ingestion 端点。

- 优点：秒级新鲜度，可实时反哺风控（渠道作弊识别）。
- 缺点：需处理签名验证、重放、幂等、限流；MMP 侧重试语义各家不一；端点暴露面 +1。与 P0 安全修复（Gateway 收敛前置校验）方向有张力。

### 方案 B（推荐）：定时云存储导出 [加载 job → ClickHouse]

MMP 定时写 S3/GCS（Data Locker / CSV uploads），运维用**与 P7-4 导出目录相同的对象存储同步机制**把文件落到 oddsmaker 侧，新增一个定时加载 job 解析入库。

- 优点：与现有架构对称（P7-4 已建立"导出目录 + manifest + 运维同步"的归档模式，反向读同一目录体系即可）；批式可重放、可对账；无需暴露新公网端点；解析失败可修后重跑。
- 缺点：小时级延迟（买量分析场景完全够用）；依赖运维的对象存储同步任务。

### 方案 C：MMP S2S postback 冒充渠道打 Gateway

把 MMP postback 当作一种 in-app 事件直接走 SDK 事件通道。

- 不推荐：污染自有事件契约（`event_type` 语义混乱），MMP postback 的归因字段塞不进 `attribution Map` 的规范，且失去独立表结构的演进空间。

**结论：以方案 B 为主路径立项；若未来需要实时反作弊，再补方案 A 作为增量。**

## 4. 建表草案（ClickHouse）

```sql
-- MMP 安装归因（方案 B 主表）
CREATE TABLE IF NOT EXISTS attribution_installs
(
    game_id        String,
    environment    LowCardinality(String),
    mmp            LowCardinality(String),   -- appsflyer / adjust / airbridge / ...
    mmp_app_id     String,                   -- MMP 侧 app 标识
    install_time   DateTime,
    event_date     Date MATERIALIZED toDate(install_time),
    subject        String,                   -- 与 events 相同 SUBJECT 口径（player_id→user_id→device_id 回退）
    device_id      String,
    user_id        String,
    is_organic     UInt8,
    match_type     LowCardinality(String),   -- click / impression / srn / probabilistic ...
    touch_time     DateTime,
    media_source   String,                   -- 归一化到 events.ad_network 口径
    agency         String,
    campaign       String,
    adset          String,
    ad             String,
    keyword        String,
    country        LowCardinality(String),
    device_type    LowCardinality(String),
    platform       LowCardinality(String),
    app_version    String,
    raw            Map(String, String),      -- MMP 原始行兜底（与 events.attribution 同思路）
    ingest_batch   String,                   -- 加载批次（文件名/manifest id），支持按批次重放删除
    ingested_at    DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, mmp_app_id, subject, install_time);

-- 归因 in-app 事件（可选二期）：结构同上，event_name/revenue 两列替换 campaign 侧列
```

配套（Postgres，control 侧）：

- `mmp_connections` 表：`game_id + environment` ↔ `mmp + mmp_app_id` 映射、文件路径模式、字段映射版本、启停。权限沿用 `export:read` / 新增 `mmp:manage`。
- 加载 job：扫描目录 → 按 manifest/文件名解析 → 批次幂等（`ingest_batch` 去重）→ 失败告警。

## 5. 成本与工作量评估

| 项 | 评估 |
|---|---|
| 外部依赖 | **重**：需要真实 MMP 账号 + 原始数据导出套餐（Data Locker/CSV uploads 均非免费档）；无账号则闭环无法验证 |
| 开发量（方案 B 最小闭环） | 约 5–7 人日：DDL + `mmp_connections` CRUD + 加载 job（CSV/JSON 各一解析器）+ 渠道安装分布报表 1 张 |
| 二期（CPI/ROAS） | +4–6 人日：campaign 花费报表 API 拉取 + 归因收入 join；需 MMP 报表 API 权限 |
| 风险 | MMP 字段各家且版本多变（AppsFlyer/Adjust 列名不同）——解析器必须按 `mmp` 维度隔离 + `raw` Map 兜底 |

## 6. Go / No-Go 建议

**有条件立项（进 P8 路线图）**：

- 触发条件：拿到任一 MMP 的原始数据导出权限（Data Locker 或 CSV uploads 任一即可启动）。
- 启动范围：方案 B 最小闭环 + 安装归因渠道分布报表；CPI/ROAS 等花费数据接入放二期。
- 明确不做（与竞品分析 §4 一致）：广告平台直连（Facebook/Google Ads API 直接拉数）、MMP 聚合看板复刻、移动归因 SDK 自研。
- 在触发条件达成前，P7 系列到此收口；后续优先级回归补测试覆盖与运营工具打磨。

## 7. 参考资料

- AppsFlyer dev docs：Push API、Data Locker、Raw Data Pull API V2、Postbacks by date API（dev.appsflyer.com）
- Adjust help center：Raw data export → Cloud storage upload（help.adjust.com）；S2S 与 callbacks（dev.adjust.com）
- Airbridge docs：Cloud storage export（S3/GCS 定时导出）
- 本仓库：`docs/competitive-analysis.md`（差距矩阵与不跟进清单）、`schema/sql/clickhouse/schema.sql`（events 表现有归因字段）
