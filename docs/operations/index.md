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

- [Incident Response](/operations/incident-response) - Handle incidents
- [Troubleshooting](/operations/troubleshooting) - Debug issues

## Deployment Options

### Docker Compose (Development)

```bash
# Start local infrastructure
docker-compose -f infra/docker-compose.yml up -d

# View logs
docker-compose logs -f

# Stop services
docker-compose down
```

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

- [ ] Enable HTTPS
- [ ] Configure CORS
- [ ] Set up firewall rules
- [ ] Enable audit logging
- [ ] Configure rate limiting
- [ ] Set up MFA for admin users
- [ ] Rotate secrets regularly
- [ ] Monitor security events

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
