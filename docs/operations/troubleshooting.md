# Oddsmaker Troubleshooting Guide

> 🌐 Language: **English** · 中文 → [troubleshooting.zh.md](../zh/operations/troubleshooting)

## Quick Diagnostics

### Health Check Commands

```bash
# Check all services
kubectl get pods -n oddsmaker

# Check specific service
kubectl get pods -l app=oddsmaker-control -n oddsmaker

# Check service endpoints
kubectl get endpoints -n oddsmaker

# Check recent events
kubectl get events -n oddsmaker --sort-by='.lastTimestamp' | tail -20
```

### Service Status Check

```bash
# Control Service health
curl -s http://localhost:8086/actuator/health | jq .

# Detailed health
curl -s http://localhost:8086/actuator/health/detail | jq .

# Specific health indicators
curl -s http://localhost:8086/actuator/health/db | jq .
curl -s http://localhost:8086/actuator/health/redis | jq .
```

## Common Issues

### 1. Service Won't Start

**Symptoms:**
- Pod in CrashLoopBackOff
- Container exits immediately
- Startup probe failures

**Diagnosis:**
```bash
# Check pod status
kubectl describe pod <pod-name> -n oddsmaker

# Check container logs
kubectl logs <pod-name> -n oddsmaker --tail=100

# Check previous container logs
kubectl logs <pod-name> -n oddsmaker --previous

# Check resource limits
kubectl get pod <pod-name> -n oddsmaker -o yaml | grep -A 5 resources
```

**Common Causes and Solutions:**

| Cause | Solution |
|-------|----------|
| OOM Kill | Increase memory limits |
| Configuration error | Check ConfigMap/Secret |
| Database connection | Verify database connectivity |
| Port conflict | Check port availability |
| Missing dependency | Verify all services running |

**Example Fix:**
```bash
# Increase memory limits
kubectl patch deployment oddsmaker-control -n oddsmaker \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"control-service","resources":{"limits":{"memory":"2Gi"}}}]}}}}'

# Check configuration
kubectl get configmap oddsmaker-control-config -n oddsmaker -o yaml
kubectl get secret oddsmaker-control-secrets -n oddsmaker -o yaml
```

### 2. Database Connection Issues

**Symptoms:**
- Connection timeout errors
- "Connection refused" errors
- Connection pool exhaustion

**Diagnosis:**
```bash
# Check PostgreSQL pod
kubectl get pods -l app=oddsmaker-postgres -n oddsmaker

# Check PostgreSQL logs
kubectl logs -l app=oddsmaker-postgres -n oddsmaker --tail=100

# Test connection
kubectl exec -it <app-pod> -n oddsmaker -- psql -h oddsmaker-postgres -U oddsmaker -d oddsmaker

# Check active connections
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "SELECT count(*) FROM pg_stat_activity;"
```

**Common Causes and Solutions:**

| Cause | Solution |
|-------|----------|
| Database down | Restart PostgreSQL pod |
| Connection limit | Increase max_connections |
| Long-running queries | Kill blocking queries |
| Network issue | Check network policies |
| Authentication | Verify credentials |

**Example Fix:**
```bash
# Kill long-running queries
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'active' AND query_start < now() - interval '5 minutes';"

# Increase connection limit
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "ALTER SYSTEM SET max_connections = 200;"
kubectl rollout restart deployment/oddsmaker-postgres -n oddsmaker
```

### 3. High Latency

**Symptoms:**
- Slow API responses
- Timeout errors
- High P95/P99 latency

**Diagnosis:**
```bash
# Check latency metrics
curl -s http://localhost:8086/actuator/metrics/http.server.requests | jq .

# Check slow queries
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "SELECT pid, now() - pg_stat_activity.query_start AS duration, query FROM pg_stat_activity WHERE (now() - pg_stat_activity.query_start) > interval '1 second' ORDER BY duration DESC;"

# Check resource usage
kubectl top pods -n oddsmaker
kubectl top nodes

# Check network latency
kubectl exec -it <app-pod> -n oddsmaker -- ping oddsmaker-postgres
```

**Common Causes and Solutions:**

