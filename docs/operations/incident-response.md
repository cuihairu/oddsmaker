# Oddsmaker Incident Response Runbook

## Overview

This runbook provides procedures for responding to incidents affecting the Oddsmaker Gaming Analytics Platform.

## Severity Levels

| Level | Description | Response Time | Example |
|-------|-------------|---------------|---------|
| P1 - Critical | Service completely down, data loss | 15 minutes | Database failure, security breach |
| P2 - High | Major feature unavailable | 30 minutes | API errors, authentication failure |
| P3 - Medium | Degraded performance | 2 hours | Slow queries, high latency |
| P4 - Low | Minor issue, workaround available | 24 hours | UI bug, non-critical feature issue |

## Incident Response Process

### 1. Detection and Alert

**Automated Alerts:**
- Prometheus/Grafana alerts
- Health check failures
- Error rate spikes
- Performance degradation

**Manual Detection:**
- Customer reports
- Support tickets
- Monitoring dashboard review

### 2. Initial Response

**First 5 Minutes:**
1. Acknowledge the alert
2. Assess severity level
3. Notify on-call team
4. Create incident ticket

**First 15 Minutes:**
1. Gather initial information
2. Identify affected components
3. Determine scope of impact
4. Communicate status to stakeholders

### 3. Investigation

**Information Gathering:**
```bash
# Check service status
kubectl get pods -n oddsmaker
kubectl describe pod <pod-name> -n oddsmaker

# View logs
kubectl logs <pod-name> -n oddsmaker --tail=100
kubectl logs <pod-name> -n oddsmaker -f

# Check events
kubectl get events -n oddsmaker --sort-by='.lastTimestamp'

# Check resource usage
kubectl top pods -n oddsmaker
kubectl top nodes
```

**Database Investigation:**
```sql
-- Check active connections
SELECT count(*) FROM pg_stat_activity;

-- Check long-running queries
SELECT pid, now() - pg_stat_activity.query_start AS duration, query
FROM pg_stat_activity
WHERE (now() - pg_stat_activity.query_start) > interval '5 minutes';

-- Check locks
SELECT * FROM pg_locks WHERE NOT granted;

-- Check database size
SELECT pg_database.datname, pg_size_pretty(pg_database_size(pg_database.datname))
FROM pg_database ORDER BY pg_database_size(pg_database.datname) DESC;
```

### 4. Mitigation

**Common Mitigation Actions:**

**Service Restart:**
```bash
kubectl rollout restart deployment/oddsmaker-control -n oddsmaker
```

**Scale Up:**
```bash
kubectl scale deployment/oddsmaker-control --replicas=5 -n oddsmaker
```

**Rollback:**
```bash
kubectl rollout undo deployment/oddsmaker-control -n oddsmaker
```

**Database Failover:**
```bash
# Promote standby to primary
pg_ctl promote -D /var/lib/postgresql/data
```

### 5. Resolution

**Resolution Steps:**
1. Implement fix (code change, configuration, infrastructure)
2. Test fix in staging environment
3. Deploy to production
4. Verify resolution
5. Monitor for recurrence

### 6. Post-Incident

**Post-Incident Review:**
1. Document timeline
2. Identify root cause
3. Determine contributing factors
4. Create action items
5. Update runbooks

**Post-Incident Template:**
```markdown
# Incident Report: [Title]

## Summary
- **Date**: [Date]
- **Duration**: [Duration]
- **Severity**: [P1/P2/P3/P4]
- **Impact**: [Description of impact]

## Timeline
- [Time] - Incident detected
- [Time] - Investigation started
- [Time] - Root cause identified
- [Time] - Fix implemented
- [Time] - Incident resolved

## Root Cause
[Description of root cause]

## Contributing Factors
- [Factor 1]
- [Factor 2]

## Resolution
[Description of resolution]

## Action Items
- [ ] [Action 1]
- [ ] [Action 2]

## Lessons Learned
- [Lesson 1]
- [Lesson 2]
```

## Common Incidents

### Incident: High Error Rate

**Symptoms:**
- Error rate > 5%
- 5xx responses increasing
- Customer complaints

**Investigation:**
```bash
# Check error logs
kubectl logs -l app=oddsmaker-control -n oddsmaker --tail=1000 | grep -i error

# Check recent deployments
kubectl rollout history deployment/oddsmaker-control -n oddsmaker

# Check resource usage
kubectl top pods -n oddsmaker
```

**Resolution:**
1. If recent deployment: Rollback
2. If resource issue: Scale up
3. If code bug: Hotfix deployment

### Incident: Database Connection Issues

