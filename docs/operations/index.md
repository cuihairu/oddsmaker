# Operations Guide

Welcome to the Oddsmaker Operations Guide. This section provides documentation for deploying, monitoring, and maintaining the Oddsmaker platform.

## Overview

This guide covers:

- **Deployment**: Deploy Oddsmaker to various environments
- **Monitoring**: Set up monitoring and alerting
- **Incident Response**: Handle incidents and outages
- **Troubleshooting**: Debug common issues
- **Performance**: Optimize system performance
- **Security**: Harden security configurations
- **Backup**: Backup and disaster recovery

## Quick Links

- [Startup Guide (Tested)](/operations/startup) - Tested steps and pitfalls for bringing up the full stack from scratch on a local machine
- [Incident Response](/operations/incident-response) - Handle incidents
- [Troubleshooting](/operations/troubleshooting) - Debug issues

## Deployment Options

### Docker Compose (Recommended, Tested)

```bash
# 一次性前置：podman socket + 镜像加速（详见 Startup Guide）
systemctl --user start podman.socket

# 全栈（根 compose，16 容器，含 postgres/control/gateway）
podman compose up -d --build

# 健康检查（双 UP 即成功）
curl -s http://127.0.0.1:28080/actuator/health   # gateway
curl -s http://127.0.0.1:28085/actuator/health   # control

# Stop services（数据卷保留）
podman compose down
```

> The split dev mode (`make infra-up`) only starts the middleware and does not include postgres;
> the bootRun of `make gateway/control` requires a local PG of your own, otherwise use the root compose. See the
> [Startup Guide](/operations/startup) for the list of pitfalls.

### Kubernetes (Production)

```bash
# First-time only: Secret docs are split into *-secrets.yaml files carrying CHANGE_ME
# placeholders — fill real values, then apply once manually. The release pipeline never
# applies placeholder Secrets (it would clobber real production credentials)
kubectl apply -f deploy/k8s/postgres-secrets.yaml
kubectl apply -f deploy/k8s/redis-secrets.yaml
kubectl apply -f deploy/k8s/control-service-secrets.yaml
kubectl apply -f deploy/k8s/gateway-service-secrets.yaml
# Deploy services (namespace first, then manifests; backup CronJob is optional)
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/postgres.yaml
kubectl apply -f deploy/k8s/redis.yaml
kubectl apply -f deploy/k8s/control-service.yaml
kubectl apply -f deploy/k8s/gateway-service.yaml
# kubectl apply -f deploy/k8s/backup-cronjob.yaml   # optional

# Check status
kubectl get pods -n oddsmaker
```

### Helm Chart (Recommended for Production)

```bash
# Install from the in-repo chart
helm upgrade --install oddsmaker ./infra/helm/oddsmaker \
  --namespace oddsmaker --create-namespace \
  --set image.gateway=your-registry/oddsmaker-gateway:tag \
  --set image.control=your-registry/oddsmaker-control:tag
```

## Monitoring Stack

The recommended monitoring stack includes:

- **Prometheus**: Metrics collection
- **Grafana**: Visualization and dashboards
- **Alertmanager**: Alert routing and notification
- **Loki**: Log aggregation (optional)

### Quick Setup

`deploy/monitoring/` contains **configuration files** (not Kubernetes manifests) — mount them into an existing Prometheus / Grafana installation:

```bash
# prometheus.yaml  → Prometheus scrape config (scrape targets + rule_files)
# alerts/oddsmaker-alerts.yml → alerting rules (referenced from prometheus.yaml)
# grafana-dashboard.json → Grafana dashboard export (import via Grafana UI)

# Access Grafana (after deploying the stack with your own manifests or operator)
kubectl port-forward svc/grafana 3000:80 -n monitoring
```

The `deploy/k8s/` service manifests carry `prometheus.io/scrape` annotations — point your Prometheus Operator ServiceMonitor or scrape config at those ports.

## Key Metrics

### Application Metrics

- Request rate (RPS)
- Response time (P50, P95, P99)
- Error rate
- Active connections

### Infrastructure Metrics

- CPU usage
- Memory usage
- Disk I/O
- Network I/O

### Business Metrics

- Events processed
- Active users
- API key usage
- Experiment participants

## Alerting Rules

Critical alerts include:

- Service down
- High error rate (>5%)
- High latency (P95 > 2s)
- Database connection issues
- Memory/CPU exhaustion

## Disaster Recovery

### Backup Schedule

> Configuration delivered in the repository: postgres WAL archiving (`archive_mode=on` + `archive_timeout=30s` + 30-day in-place retention cleanup, archiving goes to a dedicated volume/PVC), Redis snapshots + AOF (`save 60 10000/300 10/900 1` + `appendfsync everysec`), ClickHouse full/incremental scripts (`deploy/backup/clickhouse-backup.sh`, 90-day retention), pg_dump full backups with 30-day retention (`deploy/backup/postgres-backup.sh` + `deploy/k8s/backup-cronjob.yaml`).
>
> The S3 off-site copy requires the aws CLI to exist in the runtime environment. The k8s CronJob image `postgres:16-alpine` does not include that CLI, so the script detects its presence and skips with a WARN (the backup still lands locally and retention cleanup proceeds as usual), without interrupting the job — for the off-site copy, install aws-cli in the base image, or hand it over to an external backup controller.

- **PostgreSQL**: Daily full backup, continuous WAL archiving
- **Redis**: RDB snapshots every 15 minutes
- **ClickHouse**: Weekly full, daily incremental

