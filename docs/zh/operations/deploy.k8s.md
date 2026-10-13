# K8s 部署（示例）

前提
- 已有 Kafka/Schema Registry/ClickHouse 集群（或使用云服务），拿到地址
- 镜像：CI 构建推送单镜像双服务 `ghcr.io/cuihairu/oddsmaker:nightly`（根 `Dockerfile`，`SERVICE=control|gateway` 选服务，与 `deploy/k8s/` 清单对齐）；如需自建，`services/gateway-service/Dockerfile` 与 `services/control-service/Dockerfile` 可各自构建

方式一：Helm（推荐）
```bash
helm upgrade --install oddsmaker ./infra/helm/oddsmaker -n oddsmaker --create-namespace \
  --set image.gateway=your-registry/oddsmaker-gateway:tag \
  --set image.control=your-registry/oddsmaker-control:tag \
  --set env.kafkaBootstrap=kafka:9092 \
  --set env.registryUrl=http://apicurio:8080/apis/registry/v2 \
  --set env.clickhouseUrl=jdbc:clickhouse://clickhouse:8123/default \
  --set env.adminToken=YOUR_STRONG_TOKEN
```
- Gateway: svc/oddsmaker-gateway:8080；Control: svc/oddsmaker-control:8085
- 可选：`hpa.gateway.*` 开启 HPA
 - 可选：Ingress（`ingress.gateway.*` 与 `ingress.control.*`）启用，对外暴露入口
 - 可选：探针与调度（`gateway.*` / `control.*` 下的 `livenessProbe`/`readinessProbe`、`nodeSelector`、`tolerations`、`affinity`、`topologySpreadConstraints`）
 - 可选：Service 注解（`service.*.annotations`）和通用标签（`commonLabels`）

方式二：Raw YAML（`deploy/k8s/`）
```bash
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/postgres.yaml
kubectl apply -f deploy/k8s/redis.yaml
kubectl apply -f deploy/k8s/control-service.yaml
kubectl apply -f deploy/k8s/gateway-service.yaml
# 备份（可选）：kubectl apply -f deploy/k8s/backup-cronjob.yaml
```

注意
- 管理令牌：`oddsmaker.admin.token`（Control），通过 `x-admin-token` 访问 /api/**
- 入口暴露：可用 Ingress/LoadBalancer 暴露 `oddsmaker-gateway` 与 `oddsmaker-control`
- 下游地址：改 `deploy/k8s/control-service.yaml` 与 `gateway-service.yaml` 的 ConfigMap（Kafka/Registry/ClickHouse）
- 认证与安全：接入企业网关/WAF；控制面换强令牌或对接 OIDC；启用 TLS（Ingress/ServiceMesh）

镜像构建（示例）
```bash
# 单镜像双服务（CI 路径，SERVICE 选服务）
docker build -t ghcr.io/cuihairu/oddsmaker:nightly .
docker push ghcr.io/cuihairu/oddsmaker:nightly
# 或按服务自建
cd services/gateway-service && docker build -t your-registry/oddsmaker-gateway:tag . && docker push your-registry/oddsmaker-gateway:tag
cd ../control-service && docker build -t your-registry/oddsmaker-control:tag . && docker push your-registry/oddsmaker-control:tag
```
