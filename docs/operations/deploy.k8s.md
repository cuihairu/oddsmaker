# K8s Deployment (Example)

Prerequisites
- Have an existing Kafka/Schema Registry/ClickHouse cluster (or use a cloud service) and obtain the addresses
- Images: CI builds and pushes a single-image dual-service `ghcr.io/cuihairu/oddsmaker:nightly` (root `Dockerfile`, `SERVICE=control|gateway` selects the service, aligned with the `deploy/k8s/` manifests); to build separately, `services/gateway-service/Dockerfile` and `services/control-service/Dockerfile` can each be built

Option 1: Helm (recommended)
```bash
helm upgrade --install oddsmaker ./infra/helm/oddsmaker -n oddsmaker --create-namespace \
  --set image.gateway=your-registry/oddsmaker-gateway:tag \
  --set image.control=your-registry/oddsmaker-control:tag \
  --set env.kafkaBootstrap=kafka:9092 \
  --set env.registryUrl=http://apicurio:8080/apis/registry/v2 \
  --set env.clickhouseUrl=jdbc:clickhouse://clickhouse:8123/default \
  --set env.adminToken=YOUR_STRONG_TOKEN
```
- Gateway: svc/oddsmaker-gateway:8080; Control: svc/oddsmaker-control:8085
- Optional: `hpa.gateway.*` enables HPA
- Optional: enable Ingress (`ingress.gateway.*` and `ingress.control.*`) to expose endpoints externally
- Optional: probes and scheduling (`livenessProbe`/`readinessProbe`, `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints` under `gateway.*` / `control.*`)
- Optional: Service annotations (`service.*.annotations`) and common labels (`commonLabels`)

Option 2: Raw YAML (`deploy/k8s/`)
```bash
# 首次部署一次性：Secret 已拆至独立 *-secrets.yaml（内含 CHANGE_ME 占位值），
# 填入真实值后手工 apply 一次；发布流水线不 apply 占位 Secret（覆盖即毁产线真实凭据）
kubectl apply -f deploy/k8s/postgres-secrets.yaml
kubectl apply -f deploy/k8s/redis-secrets.yaml
kubectl apply -f deploy/k8s/control-service-secrets.yaml
kubectl apply -f deploy/k8s/gateway-service-secrets.yaml
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/postgres.yaml
kubectl apply -f deploy/k8s/redis.yaml
kubectl apply -f deploy/k8s/control-service.yaml
kubectl apply -f deploy/k8s/gateway-service.yaml
# 备份（可选）：kubectl apply -f deploy/k8s/backup-cronjob.yaml
```

Notes
- Admin token: `oddsmaker.admin.token` (Control), access /api/** via `x-admin-token`
- Credentials: Secret docs are split into `deploy/k8s/*-secrets.yaml` (CHANGE_ME placeholders) — fill real values and apply once manually on first deploy; the release pipeline never applies placeholder Secrets
- Endpoint exposure: `oddsmaker-gateway` and `oddsmaker-control` can be exposed via Ingress/LoadBalancer
- Downstream addresses: change the ConfigMaps in `deploy/k8s/control-service.yaml` and `gateway-service.yaml` (Kafka/Registry/ClickHouse)
- Authentication and security: put behind an enterprise gateway/WAF; use a strong token or OIDC for the control plane; enable TLS (Ingress/ServiceMesh)

Image build (example)
```bash
# 单镜像双服务（CI 路径，SERVICE 选服务）
docker build -t ghcr.io/cuihairu/oddsmaker:nightly .
docker push ghcr.io/cuihairu/oddsmaker:nightly
# 或按服务自建
cd services/gateway-service && docker build -t your-registry/oddsmaker-gateway:tag . && docker push your-registry/oddsmaker-gateway:tag
cd ../control-service && docker build -t your-registry/oddsmaker-control:tag . && docker push your-registry/oddsmaker-control:tag
```
