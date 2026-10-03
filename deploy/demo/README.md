# 演示站点部署手册（deploy/demo）

低配单机跑 Oddsmaker **控制面 + Web 控制台**，对外经 Cloudflare 代理提供 https://oddsmaker.cuihairu.site/ 。

镜像滚动更新由 `.github/workflows/deploy.yml` 自动完成；本目录只放**首次人工落地**与**排障**所需的文件。

## 1. 部署机要求

实测落地机：2 vCPU / 1.6 GB RAM / 40 GB 磁盘（Debian/Ubuntu + docker 29.x）。

1.6 GB 是硬约束，因此：

- 用本目录的 `docker-compose.yml`（postgres + redis + control-service），
  **不要**用根目录 `docker-compose.quickstart.yml`——它的 `control-service` 依赖
  `kafka(healthy)` + `topic-init(completed)`，按服务名挑着 `up` 也会连带拉起 Kafka/Apicurio，
  1.6 GB 机器跑不动（本编排直接省掉 Kafka：分析类接口按 designed 降级 `available=false`）。
- JVM 堆在 `.env`/编排里已按 `-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m` 收窄。

### 1.1 必做系统调优：`vm.swappiness=60`

云主机镜像（Aliyun 系）默认 `vm.swappiness = 0`。内存吃紧时内核会**只回收 page cache、不碰 swap**，
表现为整机“假死”，且极具迷惑性：

| 症状 | 实测 |
|------|------|
| 负载 | load average 42→62（2 vCPU），`ps` 里 CPU 却接近 0 |
| 阻塞栈 | 大量进程卡在 `blk_mq_get_tag` / `jbd2/vda3` / `folio_wait_bit_commo`（含 PID 1 systemd） |
| 现象 | SSH 握手能过、**会话建立后无输出**；nginx 仍秒回；runner 反复掉线 offline |
| 日志 | `systemd-journald: Under memory pressure, flushing caches.` 每十几秒刷一次 |
| 受害面 | control 的 `/actuator/health` 从 69 ms 变成 >50 s 超时（看着像应用挂了，其实是内存抖动） |

```bash
sysctl -w vm.swappiness=60
printf 'vm.swappiness=60\n' > /etc/sysctl.d/99-odds-demo.conf   # 持久化
```

改完观察：`cat /proc/loadavg`（1 分钟内应从 40+ 回落到个位数）、`free -m` 里 swap 开始有占用（正常）。

顺带：阿里云备份代理 `hbrclient` 曾在同一时段卡在块层请求标签上，必要时 `systemctl stop hbrclient`。

## 2. 首次落地（人工，一次性）

```bash
sudo mkdir -p /opt/oddsmaker && cd /opt/oddsmaker
sudo cp <repo>/deploy/demo/docker-compose.yml .
sudo cp <repo>/deploy/demo/env.example .env && sudo vi .env     # 全部有中文注释，密码用 openssl rand -hex 24

# Web 控制台静态资源：CI 会自动发布，首次也可手动
sudo mkdir -p /opt/oddsmaker/web && cp -a <repo>/web/dist/. /opt/oddsmaker/web/

docker compose up -d
curl -fsS http://127.0.0.1:38085/actuator/health        # {"status":"UP"}
```

首次启动含 Flyway 全量迁移 + admin 种子，健康检查可能需要 1~3 分钟。

## 3. 对外接入（Cloudflare 代理）

1. DNS：`oddsmaker` **A 记录 → 部署机公网 IP，橙云开启**（与 cockpit/coding/wingman 同法）。
2. nginx：把 `nginx-oddsmaker.conf` 放到 `/etc/nginx/sites-available/oddsmaker.cuihairu.site`
   并软链到 `sites-enabled/`，`nginx -t && systemctl reload nginx`。
   `:80` 不做 301（CF 以 http 回源），`/api/` 反代 `127.0.0.1:38085`，`/` 托管 SPA。
3. 证书：签发前用自签占位（`/etc/nginx/ssl/oddsmaker/`），此时 CF 的 SSL/TLS 模式须为 **Full**；
   DNS 生效后跑 `sudo bash issue-le-cert.sh` 换 Let's Encrypt 证书，再把模式收紧到 **Full (strict)**。

```bash
# 回源自查（不经 CF）：CF 报 502/525 而这里 200，就是 DNS 或 SSL 模式没配对
curl -I -H 'Host: oddsmaker.cuihairu.site' http://<部署机IP>/
curl -k -I --resolve oddsmaker.cuihairu.site:443:<部署机IP> https://oddsmaker.cuihairu.site/
```

## 4. CI 部署腿

`.github/workflows/deploy.yml`：`Nightly Build` 成功后 `workflow_run` 跟进，也支持手动派发。

- `build-web`（GitHub-hosted）：`pnpm -C web build` 出 `web/dist` artifact——vite 构建的内存峰值不落在 1.6 G 机器上。
- `deploy`（self-hosted `runner-docker`）：拉 `:nightly` 镜像 → `docker compose up -d` →
  轮询 `/actuator/health` 最多 5 分钟（首启含 Flyway）→ 原子换名发布 web 静态资源 → 记录 `.last-good-digest`。
- 任一步失败：`Rollback on failure` 把镜像 tag 退回上一个 digest 再起栈，回滚成功也判本次失败（让变更可见）。

runner 注册（部署机上一次性）：

```bash
/opt/runner-docker/bin/config.sh --url https://github.com/cuihairu/oddsmaker \
  --token <registration-token> --labels runner-docker --unattended --replace
sudo /opt/runner-docker/bin/svc.sh install runner-docker && sudo systemctl start actions.runner.*runner-docker
```

## 5. 演示账号约定

- 演示站点对外公开，账号必须是**新建的只读账号**，禁止放真实管理员（`admin/admin123` 只在种子数据里）。
- 建号（X-Admin-Token 直通管理面）：

```bash
TOKEN=$(curl -sS -X POST http://127.0.0.1:38085/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' | jq -r .token)
HASH=$(python3 -c 'import bcrypt,sys;print(bcrypt.hashpw(sys.argv[1].encode(),bcrypt.gensalt(10,prefix=b"2a")).decode())' "$PW")
curl -sS -X POST http://127.0.0.1:38085/api/users -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"demo\",\"displayName\":\"演示账号\",\"passwordHash\":\"$HASH\",\"roles\":[\"VIEWER\"],\"status\":\"ACTIVE\"}"
```

> `POST /api/users` 收的是已 bcrypt 的 `passwordHash`（`UserService.createUser` 不做哈希），
> 角色留空时服务默认给 `VIEWER`。

- 角色分配（**必做**）：权限门读 `user_role_assignments`，`POST /api/users` 只写
  `users.roles` 不落该表——不补这行，账号登录后所有受权限门端点全量拒绝：

```bash
docker exec -i oddsmaker-demo-postgres psql -U oddsmaker -d oddsmaker <<'SQL'
INSERT INTO user_role_assignments (user_id, role_id, enabled, assigned_by, assigned_at)
SELECT u.id, 'role_viewer', TRUE, 'demo-seed', now()
FROM users u
WHERE u.username = 'demo'
  AND NOT EXISTS (
    SELECT 1 FROM user_role_assignments a
    WHERE a.user_id = u.id AND a.role_id = 'role_viewer' AND a.enabled
  );
SQL
```