**Symptoms:**
- Connection timeout errors
- Connection pool exhaustion
- Slow queries

**Investigation:**
```sql
-- Check connection count
SELECT count(*) FROM pg_stat_activity;

-- Check connection by state
SELECT state, count(*) FROM pg_stat_activity GROUP BY state;

-- Check waiting queries
SELECT * FROM pg_stat_activity WHERE wait_event_type IS NOT NULL;
```

**Resolution:**
1. Kill long-running queries
2. Increase connection pool size
3. Optimize queries
4. Scale database

### Incident: High Memory Usage

**Symptoms:**
- OOM kills
- Pod restarts
- High garbage collection

**Investigation:**
```bash
# Check memory usage
kubectl top pods -n oddsmaker

# Check JVM heap
curl http://localhost:8086/actuator/metrics/jvm.memory.used

# Check for memory leaks
jmap -histo <pid> | head -20
```

**Resolution:**
1. Increase memory limits
2. Optimize JVM settings
3. Fix memory leak
4. Scale horizontally

### Incident: Security Breach

**Symptoms:**
- Unauthorized access attempts
- Suspicious audit logs
- Data exfiltration alerts

**Investigation:**
```bash
# Check audit logs
kubectl logs -l app=oddsmaker-control -n oddsmaker | grep -i "security\|unauthorized\|breach"

# Check failed login attempts
psql -c "SELECT * FROM audit_logs WHERE action = 'LOGIN' AND result = 'FAILURE' ORDER BY created_at DESC LIMIT 100;"

# Check suspicious IPs
psql -c "SELECT client_ip, count(*) FROM audit_logs WHERE result = 'FAILURE' GROUP BY client_ip ORDER BY count DESC LIMIT 20;"
```

**Resolution:**
1. Block suspicious IPs
2. Revoke compromised credentials
3. Enable additional security measures
4. Notify security team
5. Preserve evidence

## Alert Runbook (oddsmaker-alerts.yml)

Per-alert first-response guide for the rule set shipped in `deploy/monitoring/alerts/oddsmaker-alerts.yml` (24 rules; rule names are stable IDs referenced by contract tests). Queries below work on both monitoring faces: Prometheus UI (`:9090`, compose stack) or Grafana.

| Alert | Severity | Triggered when | First checks / action |
|---|---|---|---|
| OddsmakerControlServiceDown | critical | `up{job="oddsmaker-control"}=0` for 1m | Check control container status and logs; if intentionally stopped, expect this alert — restart or fix the failing start (see `RootComposeSecretsContractTest` for required env). |
| OddsmakerGatewayServiceDown | critical | `up{job="oddsmaker-gateway"}=0` for 1m | Ingestion entry is down: check gateway logs; verify Kafka/Apicurio deps are up. Ingestion is rejected while down. |
| HighErrorRate | warning | 5xx > 5% of requests for 2m | Drill down `http_server_requests_seconds_count{status=~"5.."}` by `uri`; check recent deploys and downstream health. |
| HighLatency | warning | P95 > 2s for 2m | Compare `http_server_requests_seconds_bucket` by uri; check DB pool and downstream latency. |
| HighMemoryUsage | warning | JVM heap > 85% max for 5m | Watch heap trend; check for leaks before restarting; `jvm_gc_pause` histogram. |
| HighCpuUsage | warning | CPU > 80% for 5m | Identify hot threads; check for load spikes or runaway loops. |
| DatabaseConnectionPoolExhausted | critical | Hikari active/max > 90% for 2m | Find connection leaks (`hikaricp_connections_pending`); check for slow queries holding connections. |
| HighDatabaseQueryTime | warning | P95 DB query > 1s for 2m | Look for missing indexes / long transactions in PG (`pg_stat_statements`). |
| WebhookDeliveryFailing | warning | `rate(oddsmaker_webhook_delivery_total{outcome="failed"}[5m]) > 0` for 5m | Outbound alert delivery is broken — check the failing config's `url`/secret and the `webhook_logs` retry counters. Failures are logged and either retried (counted as `retry_scheduled`) or dropped; there is no delivery queue, so the config must be fixed. Test deliveries are not counted. |
| RiskActionFailing | warning | `rate(oddsmaker_risk_action_total{outcome="failed"}[5m]) > 0` for 5m | Risk actions (block/review/throttle/mark) are failing to execute — read the control-service error log for the event id and fix the dependency (block list write, review queue, game-server webhook). Outcome is `ok`, `failed`, or `decision_rejected` (illegal state transition, not an infra failure); unknown actions are clamped to `action="other"`. |
| PostgresDown | critical | exporter `up=0` for 1m | Check postgres container + exporter. Note: exporter `up=1` only proves HTTP — verify `pg_stat` series exist to rule out auth failure. |
| HighDatabaseConnections | warning | `pg_stat_activity_count` > 80 | Identify idle/sleeping backends (`pg_stat_activity`); terminate offenders or raise limits. |
| DatabaseReplicationLag | warning | Replication lag > 30s for 2m | Check standby replay; `pg_stat_replication` on primary. |
| RedisDown | critical | exporter `up=0` for 1m | Check redis container; verify sessions/cache consumers fail over gracefully. |
| RedisHighMemoryUsage | warning | used/max > 85% for 5m (silent when maxmemory=0) | Check key growth and eviction policy; `maxmemory` unconfigured is guarded by `redis_memory_max_bytes > 0`. |
| RedisHighEvictionRate | warning | Evictions > 100/s for 2m | Working set exceeds budget: raise maxmemory or fix TTLs. |
| KafkaDown | critical | exporter `up=0` for 1m | Check kafka container; producers (gateway) will block/fail. |
| KafkaHighConsumerLag | warning | Group lag > 10000 for 5m | Check consumer health (`kafka_consumergroup_*`); scale or restart the lagging consumer. |
| FlinkJobManagerDown | critical | `up{job="flink"}=0` for 1m | k8s face only (compose local-executor jobs have no JobManager endpoint). Check JM pod/container. |
| FlinkJobFailed | critical | JM reports failed job | Restart the job; check checkpoint state and source lag before resuming. |
| ClickHouseDown | critical | `up{job="clickhouse"}=0` for 1m | Check CH container (compose: native metrics on `:29363`); analytics APIs degrade to `available=false` while down. |
| ClickHouseWriteFailures | warning | Failed INSERTs > 0/s for 5m | Inspect `system.query_log` for `type='ExceptionWhileProcessing'`; check schema mismatch / disk full. |
| ClickHouseHighQueryTime | warning | Avg query > 2s for 5m | Check `system.query_log` slow queries; look for scans on large parts, missing ORDER BY keys. |
| ClickHouseDiskSpaceLow | warning | Free/total on default disk < 15% | Free disk or drop expired partitions; growth history in `ClickHouseAsyncMetrics_DiskUsed_default`. |