### Recovery Objectives

> Target RPO/RTO (configuration is in the repo: postgres WAL archiving meets an RPO of 5 minutes; pending verification in the deployment environment).

| Component | RPO | RTO |
|-----------|-----|-----|
| PostgreSQL | 5 minutes | 15 minutes |
| Redis | 1 minute | 5 minutes |
| ClickHouse | 1 hour | 30 minutes |

## Security Checklist

> 2026-10 final verification: `[x]` = delivered in the repository and provable at runtime/configuration level; `[ ]` keeps the current repository evidence as-is (environment-execution items and undelivered items are not falsely checked).

- [x] Enable HTTPS — Delivered: both the control-plane and gateway ingresses are configured with TLS + ssl-redirect (`deploy/k8s/control-service.yaml`, `deploy/k8s/gateway-service.yaml`; the certificate secret is injected by ops; the gateway entry point is `ingest.oddsmaker.local`). Local compose is HTTP (expected for local/dev); platform-level HTTPS takes effect with the k8s manifests
- [x] Configure CORS — Delivered: Spring Security `SecurityConfig#corsConfigurationSource` (whitelisted origins/methods/headers/credentials, registered for `/api/**`)
- [ ] Set up firewall rules — An item to execute in the deployment environment (no corresponding configuration surface in the repository; kept as-is)
- [x] Enable audit logging — Delivered: `audit_log` persisted to the database + `GET /api/audit-logs` query (fully records policy/key/permission/risk-control actions)
- [x] Configure rate limiting — Delivered: the control-plane ingress `rate-limit 100/min` annotation is in the repo (`deploy/k8s/control-service.yaml`) + in-app rate limiting at the gateway public event entry `/v1/batch` (`RateLimitFilter`: per-apiKey `rpm` and per-IP `ipRpm`, policy-level values can override, anonymous fallback bucket, over-limit returns 429 + `Retry-After`; e9ebbac, `GatewayFiltersCoverageTest` 5 cases + `RateLimitAndPolicyTest` 5 cases green, gateway module 237 cases 0 failures)
- [x] Set up MFA for admin users — Delivered: the login flow is wired to MFA verification (`AuthController` checks `isUserMFAEnabled` after password verification; without a code it returns a `{mfaRequired:true}` challenge, with a code it verifies via `verifyMFA`, and failure returns 401 + LOGIN_FAILED audit; the frontend `LoginView` pops up a verification-code input for resend). The `/api/security/mfa/*` configuration endpoints and the `SecurityPolicyEntity.mfaRequired` policy field are both in place
- [x] Rotate secrets regularly — Delivered: the API Key auto-rotation executor (`V0.9.27` adds the `last_rotated_at` timeline; `ControlService.rotateDueApiKeys` scans every hour for keys that are ACTIVE and `auto_rotate=TRUE`, issues a new `sk_` secret when due while the `pk_` identifier stays unchanged, falls back to 90 days when `rotation_days` is empty/≤0, and writes an audit log on rotation; `PUT /api-keys/{key}` reads and writes `autoRotate`/`rotationDays`). The key-value rotation action is in the repo; the specific period is configured by `rotation_days` and the scheduling period
- [x] Monitor security events — Delivered: risk-control dashboard `/api/risk-metrics` (risk trends/hits/handling status) + audit log queries; alert rule integration belongs to the monitoring stack (see Monitoring Stack)

## On-Call Handbook

### Severity Levels

| Level | Description | Response Time |
|-------|-------------|---------------|
| P1 | Service down | 15 minutes |
| P2 | Major feature unavailable | 30 minutes |
| P3 | Degraded performance | 2 hours |
| P4 | Minor issue | 24 hours |

### Escalation Path

1. On-call Engineer
2. Team Lead
3. Engineering Manager
4. CTO

## Useful Commands

### Kubernetes

```bash
# View pods
kubectl get pods -n oddsmaker

# View logs
kubectl logs -f deployment/oddsmaker-control -n oddsmaker

# Scale deployment
kubectl scale deployment/oddsmaker-control --replicas=3 -n oddsmaker

# Port forward
kubectl port-forward svc/oddsmaker-control 8085:80 -n oddsmaker
```

### Database

```bash
# Connect to PostgreSQL (postgres is a Deployment; kubectl resolves the pod)
kubectl exec -it deployment/oddsmaker-postgres -n oddsmaker -- psql -U oddsmaker

# Backup database
kubectl exec -it deployment/oddsmaker-postgres -n oddsmaker -- pg_dump -U oddsmaker oddsmaker > backup.sql

# Restore database
cat backup.sql | kubectl exec -i deployment/oddsmaker-postgres -n oddsmaker -- psql -U oddsmaker oddsmaker
```

### Application

```bash
# View application logs
kubectl logs -f deployment/oddsmaker-control -n oddsmaker

# Check health
curl http://localhost:8086/actuator/health

# View metrics
curl http://localhost:8086/actuator/prometheus
```

## Best Practices

1. Manage infrastructure with Terraform/Pulumi.
2. Deploy through CI/CD pipelines.
3. Set up monitoring on every service and dependency.
4. Test backup restoration regularly.
5. Keep runbooks up to date.
6. Run disaster recovery drills.
7. Audit security on a schedule.
8. Review performance regularly and act on the findings.

## Support

For operations support:

- [GitHub Issues](https://github.com/cuihairu/oddsmaker/issues)
- [Slack Channel](https://oddsmaker.slack.com)
- [Email](mailto:ops@oddsmaker.local)
