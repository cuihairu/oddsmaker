# 启动指南（本机实测路径）

> 本文按 2026-09-23 在一台 Linux 主机（podman + docker 垫片）上从零拉起全栈的**实测步骤**整理，
> 不是照抄 README 的理想路径。踩过的坑与修过的雷一并记录，后来者照抄即可。

## 一句话路径

```text
podman API socket → 根 docker-compose.yml 全量 up → 健康端点双绿
```

**整栈用根目录 `docker-compose.yml`，不要用 `make infra-up` 的分体 dev 模式**（见下方“已知问题”）。

## 0. 前置（一次性）

```bash
# 1) compose 外部 provider 走 docker API，podman 必须开 socket（用户态，无需 sudo）
systemctl --user start podman.socket
ls -l /run/user/1000/podman/podman.sock   # 确认存在

# 2) 国内镜像加速（写入 ~/.config/containers/registries.conf.d/999-mirror.conf）
#    docker.io / quay.io / ghcr.io / gcr.io 四组 mirror——缺哪组哪组断流 EOF，
#    apicurio 在 quay.io、JRE 基础镜像在 docker.io，两个都实测断过。
```

## 1. 启动

```bash
cd <repo>
podman compose up -d --build          # 首次要构建 control/gateway 镜像，较久
```

## 2. 健康检查

```bash
for p in 28080 28085; do curl -s http://127.0.0.1:$p/actuator/health; done
# 双双返回 {"status":"UP"} 即成功
podman ps --format '{{.Names}}\t{{.Status}}' | grep oddsmaker   # 期望 14 个 Up
```

## 3. 访问地址（按本机实测 IP）

| 服务 | 地址 | 说明 |
|---|---|---|
| gateway | http://192.168.5.188:28080/actuator/health | 容器 8080 偏移发布 |
| control | http://192.168.5.188:28085/actuator/health | 容器 8085 偏移发布 |
| superset | http://192.168.5.188:8088 | BI 看板 |
| clickhouse HTTP | http://192.168.5.188:28123 | 容器 8123 |

（端口偏移约定见根 `docker-compose.yml`；IP 变了用 `hostname -I` 自查。）

## 常用操作

```bash
podman compose logs -f control-service        # 跟日志
podman compose up -d --build control-service  # 单服务改码重建
podman compose down                           # 整栈下线（数据卷保留）
```

## 已知问题（本次首跑修复记录）

根 compose 这条链**此前从未被完整跑通**，逐层剥了四颗雷，全部已修入 main：

1. **`V0.9.8` 权限种子非幂等**：`game.read/user.read/audit.read/system.read` 四 code 在
   `V0.2.3` 已落库（`perm_*` 样式 id），裸 INSERT 撞 `permissions_code_key`。
   修复：迁移前置换 id（`session_replication_role` 临时降 FK）并同步 `role_permissions`
   绑定，INSERT 清单剔除四行，绑定段加 `ON CONFLICT DO NOTHING`。（`b64f0b6`）
2. **`V0.9.11` 改 varchar 撞视图**：`v_user_permissions` 的 `_RETURN` 规则依赖 `users.id`。
   修复：迁移内 `pg_get_viewdef` 存临时表 → `DROP VIEW` → 12 条 `ALTER` → 重建。（`42ed36e`）
3. **`ClickHouseClient` 双构造器**：生产构造器（`@Value` 三参）与测试构造器（`JdbcTemplate`）
   并存且无 `@Autowired`，Spring 找无参构造失败。修复：标注生产构造器。（`f30db0f`）
4. **`WebhookService.asyncExecutor` 注入歧义**：Boot 同时注册 `applicationTaskExecutor` 与
   `taskScheduler`，`Executor` 类型二义（`required=false` 只容忍缺失不容忍歧义）。
   修复：`@Qualifier("applicationTaskExecutor")`。（`1f0970e`）

### Makefile 分体模式的坑

- `make infra-up` 曾引用 infra compose 里**不存在的 `zookeeper`**（已删，见 Makefile 修复）。
- infra compose **没有 postgres**，而 `make gateway` / `make control` 的 bootRun 走
  `localhost:5432` → 在本机会连库失败。dev 分体模式要先自备同名同账号的本地 PG
  （`oddsmaker/oddsmaker`，库 `oddsmaker`），否则请直接用根 compose 全量模式。
- 本机没有 docker，`~/.local/bin/docker` 是 `exec podman "$@"` 垫片，
  compose 插件为 `~/.docker/cli-plugins/docker-compose`（真实 release 版本，先查 tags 再装）。

## 验证一条迁移的固定姿势（防再踩雷）

```bash
(echo "BEGIN;"; cat <sql迁移文件>; echo "ROLLBACK;") \
  | podman exec -i oddsmaker-postgres psql -U oddsmaker -d oddsmaker -v ON_ERROR_STOP=1
# exit 0 且尾行 ROLLBACK = 语句在真库全通过且不落盘；出错立即定位到语句行
```
