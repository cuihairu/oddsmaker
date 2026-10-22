# Oddsmaker 故障排查指南

> 🌐 Language: **中文** · English → [troubleshooting.md](troubleshooting.md)

## 快速诊断

### 健康检查命令

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

### 服务状态检查

```bash
# Control Service health
curl -s http://localhost:8086/actuator/health | jq .

# Detailed health
curl -s http://localhost:8086/actuator/health/detail | jq .

# Specific health indicators
curl -s http://localhost:8086/actuator/health/db | jq .
curl -s http://localhost:8086/actuator/health/redis | jq .
```

## 常见问题

### 1. 服务无法启动

**症状：**
- Pod 处于 CrashLoopBackOff
- 容器立即退出
- 启动探针失败

**诊断：**
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

**常见原因与解决方案：**

| 原因 | 解决方案 |
|-------|----------|
| OOM Kill | 提高内存限额 |
| 配置错误 | 检查 ConfigMap/Secret |
| 数据库连接 | 验证数据库连通性 |
| 端口冲突 | 检查端口是否可用 |
| 依赖缺失 | 确认所有服务均在运行 |

**修复示例：**
```bash
# Increase memory limits
kubectl patch deployment oddsmaker-control -n oddsmaker \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"control-service","resources":{"limits":{"memory":"2Gi"}}}]}}}}'

# Check configuration
kubectl get configmap oddsmaker-control-config -n oddsmaker -o yaml
kubectl get secret oddsmaker-control-secrets -n oddsmaker -o yaml
```

### 2. 数据库连接问题

**症状：**
- 连接超时错误
- "Connection refused" 错误
- 连接池耗尽

**诊断：**
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

**常见原因与解决方案：**

| 原因 | 解决方案 |
|-------|----------|
| 数据库宕机 | 重启 PostgreSQL pod |
| 连接数上限 | 增大 max_connections |
| 长时间运行的查询 | 终止阻塞查询 |
| 网络问题 | 检查网络策略 |
| 认证问题 | 校验凭证 |

**修复示例：**
```bash
# Kill long-running queries
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'active' AND query_start < now() - interval '5 minutes';"

# Increase connection limit
kubectl exec -it <postgres-pod> -n oddsmaker -- psql -U oddsmaker -d oddsmaker \
  -c "ALTER SYSTEM SET max_connections = 200;"
kubectl rollout restart deployment/oddsmaker-postgres -n oddsmaker
```

### 3. 高延迟

**症状：**
- API 响应缓慢
- 超时错误
- P95/P99 延迟过高

**诊断：**
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

**常见原因与解决方案：**

| 原因 | 解决方案 |
|-------|----------|
| 慢查询 | 添加索引、优化查询 |
| CPU 过高 | 水平扩容 |
| 内存压力 | 增加内存、优化 GC |
| 网络延迟 | 检查网络策略 |
| 连接池 | 增大连接池大小 |

**修复示例：**
```sql
-- ClickHouse: events has event_date / ts_server (no created_at); add a skip index instead
ALTER TABLE events ADD INDEX idx_game_eventdate (game_id, event_date) TYPE set(100) GRANULARITY 4;

-- Analyze query
EXPLAIN SELECT * FROM events WHERE game_id = 'xxx' AND event_date >= today() - 30;
```

### 4. 内存问题

**症状：**
- OOM 杀进程
- 高频垃圾回收
- Pod 重启

**诊断：**
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

**常见原因与解决方案：**

| 原因 | 解决方案 |
|-------|----------|
| 内存泄漏 | 修复代码、重启服务 |
| 堆过大 | 提高内存限额 |
| GC 问题 | 调优 JVM 设置 |
| 缓存膨胀 | 限制缓存大小 |

**修复示例：**
```bash
# Update JVM settings
kubectl set env deployment/oddsmaker-control -n oddsmaker \
  JAVA_OPTS="-Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Increase memory limits
kubectl patch deployment oddsmaker-control -n oddsmaker \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"control-service","resources":{"limits":{"memory":"4Gi"}}}]}}}}'
```

### 5. 认证问题

**症状：**
- 401 Unauthorized 错误
- Token 校验失败
- 登录失败

**诊断：**
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

**常见原因与解决方案：**

| 原因 | 解决方案 |
|-------|----------|
| Token 过期 | 刷新 token |
| Token 无效 | 重新认证 |
| 账号被锁 | 解锁账号 |
| 凭证错误 | 重置密码 |
| 时钟偏移 | 同步 NTP |

**修复示例：**
```sql
-- Unlock account
UPDATE users SET login_attempts = 0, locked_until = NULL WHERE email = 'user@example.com';

-- Reset password
UPDATE users SET password_hash = '$2a$10$...' WHERE email = 'user@example.com';
```

### 6. 缓存问题

**症状：**
- 缓存未命中率过高
- 响应缓慢
- 数据过期

**诊断：**
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

**常见原因与解决方案：**

| 原因 | 解决方案 |
|-------|----------|
| 缓存冷启动 | 预热缓存 |
| TTL 过短 | 增大 TTL |
| 内存已满 | 增加内存、淘汰旧 key |
| 连接问题 | 检查网络 |

**修复示例：**
```bash
# Increase Redis memory
kubectl patch deployment oddsmaker-redis -n oddsmaker \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"redis","resources":{"limits":{"memory":"2Gi"}}}]}}}}'

# Flush cache (if needed)
kubectl exec -it <redis-pod> -n oddsmaker -- redis-cli FLUSHDB
```

### 7. Kafka 问题

**症状：**
- 消息处理延迟
- 消费者滞后（consumer lag）
- 连接错误

**诊断：**
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

**常见原因与解决方案：**

| 原因 | 解决方案 |
|-------|----------|
| 消费者下线 | 重启消费者 |
| 滞后过高 | 扩容消费者 |
| Broker 宕机 | 重启 broker |
| 网络问题 | 检查网络 |

**修复示例：**
```bash
# Scale consumers
kubectl scale deployment oddsmaker-control -n oddsmaker --replicas=5

# Reset consumer offset
kubectl exec -it <kafka-pod> -n oddsmaker -- kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --group oddsmaker-control \
  --topic oddsmaker.events_raw --reset-offsets --to-latest --execute
```

## 性能调优

### JVM 调优

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

### 数据库调优

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

### 连接池调优

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

## 常用命令

### Kubernetes 命令

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

### 数据库命令

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

## 联系信息

| 团队 | 联系方式 | 何时联系 |
|------|---------|-----------------|
| 值班工程师 | [Phone/Slack] | 任何事故 |
| DBA 团队 | [Phone/Slack] | 数据库问题 |
| 平台团队 | [Phone/Slack] | 基础设施问题 |
| 安全团队 | [Phone/Slack] | 安全事件 |
