# dimension-sync-agent（oddsmaker-agent）

维度数据同步 Agent：部署在游戏方内网，读取本地 DB / CSV 源头，推送维度变更到 Oddsmaker Gateway `/v1/batch`，并向 Control 上报同步状态。设计与链路见 `docs/zh/reference/dimension-sync.md`。

**零仓库内依赖**（仅 Jackson + JDK HttpClient + runtimeOnly JDBC 驱动），整个目录可 git subtree 拆出为独立仓库。

## Source 支持

| type | 说明 | 增量机制 |
|---|---|---|
| `mysql` / `postgres` | 增量查询 | 单 `?` 占位绑定水位，查询必须以 `ORDER BY <水位列>` 结尾 |
| `csv` | 扫描目录下 `*.csv`（文件名序） | 文件粒度断点：处理完整文件才记 checkpoint，增量 = 投递新文件 |

控制列（大小写不敏感）：`dim_type`/`dimension_type`（resource/items→item，levels→level）、`resource_id`/`item_code`/`level_id`/`id`、`op`（upsert/delete）、`version_ts`（epoch millis 或 ISO-8601）；其余列进 `attributes`。

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
