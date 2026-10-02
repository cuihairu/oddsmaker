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

- [Startup Guide (Tested)](/operations/startup) - 本机从零拉起全栈的实测步骤与踩坑记录
- [Incident Response](/operations/incident-response) - Handle incidents
- [Troubleshooting](/operations/troubleshooting) - Debug issues

## Deployment Options

### Docker Compose (Recommended, Tested)

```bash
# 一次性前置：podman socket + 镜像加速（详见 Startup Guide）
systemctl --user start podman.socket

# 全栈（根 compose，14 容器，含 postgres/control/gateway）
podman compose up -d --build

# 健康检查（双 UP 即成功）
curl -s http://127.0.0.1:28080/actuator/health   # gateway
curl -s http://127.0.0.1:28085/actuator/health   # control

# Stop services（数据卷保留）
podman compose down
```

> 分体 dev 模式（`make infra-up`）只起中间件不含 postgres，
> `make gateway/control` 的 bootRun 需自备本地 PG，否则请用根 compose。坑位清单见
> [Startup Guide](/operations/startup)。

### Kubernetes (Production)

```bash
# Create namespace
kubectl apply -f deploy/k8s/namespace.yaml

# Deploy services
kubectl apply -f deploy/k8s/

# Check status
kubectl get pods -n oddsmaker
```

### Helm Chart (Recommended for Production)

```bash
# Add Helm repository
helm repo add oddsmaker https://charts.oddsmaker.local

# Install
helm install oddsmaker oddsmaker/oddsmaker \
  --namespace oddsmaker \
  --values values.yaml
```

## Monitoring Stack

The recommended monitoring stack includes:

- **Prometheus**: Metrics collection
- **Grafana**: Visualization and dashboards
- **Alertmanager**: Alert routing and notification
- **Loki**: Log aggregation (optional)

### Quick Setup

```bash
# Deploy monitoring stack
kubectl apply -f deploy/monitoring/

# Access Grafana
kubectl port-forward svc/grafana 3000:80 -n monitoring
```

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

- **PostgreSQL**: Daily full backup, continuous WAL archiving
- **Redis**: RDB snapshots every 15 minutes
- **ClickHouse**: Weekly full, daily incremental

### Recovery Objectives

| Component | RPO | RTO |
|-----------|-----|-----|
| PostgreSQL | 5 minutes | 15 minutes |
| Redis | 1 minute | 5 minutes |
| ClickHouse | 1 hour | 30 minutes |

## Security Checklist

> 2026-10 收尾核验：`[x]` = 仓库已交付且运行时/配置面可证；`[ ]` 附仓库现状证据如实保留（环境执行项与未交付项不假勾）。

- [ ] Enable HTTPS — 部分：k8s control ingress 已配 TLS + ssl-redirect（`deploy/k8s/control-service.yaml`，证书 secret 由运维注入）；网关侧无 ingress 清单、本机 compose 为 HTTP——平台级 HTTPS 待部署环境生效
- [x] Configure CORS — 已交付：Spring Security `SecurityConfig#corsConfigurationSource`（白名单源/方法/请求头/凭证，注册 `/api/**`）
- [ ] Set up firewall rules — 部署环境执行项（仓库无对应配置面，如实保留）
- [x] Enable audit logging — 已交付：`audit_log` 落库 + `GET /api/audit-logs` 查询（策略/密钥/权限/风控动作全记录）
- [ ] Configure rate limiting — 部分：control ingress `rate-limit 100/min` 注解已随仓；网关（公网事件入口）侧未覆盖——待部署环境生效
- [ ] Set up MFA for admin users — 未交付：`MFAConfigEntity` / `SecurityPolicyEntity.mfaRequired` 字段已建，登录链路未接入校验
- [ ] Rotate secrets regularly — 部分：API Key 创建/轮换/禁用已支持；周期轮换节奏属运维执行动作
- [x] Monitor security events — 已交付：风控大屏 `/api/risk-metrics`（风险趋势/命中/处置状态）+ 审计日志查询；告警规则接入属监控栈（见 Monitoring Stack）

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
# Connect to PostgreSQL
kubectl exec -it postgres-0 -n oddsmaker -- psql -U oddsmaker

# Backup database
kubectl exec -it postgres-0 -n oddsmaker -- pg_dump -U oddsmaker oddsmaker > backup.sql

# Restore database
cat backup.sql | kubectl exec -i postgres-0 -n oddsmaker -- psql -U oddsmaker oddsmaker
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

1. **Use Infrastructure as Code**: Manage infrastructure with Terraform/Pulumi
2. **Automate Deployments**: Use CI/CD pipelines
3. **Monitor Everything**: Set up comprehensive monitoring
4. **Test Backups**: Regularly test backup restoration
5. **Document Runbooks**: Keep runbooks up to date
6. **Conduct DR Drills**: Practice disaster recovery
7. **Review Security**: Regular security audits
8. **Optimize Performance**: Regular performance reviews

## Support

For operations support:

- [GitHub Issues](https://github.com/cuihairu/oddsmaker/issues)
- [Slack Channel](https://oddsmaker.slack.com)
- [Email](mailto:ops@oddsmaker.local)