## Escalation Matrix

| Severity | Initial Responder | Escalation (30 min) | Escalation (1 hour) |
|----------|-------------------|---------------------|---------------------|
| P1 | On-call Engineer | Engineering Manager | CTO |
| P2 | On-call Engineer | Engineering Manager | CTO |
| P3 | On-call Engineer | Team Lead | Engineering Manager |
| P4 | On-call Engineer | Team Lead | - |

## Communication Templates

### Internal Communication

**Initial Notification:**
```
Incident Alert - [Severity]

Service: Oddsmaker Control Service
Impact: [Description]
Status: Investigating
ETA: [Time]

Updates will follow every [15/30/60] minutes.
```

**Status Update:**
```
Incident Update - [Severity]

Service: Oddsmaker Control Service
Status: [Investigating/Identified/Monitoring/Resolved]
Impact: [Current impact]
Progress: [What's been done]
Next Steps: [What's planned]

Next update in [15/30/60] minutes.
```

**Resolution Notification:**
```
✅ Incident Resolved - [Severity]

Service: Oddsmaker Control Service
Duration: [Duration]
Root Cause: [Brief description]
Resolution: [What was done]

Post-incident review scheduled for [Date/Time].
```

### External Communication

**Customer Notification:**
```
We are currently experiencing issues with [service/feature]. 
Our team is actively working on resolving this. 
We will provide updates every [30/60] minutes.

We apologize for any inconvenience.
```

## Tools and Resources

### Monitoring
- Grafana: https://grafana.oddsmaker.local
- Prometheus: https://prometheus.oddsmaker.local
- Logs: OTel collector → Grafana (no Elasticsearch/Kibana stack in this repo)

### Communication
- Slack: #oddsmaker-incidents
- PagerDuty: https://oddsmaker.pagerduty.com
- Status Page: https://status.oddsmaker.local

### Documentation
- Architecture: /zh/reference/architecture
- API Reference: /reference/
- Runbooks: /operations/

## Contacts

| Role | Name | Email | Phone |
|------|------|-------|-------|
| On-call Engineer | [Name] | [Email] | [Phone] |
| Engineering Manager | [Name] | [Email] | [Phone] |
| DBA | [Name] | [Email] | [Phone] |
| Security Lead | [Name] | [Email] | [Phone] |
| CTO | [Name] | [Email] | [Phone] |
