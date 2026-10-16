# Startup Guide (Locally Tested Path)

> 🌐 Language: **English** · 中文 → [startup.md](startup.md)

> This document is organized from the **tested steps** of bringing up the full stack from scratch on a Linux host (podman + docker shim) on 2026-09-23,
> not an ideal path copied from the README. The pitfalls hit and the landmines fixed are recorded along the way so that those who follow can simply copy them.

## One-line Path

```text
podman API socket → 根 docker-compose.yml 全量 up → 健康端点双绿
```

**Use the root `docker-compose.yml` for the whole stack; do not use the split dev mode of `make infra-up`** (see "Known Issues" below).

## 0. Prerequisites (one-time)

```bash
# 1) compose 外部 provider 走 docker API，podman 必须开 socket（用户态，无需 sudo）
systemctl --user start podman.socket
ls -l /run/user/1000/podman/podman.sock   # 确认存在

# 2) 国内镜像加速（写入 ~/.config/containers/registries.conf.d/999-mirror.conf）
#    docker.io / quay.io / ghcr.io / gcr.io 四组 mirror——缺哪组哪组断流 EOF，
#    apicurio 在 quay.io、JRE 基础镜像在 docker.io，两个都实测断过。
```

## 1. Start

```bash
cd <repo>
podman compose up -d --build          # 首次要构建 control/gateway 镜像，较久
```

## 2. Health Check

```bash
for p in 28080 28085; do curl -s http://127.0.0.1:$p/actuator/health; done
# 双双返回 {"status":"UP"} 即成功
podman ps --format '{{.Names}}\t{{.Status}}' | grep oddsmaker   # 期望 15 个 Up
```

## 3. Access Addresses (based on the locally tested IP)

| Service | Address | Description |
|---|---|---|
| gateway | http://192.168.5.188:28080/actuator/health | Container 8080 published with an offset |
| control | http://192.168.5.188:28085/actuator/health | Container 8085 published with an offset |
| superset | http://192.168.5.188:8088 | BI dashboard |
| clickhouse HTTP | http://192.168.5.188:28123 | Container 8123 |

(For the port-offset convention, see the root `docker-compose.yml`; if the IP changes, check it yourself with `hostname -I`.)

## Common Operations

```bash
podman compose logs -f control-service        # 跟日志
podman compose up -d --build control-service  # 单服务改码重建
podman compose down                           # 整栈下线（数据卷保留）
```

## Known Issues (fixes from this first run)

This chain of the root compose **had never been fully run before**; four landmines were peeled off layer by layer, all fixed into main
(upstream commits; the shallow clone of this repo cannot verify the hashes):

1. **`V0.9.8` permission seed not idempotent**: the four codes `game.read/user.read/audit.read/system.read` were
   already in the database in `V0.2.3` (with `perm_*`-style ids), and a bare INSERT collided with `permissions_code_key`.
   Fix: swap the ids before the migration (`session_replication_role` temporarily lowers FKs) and sync the `role_permissions`
   bindings, remove the four rows from the INSERT list, and add `ON CONFLICT DO NOTHING` to the binding section.
2. **`V0.9.11` varchar change hit a view**: the `_RETURN` rule of `v_user_permissions` depends on `users.id`.
   Fix: inside the migration, store `pg_get_viewdef` to a temp table → `DROP VIEW` → 12 `ALTER` statements → recreate.
3. **`ClickHouseClient` dual constructors**: the production constructor (`@Value`, three params) and the test constructor (`JdbcTemplate`)
   coexisted with no `@Autowired`, so Spring failed to find a no-arg constructor. Fix: annotate the production constructor.
4. **`WebhookService.asyncExecutor` injection ambiguity**: Boot registers both `applicationTaskExecutor` and
   `taskScheduler`, making the `Executor` type ambiguous (`required=false` only tolerates absence, not ambiguity).
   Fix: `@Qualifier("applicationTaskExecutor")`.

### Pitfalls of the Makefile Split Mode

- `make infra-up` once referenced a **nonexistent `zookeeper`** in the infra compose (removed; see the Makefile fix).
- The infra compose **has no postgres**, while the bootRun of `make gateway` / `make control` goes to
  `localhost:5432` → it will fail to connect to the database locally. The dev split mode requires a local PG of your own with the same name and account
  (`oddsmaker/oddsmaker`, database `oddsmaker`), otherwise use the root compose full mode directly.
- There is no docker on this machine; `~/.local/bin/docker` is a `exec podman "$@"` shim,
  and the compose plugin is `~/.docker/cli-plugins/docker-compose` (a real release version; check the tags before installing).

## A Fixed Way to Validate a Migration (to avoid hitting landmines again)

```bash
(echo "BEGIN;"; cat <sql迁移文件>; echo "ROLLBACK;") \
  | podman exec -i oddsmaker-postgres psql -U oddsmaker -d oddsmaker -v ON_ERROR_STOP=1
# exit 0 且尾行 ROLLBACK = 语句在真库全通过且不落盘；出错立即定位到语句行
```
