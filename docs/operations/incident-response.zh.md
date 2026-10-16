# Oddsmaker 事故响应手册

> 🌐 Language: **中文** · English → [incident-response.md](incident-response.md)

## 概览

本手册提供针对影响 Oddsmaker 游戏分析平台的事故的响应流程。

## 严重级别

| 级别 | 描述 | 响应时间 | 示例 |
|-------|-------------|---------------|---------|
| P1 - Critical | 服务完全不可用、数据丢失 | 15 分钟 | 数据库故障、安全入侵 |
| P2 - High | 主要功能不可用 | 30 分钟 | API 错误、认证失败 |
| P3 - Medium | 性能降级 | 2 小时 | 查询缓慢、高延迟 |
| P4 - Low | 轻微问题，有可用替代方案 | 24 小时 | UI 缺陷、非关键功能问题 |

## 事故响应流程

### 1. 检测与告警

**自动告警：**
- Prometheus/Grafana 告警
- 健康检查失败
- 错误率激增
- 性能降级

**人工检测：**
- 客户报告
- 支持工单
- 监控大盘巡检

### 2. 初始响应

**前 5 分钟：**
1. 确认告警
2. 评估严重级别
3. 通知值班团队
4. 创建事故工单

**前 15 分钟：**
1. 收集初始信息
2. 识别受影响的组件
3. 确定影响范围
4. 向干系人通报状态

### 3. 调查

**信息收集：**
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

**数据库调查：**
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

### 4. 缓解

**常见缓解措施：**

**服务重启：**
```bash
kubectl rollout restart deployment/oddsmaker-control -n oddsmaker
```

**扩容：**
```bash
kubectl scale deployment/oddsmaker-control --replicas=5 -n oddsmaker
```

**回滚：**
```bash
kubectl rollout undo deployment/oddsmaker-control -n oddsmaker
```

**数据库故障转移：**
```bash
# Promote standby to primary
pg_ctl promote -D /var/lib/postgresql/data
```

### 5. 解决

**解决步骤：**
1. 实施修复（代码变更、配置、基础设施）
2. 在 staging 环境验证修复
3. 部署到生产环境
4. 验证问题已解决
5. 监控是否复发

### 6. 事后

**事后复盘：**
1. 记录时间线
2. 定位根因
3. 确定促成因素
4. 创建行动项
5. 更新 runbook

**事后模板：**
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

## 常见事故

### 事故：错误率过高

**症状：**
- 错误率 > 5%
- 5xx 响应增加
- 客户投诉

**调查：**
```bash
# Check error logs
kubectl logs -l app=oddsmaker-control -n oddsmaker --tail=1000 | grep -i error

# Check recent deployments
kubectl rollout history deployment/oddsmaker-control -n oddsmaker

# Check resource usage
kubectl top pods -n oddsmaker
```

**解决：**
1. 若是近期部署导致：回滚
2. 若是资源问题：扩容
3. 若是代码缺陷：热修复部署

### 事故：数据库连接问题

**症状：**
- 连接超时错误
- 连接池耗尽
- 查询缓慢

**调查：**
```sql
-- Check connection count
SELECT count(*) FROM pg_stat_activity;

-- Check connection by state
SELECT state, count(*) FROM pg_stat_activity GROUP BY state;

-- Check waiting queries
SELECT * FROM pg_stat_activity WHERE wait_event_type IS NOT NULL;
```

**解决：**
1. 终止长时间运行的查询
2. 增大连接池
3. 优化查询
4. 扩容数据库

### 事故：内存使用过高

**症状：**
- OOM 杀进程
- Pod 重启
- 高频垃圾回收

**调查：**
```bash
# Check memory usage
kubectl top pods -n oddsmaker

# Check JVM heap
curl http://localhost:8086/actuator/metrics/jvm.memory.used

# Check for memory leaks
jmap -histo <pid> | head -20
```

**解决：**
1. 提高内存限额
2. 优化 JVM 设置
3. 修复内存泄漏
4. 水平扩容

### 事故：安全入侵

**症状：**
- 未授权访问尝试
- 可疑的审计日志
- 数据外泄告警

**调查：**
```bash
# Check audit logs
kubectl logs -l app=oddsmaker-control -n oddsmaker | grep -i "security\|unauthorized\|breach"

# Check failed login attempts
psql -c "SELECT * FROM audit_logs WHERE action = 'LOGIN' AND result = 'FAILURE' ORDER BY created_at DESC LIMIT 100;"

# Check suspicious IPs
psql -c "SELECT client_ip, count(*) FROM audit_logs WHERE result = 'FAILURE' GROUP BY client_ip ORDER BY count DESC LIMIT 20;"
```

**解决：**
1. 封禁可疑 IP
2. 吊销已泄露的凭证
3. 启用额外安全措施
4. 通知安全团队
5. 保留证据

## 升级矩阵

| 严重级别 | 首要响应人 | 升级（30 分钟） | 升级（1 小时） |
|----------|-------------------|---------------------|---------------------|
| P1 | 值班工程师 | 工程经理 | CTO |
| P2 | 值班工程师 | 工程经理 | CTO |
| P3 | 值班工程师 | 团队负责人 | 工程经理 |
| P4 | 值班工程师 | 团队负责人 | - |

## 沟通模板

### 内部沟通

**初始通知：**
```
Incident Alert - [Severity]

Service: Oddsmaker Control Service
Impact: [Description]
Status: Investigating
ETA: [Time]

Updates will follow every [15/30/60] minutes.
```

**状态更新：**
```
Incident Update - [Severity]

Service: Oddsmaker Control Service
Status: [Investigating/Identified/Monitoring/Resolved]
Impact: [Current impact]
Progress: [What's been done]
Next Steps: [What's planned]

Next update in [15/30/60] minutes.
```

**解决通知：**
```
✅ Incident Resolved - [Severity]

Service: Oddsmaker Control Service
Duration: [Duration]
Root Cause: [Brief description]
Resolution: [What was done]

Post-incident review scheduled for [Date/Time].
```

### 外部沟通

**客户通知：**
```
We are currently experiencing issues with [service/feature]. 
Our team is actively working on resolving this. 
We will provide updates every [30/60] minutes.

We apologize for any inconvenience.
```

## 工具与资源

### 监控
- Grafana: https://grafana.oddsmaker.local
- Prometheus: https://prometheus.oddsmaker.local
- 日志：OTel collector → Grafana（本仓库不含 Elasticsearch/Kibana 技术栈）

### 沟通
- Slack: #oddsmaker-incidents
- PagerDuty: https://oddsmaker.pagerduty.com
- Status Page: https://status.oddsmaker.local

### 文档
- 架构：/zh/reference/architecture
- API Reference：/reference/
- Runbook：/operations/

## 联系人

| 角色 | 姓名 | 邮箱 | 电话 |
|------|------|-------|-------|
| 值班工程师 | [Name] | [Email] | [Phone] |
| 工程经理 | [Name] | [Email] | [Phone] |
| DBA | [Name] | [Email] | [Phone] |
| 安全负责人 | [Name] | [Email] | [Phone] |
| CTO | [Name] | [Email] | [Phone] |
