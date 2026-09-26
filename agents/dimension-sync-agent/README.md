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
- kafka：**端到端已实测**（2026-09-26，apache/kafka:3.7.0 单节点 KRaft，`KafkaSourceBrokerE2ETest` 六用例，broker 不可达自动 SKIP 不误报）：earliest 起步、断点 seek 续传（checkpoint 位点优先于 broker group offset，旧断点可重放）、运行中扩分区追赶、坏消息（解析失败/缺 resource_id）跳过但 offset 前进（防毒丸卡死）、drain 上限 100 轮有界分批且续传无丢失——五项核对点全绿。实测暴露并修复两点：① `KafkaSource` 位点簿记缺陷——本轮无新数据的分区曾在 cursor 丢条目，下一轮被 seekToBeginning 整段重放；② 扩分区感知默认依赖 metadata.max.age（5min）过于迟钝，压到 1s + assign 前 listTopics 全刷。未覆盖：TLS/SASL 鉴权、多 broker 集群、长稳/性能压测。

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
