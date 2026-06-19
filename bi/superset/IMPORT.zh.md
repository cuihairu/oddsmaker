# 导入 Oddsmaker 仪表盘包（Superset）

前提
- 本地已启动 ClickHouse & Superset（见 infra/docker-compose.yml）
- Superset 已初始化管理员账号（首次需要）：
  - 进入容器：`docker exec -it $(docker ps -qf name=superset) bash`
  - 初始化（仅一次）：
    ```bash
    superset fab create-admin \
      --username admin --firstname Admin --lastname User \
      --email admin@oddsmaker.local --password admin
    superset db upgrade
    superset init
    ```
  - 退出容器

打包
```bash
cd bi/superset
./build_bundle.sh
```
将生成：`bi/superset/oddsmaker_dashboard_bundle.zip`

导入
- 浏览器访问 http://localhost:8088 登录 Superset
- Settings -> Import -> 选择 `oddsmaker_dashboard_bundle.zip`
- 导入完成后，在 Databases 中会看到 `Oddsmaker CH`（如导入失败，可手动创建数据库连接并重新导入）
- Dashboards -> `Oddsmaker Overview`

注意
- bundle 中的数据库连接使用：`clickhouse+http://default:@host.docker.internal:8123/default`，如在 Linux 容器无法访问宿主，请改为 ClickHouse 容器名：`clickhouse`，然后在导入后进入 Databases -> Oddsmaker CH -> Edit 调整连接 URI。
- 若导入时提示 UUID 冲突或版本不匹配，可先删除已有对象再导入，或在 bundle YAML 里替换为新的 `uuid` 后重新打包。