| Cause | Solution |
|-------|----------|
| Slow queries | Add indexes, optimize queries |
| High CPU | Scale horizontally |
| Memory pressure | Increase memory, optimize GC |
| Network latency | Check network policies |
| Connection pool | Increase pool size |

**Example Fix:**
```sql
-- ClickHouse: events has event_date / ts_server (no created_at); add a skip index instead
ALTER TABLE events ADD INDEX idx_game_eventdate (game_id, event_date) TYPE set(100) GRANULARITY 4;

-- Analyze query
EXPLAIN SELECT * FROM events WHERE game_id = 'xxx' AND event_date >= today() - 30;
```

### 4. Memory Issues

**Symptoms:**
- OOM kills
- High garbage collection
- Pod restarts

**Diagnosis:**
```bash
# Check memory usage
kubectl top pods -n oddsmaker

# Check JVM memory
curl -s http://localhost:8086/actuator/metrics/jvm.memory.used | jq .

# Check garbage collection
curl -s http://localhost:8086/actuator/metrics/jvm.gc.pause | jq .

# Check for memory leaks
kubectl exec -it <pod-name> -n oddsmaker -- jmap -histo 1 | head -20
```

**Common Causes and Solutions:**

| Cause | Solution |
|-------|----------|
| Memory leak | Fix code, restart service |
| Large heap | Increase memory limits |
| GC issues | Tune JVM settings |
| Cache bloat | Limit cache size |

**Example Fix:**
```bash
# Update JVM settings
kubectl set env deployment/oddsmaker-control -n oddsmaker \
  JAVA_OPTS="-Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Increase memory limits
kubectl patch deployment oddsmaker-control -n oddsmaker \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"control-service","resources":{"limits":{"memory":"4Gi"}}}]}}}}'
```

### 5. Authentication Issues

**Symptoms:**
- 401 Unauthorized errors
- Token validation failures
- Login failures

**Diagnosis:**
```bash
# Check JWT configuration
kubectl get configmap oddsmaker-control-config -n oddsmaker -o yaml | grep JWT

# Check user table
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "SELECT id, email, status, last_login FROM users ORDER BY last_login DESC LIMIT 10;"

# Check failed logins
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "SELECT * FROM audit_logs WHERE action = 'LOGIN' AND result = 'FAILURE' ORDER BY created_at DESC LIMIT 20;"

# Check account lockouts
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "SELECT id, email, login_attempts, locked_until FROM users WHERE locked_until IS NOT NULL;"
```

**Common Causes and Solutions:**

| Cause | Solution |
|-------|----------|
| Expired token | Refresh token |
| Invalid token | Re-authenticate |
| Account locked | Unlock account |
| Wrong credentials | Reset password |
| Clock skew | Sync NTP |

**Example Fix:**
```sql
-- Unlock account
UPDATE users SET login_attempts = 0, locked_until = NULL WHERE email = 'user@example.com';

-- Reset password
UPDATE users SET password_hash = '$2a$10$...' WHERE email = 'user@example.com';
```

### 6. Cache Issues

**Symptoms:**
- High cache miss rate
- Slow responses
- Stale data

**Diagnosis:**
```bash
# Check Redis pod
kubectl get pods -l app=oddsmaker-redis -n oddsmaker

# Check Redis logs
kubectl logs -l app=oddsmaker-redis -n oddsmaker --tail=100

# Check Redis stats
kubectl exec -it <redis-pod> -n oddsmaker -- redis-cli INFO stats

# Check cache hit rate
kubectl exec -it <redis-pod> -n oddsmaker -- redis-cli INFO stats | grep -E "hits|misses"

# Check memory usage
kubectl exec -it <redis-pod> -n oddsmaker -- redis-cli INFO memory
```

**Common Causes and Solutions:**

| Cause | Solution |
|-------|----------|
| Cache cold | Warm up cache |
| TTL too short | Increase TTL |
| Memory full | Increase memory, evict old keys |
| Connection issue | Check network |

**Example Fix:**
```bash
# Increase Redis memory
kubectl patch deployment oddsmaker-redis -n oddsmaker \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"redis","resources":{"limits":{"memory":"2Gi"}}}]}}}}'

# Flush cache (if needed)
kubectl exec -it <redis-pod> -n oddsmaker -- redis-cli FLUSHDB
```

