# dimension-sync-agent（oddsmaker-agent）

维度数据同步 Agent：部署在游戏方内网，读取本地 DB / CSV 源头，推送维度变更到 Oddsmaker Gateway `/v1/batch`，并向 Control 上报同步状态。设计与链路见 `docs/zh/reference/dimension-sync.md`。

**零仓库内依赖**（仅 Jackson + kafka-clients + JDK HttpClient + runtimeOnly JDBC 驱动；xlsx 解析只用标准库 zip+xml，无 POI），整个目录可 git subtree 拆出为独立仓库。

## Source 支持

| type | 说明 | 增量机制 |
|---|---|---|
| `mysql` / `postgres` | 增量查询 | 单 `?` 占位绑定水位，查询必须以 `ORDER BY <水位列>` 结尾 |
| `csv` | 扫描目录下 `*.csv`（文件名序） | 文件粒度断点：处理完整文件才记 checkpoint，增量 = 投递新文件 |
| `excel` | 扫描目录下 `*.xlsx`（文件名序，取首个工作表） | 同 csv：文件粒度断点，增量 = 投递新文件 |
| `kafka` | 订阅 topic，消息体为 JSON 对象（字段语义同控制列） | 位点 checkpoint 自管（`k:0=42;1=57`，分区→下一 offset），不向 broker 提交 |

控制列（大小写不敏感）：`dim_type`/`dimension_type`（resource/items→item，levels→level）、`resource_id`/`item_code`/`level_id`/`id`、`op`（upsert/delete）、`version_ts`（epoch millis 或 ISO-8601）；其余列进 `attributes`。kafka 消息的嵌套对象/数组字段序列化为紧凑 JSON 文本进 attributes。

**验证边界（如实说明）**：

- excel：`XlsxParser` 为标准库最小实现（首个工作表；共享/内联字符串、数值、布尔、`r` 列引用定位、大数 E 记法还原；禁 DTD 防 XXE）。不支持公式重算（取缓存值）、样式/合并单元格/日期格式化——日期列建议源头导出 epoch millis 或 ISO 文本。已单测覆盖。
- kafka：位点推进/drain/坏消息跳过逻辑经假端口单测覆盖；`KafkaConsumerAdapter`（真实 broker 路径）**仅编译期验证，尚无 broker 端到端**。接真实 broker 后建议核对三点：`auto.offset.reset=earliest` 首轮行为、topic 扩分区后新分区从 earliest 起步、断点续传 seek 正确性。坏消息（解析失败/缺 resource_id）跳过但 offset 照常前进，防毒丸卡死位点。

## 快速开始

```bash
# 构建
./gradlew :agents:dimension-sync-agent:installDist

# 配置（参考 agent.properties.example，密钥可用 -D 覆盖不落盘）
build/install/dimension-sync-agent/bin/dimension-sync-agent \
  --config=/etc/oddsmaker/agent.properties
# 或： -Dgateway.api-key=xxx --config=... 
```

## 断点与幂等

- checkpoint 为本地 JSON（`agent.checkpoint-path`），tmp + 原子 move 落盘。
- **推送全部成功才前进水位**；失败下一轮以旧水位重放同窗口——下游 `item_dim`/`level_dim` 是 `ReplacingMergeTree(version_ts)`，重放幂等。
- 水位带类型标签（`n:` 整型 / `t:` 时间 / `s:` 字符串），恢复时按原类型绑定 PreparedStatement（PG 的 timestamp 列拒绝 bigint 字面量）。

## 同步状态上报

每轮循环向 `status.url`（Control `POST /api/dimensions/sync-status`，`x-admin-token`）上报位点与计数，无变更也报（心跳）；Control 侧 `GET /api/dimensions/sync-status?gameId=` 返回 `sinceLastPushSeconds`（Agent 存活）与 `sinceLastEventSeconds`（源头新鲜度）。
