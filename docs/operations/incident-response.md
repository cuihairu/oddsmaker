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
- Kibana: https://kibana.oddsmaker.local

### Communication
- Slack: #oddsmaker-incidents
- PagerDuty: https://oddsmaker.pagerduty.com
- Status Page: https://status.oddsmaker.local

### Documentation
- Architecture: docs/reference/architecture.md
- API Reference: docs/reference/api-reference.md
- Runbooks: docs/operations/

## Contacts

| Role | Name | Email | Phone |
|------|------|-------|-------|
| On-call Engineer | [Name] | [Email] | [Phone] |
| Engineering Manager | [Name] | [Email] | [Phone] |
| DBA | [Name] | [Email] | [Phone] |
| Security Lead | [Name] | [Email] | [Phone] |
| CTO | [Name] | [Email] | [Phone] |