### 7. Kafka Issues

**Symptoms:**
- Message processing delays
- Consumer lag
- Connection errors

**Diagnosis:**
```bash
# Check Kafka pod
kubectl get pods -l app=kafka -n oddsmaker

# Check Kafka logs
kubectl logs -l app=kafka -n oddsmaker --tail=100

# Check consumer lag
kubectl exec -it <kafka-pod> -n oddsmaker -- kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group oddsmaker-control

# Check topic status (oddsmaker.events_raw / oddsmaker.risk_events / oddsmaker.identity_events / oddsmaker.deadletter)
kubectl exec -it <kafka-pod> -n oddsmaker -- kafka-topics.sh \
  --bootstrap-server kafka:9092 --describe --topic oddsmaker.events_raw
```

**Common Causes and Solutions:**

| Cause | Solution |
|-------|----------|
| Consumer down | Restart consumer |
| High lag | Scale consumers |
| Broker down | Restart broker |
| Network issue | Check network |

**Example Fix:**
```bash
# Scale consumers
kubectl scale deployment oddsmaker-control -n oddsmaker --replicas=5

# Reset consumer offset
kubectl exec -it <kafka-pod> -n oddsmaker -- kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --group oddsmaker-control \
  --topic oddsmaker.events_raw --reset-offsets --to-latest --execute
```

## Performance Tuning

### JVM Tuning

```bash
# Recommended JVM settings
JAVA_OPTS="-Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -XX:+UseStringDeduplication \
  -XX:+UseCompressedOops \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs/heapdump.hprof"
```

### Database Tuning

```sql
-- PostgreSQL recommended settings
ALTER SYSTEM SET shared_buffers = '4GB';
ALTER SYSTEM SET effective_cache_size = '12GB';
ALTER SYSTEM SET maintenance_work_mem = '1GB';
ALTER SYSTEM SET work_mem = '16MB';
ALTER SYSTEM SET max_connections = 200;
ALTER SYSTEM SET random_page_cost = 1.1;
ALTER SYSTEM SET effective_io_concurrency = 200;
ALTER SYSTEM SET wal_buffers = '64MB';
ALTER SYSTEM SET min_wal_size = '2GB';
ALTER SYSTEM SET max_wal_size = '8GB';
```

### Connection Pool Tuning

```yaml
# application.yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      idle-timeout: 30000
      max-lifetime: 1800000
      connection-timeout: 10000
      validation-timeout: 5000
```

## Useful Commands

### Kubernetes Commands

```bash
# Get all resources
kubectl get all -n oddsmaker

# Describe resource
kubectl describe <resource> <name> -n oddsmaker

# View logs
kubectl logs <pod> -n oddsmaker -f

# Execute command
kubectl exec -it <pod> -n oddsmaker -- <command>

# Port forward
kubectl port-forward svc/oddsmaker-control 8085:80 -n oddsmaker

# Copy files
kubectl cp <pod>:/path/to/file ./local-file -n oddsmaker

# Resource usage
kubectl top pods -n oddsmaker
kubectl top nodes
```

### Database Commands

```sql
-- Connection info
SELECT * FROM pg_stat_activity;

-- Database size
SELECT pg_database.datname, pg_size_pretty(pg_database_size(pg_database.datname))
FROM pg_database ORDER BY pg_database_size(pg_database.datname) DESC;

-- Table sizes
SELECT schemaname, tablename, pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename))
FROM pg_tables WHERE schemaname = 'public' ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;

-- Index usage
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes ORDER BY idx_scan DESC;

-- Slow queries
SELECT pid, now() - pg_stat_activity.query_start AS duration, query
FROM pg_stat_activity WHERE (now() - pg_stat_activity.query_start) > interval '5 minutes';
```

## Contact Information

| Team | Contact | When to Contact |
|------|---------|-----------------|
| On-call Engineer | [Phone/Slack] | Any incident |
| DBA Team | [Phone/Slack] | Database issues |
| Platform Team | [Phone/Slack] | Infrastructure issues |
| Security Team | [Phone/Slack] | Security incidents |
