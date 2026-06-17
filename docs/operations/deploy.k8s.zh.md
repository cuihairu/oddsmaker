# K8s 部署（示例）

前提
- 已有 Kafka/Schema Registry/ClickHouse 集群（或使用云服务），拿到地址
- 已构建并推送镜像：
  - services/gateway-service/Dockerfile -> your-registry/pit-gateway:tag
  - services/control-service/Dockerfile -> your-registry/pit-control:tag

方式一：Helm（推荐）
```bash
helm upgrade --install pit ./infra/helm/pit -n pit --create-namespace \
  --set image.gateway=your-registry/pit-gateway:tag \
  --set image.control=your-registry/pit-control:tag \
  --set env.kafkaBootstrap=kafka:9092 \
  --set env.registryUrl=http://apicurio:8080/apis/registry/v2 \
  --set env.clickhouseUrl=jdbc:clickhouse://clickhouse:8123/default \
  --set env.adminToken=YOUR_STRONG_TOKEN
```
- Gateway: svc/pit-gateway:8080；Control: svc/pit-control:8085
- 可选：`hpa.gateway.*` 开启 HPA
 - 可选：Ingress（`ingress.gateway.*` 与 `ingress.control.*`）启用，对外暴露入口
 - 可选：探针与调度（`gateway.*` / `control.*` 下的 `livenessProbe`/`readinessProbe`、`nodeSelector`、`tolerations`、`affinity`、`topologySpreadConstraints`）
 - 可选：Service 注解（`service.*.annotations`）和通用标签（`commonLabels`）

方式二：Raw YAML
```bash
kubectl apply -f infra/k8s/namespace.yaml
kubectl apply -f infra/k8s/config.yaml
kubectl apply -f infra/k8s/control.yaml
kubectl apply -f infra/k8s/gateway.yaml
```

注意
- 管理令牌：`pit.admin.token`（Control），通过 `x-admin-token` 访问 /api/**
- 入口暴露：可用 Ingress/LoadBalancer 暴露 `pit-gateway` 与 `pit-control`
- 下游地址：修改 values.yaml 或 config.yaml 对应的 Kafka/Registry/ClickHouse 地址
- 认证与安全：接入企业网关/WAF；控制面换强令牌或对接 OIDC；启用 TLS（Ingress/ServiceMesh）

镜像构建（示例）
```bash
# Gateway
cd services/gateway-service
docker build -t your-registry/pit-gateway:tag .
docker push your-registry/pit-gateway:tag
# Control
cd ../control-service
docker build -t your-registry/pit-control:tag .
docker push your-registry/pit-control:tag
```
