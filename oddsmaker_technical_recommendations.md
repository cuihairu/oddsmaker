# Oddsmaker 项目技术决策建议

## 执行摘要

基于对代码库的深度分析，Oddsmaker 项目已具备:
- **核心功能**: 80% 完整 (数据采集/存储/分析)
- **代码质量**: 整体良好 (Web SDK优秀)
- **关键缺陷**: 安全性严重不足 (无认证/权限)

**建议**: 立即启动12周加固计划，优先完成安全框架、权限系统、审计日志三大支柱。

---

## 一、安全框架实施方案

### 1.1 认证系统选型

**当前问题**:
```
Web UI:  完全开放，任何人可访问管理界面
API:     仅有AdminToken，单一密钥，无用户隔离
Gateway: API密钥校验存在但无权限细化
```

**推荐方案: Keycloak集成**

**技术栈**:
```
Keycloak Server (OAuth2/OIDC)
├─ User Management (员工/客户)
├─ Role Management (Admin/Manager/Viewer)
├─ Client Registration (Web UI/API Gateway/Mobile)
└─ Token Management (JWT/Refresh Token)
       ↓
Control Service
├─ @Secured注解 (Spring)
├─ Token验证中间件
└─ 权限检查拦截器
```

**实施步骤** (2周):
1. 部署Keycloak服务器
2. 创建Realm和用户角色
3. 集成Spring Security (Control/Gateway)
4. 修改Web UI添加OAuth2流程
5. 更新SDK文档 (API密钥仍保留)

**代码示例**:
```java
// Control Service中
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.decoder(jwtDecoder()))
        ).authorizeRequests()
            .antMatchers("/api/experiments/**").hasRole("ADMIN")
            .antMatchers("/api/keys/**").hasRole("ADMIN")
            .antMatchers("/api/config/**").permitAll() // SDK public
            .anyRequest().authenticated();
        return http.build();
    }
}
```

**成本**: 服务器成本(可用开源) + 开发工时

---

### 1.2 权限模型 (RBAC)

**推荐层级**:
```
权限维度:
  └─ Organization (组织)
      └─ Project (项目)
          ├─ 角色: Owner / Manager / Analyst / Viewer
          └─ 权限:
              - Owner: 所有操作 + 删除项目
              - Manager: 编辑项目/实验/API密钥
              - Analyst: 查看数据/仪表板
              - Viewer: 只读所有

权限粒度:
  ├─ 资源级: 项目/实验/API密钥
  ├─ 操作级: CREATE/READ/UPDATE/DELETE/EXPORT
  └─ 数据级: 跨项目查询防护
```

**数据库扩展**:
```sql
-- 新增表
CREATE TABLE organizations (
  id VARCHAR(32) PRIMARY KEY,
  name VARCHAR(255),
  owner_id VARCHAR(64),
  created_at TIMESTAMP
);

CREATE TABLE project_members (
  project_id VARCHAR(32),
  user_id VARCHAR(64),
  role ENUM('OWNER', 'MANAGER', 'ANALYST', 'VIEWER'),
  granted_at TIMESTAMP,
  PRIMARY KEY (project_id, user_id)
);

CREATE TABLE audit_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id VARCHAR(64),
  action VARCHAR(255),
  resource_type VARCHAR(64),
  resource_id VARCHAR(32),
  old_value TEXT,
  new_value TEXT,
  ip_address VARCHAR(45),
  created_at TIMESTAMP,
  INDEX idx_user_time (user_id, created_at),
  INDEX idx_resource (resource_type, resource_id)
);
```

**实施步骤** (1周):
1. 设计权限矩阵
2. 创建数据库表
3. 开发权限检查工具类
4. 改造API添加权限检查
5. 更新Web UI展示权限信息

---

### 1.3 审计日志系统

**日志覆盖范围**:
```
API操作:
  ├─ 项目管理 (创建/编辑/删除)
  ├─ 实验管理 (发布/暂停/修改)
  ├─ API密钥管理 (创建/轮换/删除)
  └─ 权限变更 (用户添加/移除/角色变更)

敏感数据访问:
  ├─ API密钥导出
  ├─ 数据导出
  └─ 配置备份

认证事件:
  ├─ 登录成功/失败
  ├─ Token更新
  └─ 会话终止
```

**日志格式**:
```json
{
  "timestamp": "2024-01-15T10:30:45Z",
  "user_id": "user_123",
  "action": "CREATE",
  "resource_type": "experiment",
  "resource_id": "exp_abc123",
  "old_value": null,
  "new_value": {"id": "exp_abc123", "name": "new_exp"},
  "status": "SUCCESS",
  "ip_address": "192.168.1.100",
  "user_agent": "Mozilla/5.0...",
  "details": {...}
}
```

**存储位置**: ClickHouse (audit_logs表)

**查询示例**:
```sql
-- 某用户最近修改
SELECT * FROM audit_logs 
WHERE user_id = 'user_123' 
AND created_at > now() - INTERVAL 30 DAY
ORDER BY created_at DESC LIMIT 1000;

-- API密钥操作审计
SELECT * FROM audit_logs 
WHERE resource_type = 'api_key' 
AND action IN ('CREATE', 'DELETE', 'UPDATE')
ORDER BY created_at DESC;
```

---

## 二、数据隐私加强

### 2.1 PII处理完善

**现状**:
- ✓ Gateway中有PII策略 (allow/mask/drop)
- ✗ Flink中PII处理不完整
- ✗ 无加密存储方案
- ✗ 无数据最小化策略

**改进方案**:

```java
// 1. Flink中强制PII处理
EventsEnrichJob.java修改:
- 根据API密钥策略，在Flink中二次应用PII过滤
- 添加加密字段支持 (user_id/email可选加密)
- 生成数据脱敏报告

// 2. ClickHouse加密列
ALTER TABLE events 
ADD COLUMN user_id_encrypted AES_ENCRYPT(user_id, 'key');

// 3. 访问控制
- 只有授权用户可查看PII字段
- 导出操作需审批
```

---

## 三、SDK完整化方案

### 3.1 iOS SDK完成 (2周)

**现状**: 代码在100行被截断，缺少:
- 缓存实现
- HMAC签名
- 错误处理
- 完整测试

**修复清单**:
```swift
// 完成缓存机制
class ExperimentsCache {
    private let userDefaults: UserDefaults
    private let ttl: TimeInterval
    
    func cached() -> [ExperimentCfg]? {
        guard let data = userDefaults.data(forKey: "experiments"),
              let entry = try? JSONDecoder().decode(CacheEntry.self, from: data),
              Date().timeIntervalSince(entry.timestamp) < ttl else {
            return nil
        }
        return entry.experiments
    }
}

// 完成HMAC签名
func sign(_ request: inout URLRequest, with secret: String) {
    let timestamp = String(Date().timeIntervalSince1970.rounded())
    let bodyData = request.httpBody ?? Data()
    let message = timestamp + "." + String(data: bodyData, encoding: .utf8)!
    let signature = HMAC<SHA256>.authenticationCode(
        for: Data(message.utf8),
        using: SymmetricKey(data: Data(secret.utf8))
    )
    request.setValue("t=\(timestamp),s=\(signature.hexString)", 
                    forHTTPHeaderField: "x-signature")
}

// 添加Combine支持 (现代异步)
func trackAsync(_ name: String, props: [String: Any]? = nil) -> Future<String, Error> {
    Future { promise in
        let eventId = self.track(name, props: props)
        promise(.success(eventId))
    }
}
```

### 3.2 游戏事件预定义 (1周)

**创建game_events.avsc**:
```json
{
  "type": "record",
  "name": "GameEvent",
  "namespace": "io.oddsmaker.v1",
  "fields": [
    {"name": "event_base", "type": "io.oddsmaker.v1.Event"},
    {"name": "event_type", "type": {
      "type": "enum",
      "name": "GameEventType",
      "symbols": [
        "LEVEL_START", "LEVEL_COMPLETE", "LEVEL_FAILED",
        "PURCHASE_IAP", "PURCHASE_VIRTUAL",
        "BATTLE_START", "BATTLE_WIN", "BATTLE_LOSS",
        "ACHIEVEMENT_UNLOCK",
        "SOCIAL_INVITE", "SOCIAL_ACCEPT",
        "AD_WATCH", "AD_REWARD",
        "ECONOMY_GAIN", "ECONOMY_SPEND"
      ]
    }},
    {"name": "level_id", "type": ["null", "string"], "default": null},
    {"name": "character_id", "type": ["null", "string"], "default": null},
    {"name": "currency_type", "type": ["null", "string"], "default": null},
    {"name": "amount", "type": ["null", {"type": "bytes", "logicalType": "decimal", "precision": 18, "scale": 4}], "default": null}
  ]
}
```

**SDK辅助函数**:
```typescript
// Web SDK示例
export interface GameEventProps {
  levelId?: string;
  characterId?: string;
  amount?: number;
  currencyType?: string;
}

export function trackLevelComplete(
  levelId: string,
  props?: GameEventProps
): string {
  return track('level_complete', {
    ...props,
    level_id: levelId
  });
}
```

---

## 四、分析能力增强

### 4.1 可配置漏斗实现 (1.5周)

**当前限制**: 仅支持2步漏斗

**改进方案**:
```java
// 创建新的Flink作业: configurable-funnels-job
public class ConfigurableFunnelsJob {
    // 从Control Service API获取漏斗定义
    FunnelConfig config = fetchConfig(projectId, funnelId);
    
    // config示例:
    // {
    //   "id": "funnel_onboarding",
    //   "steps": [
    //     {"name": "signup", "event": "user_signup"},
    //     {"name": "profile", "event": "profile_complete"},
    //     {"name": "first_purchase", "event": "purchase"},
    //     {"name": "retention_7d", "event": "level_start", "timedelta_days": 7}
    //   ],
    //   "user_key": "user_id"
    // }
    
    events.keyBy(config.userKey)
          .window(TumblingEventTimeWindows.of(Time.days(1)))
          .process(new FunnelProcessor(config))
          .addSink(ClickHouseSink);
}
```

**ClickHouse视图**:
```sql
CREATE TABLE funnels_configurable (
  project_id String,
  funnel_id String,
  event_date Date,
  step INT,
  step_name String,
  users UInt64,
  conversion_rate Decimal(5,2)
) ENGINE = SummingMergeTree()
PARTITION BY (project_id, toYYYYMM(event_date))
ORDER BY (project_id, funnel_id, event_date, step);
```

---

## 五、运维与监控

### 5.1 性能监控 (Prometheus)

**关键指标**:
```yaml
# Gateway
oddsmaker_gateway_http_requests_total{method, endpoint, status}
oddsmaker_gateway_event_processing_duration_seconds
oddsmaker_gateway_schema_validation_errors_total
oddsmaker_gateway_rate_limit_triggered_total

# Flink
oddsmaker_flink_events_processed_total
oddsmaker_flink_events_enrich_duration_milliseconds
oddsmaker_flink_events_dedup_ratio
oddsmaker_flink_state_size_bytes

# ClickHouse
oddsmaker_clickhouse_query_duration_seconds
oddsmaker_clickhouse_insert_queue_depth
oddsmaker_clickhouse_disk_usage_bytes

# Control Service
oddsmaker_control_api_latency_seconds
oddsmaker_control_db_query_time_seconds
```

**告警规则示例**:
```yaml
groups:
  - name: oddsmaker_slo
    rules:
      - alert: HighEventProcessingLatency
        expr: histogram_quantile(0.95, oddsmaker_gateway_event_processing_duration_seconds) > 5
        for: 5m
        annotations:
          summary: "事件处理延迟超过5秒"
          
      - alert: FlinkJobFailed
        expr: oddsmaker_flink_job_status{status="failed"} > 0
        for: 1m
        annotations:
          summary: "Flink作业异常"
```

---

## 六、12周执行计划

### Phase 1: 安全加固 (第1-4周)

**Week 1-2: 认证系统**
- Day 1-2: Keycloak部署 + 配置
- Day 3-5: Spring Security集成
- Day 6-10: Web UI OAuth2改造

**Week 3: 权限模型**
- Day 1-2: 数据库设计
- Day 3-5: 权限检查框架
- Day 6-10: API改造

**Week 4: 审计日志**
- Day 1-3: 审计框架实现
- Day 4-10: 覆盖所有API端点

### Phase 2: 功能完整 (第5-8周)

**Week 5-6: iOS SDK**
**Week 7: 游戏事件**
**Week 8: 漏斗配置**

### Phase 3: 运维就绪 (第9-12周)

**Week 9-10: 性能监控**
**Week 11: 容量规划 + 文档**
**Week 12: 测试验收**

---

## 七、成本估算

| 项目 | 工时 | 人力成本 | 基础设施 | 总计 |
|------|------|---------|---------|------|
| 认证系统 | 80h | ¥32k | ¥5k/月 | ¥37k |
| 权限模型 | 40h | ¥16k | ¥0 | ¥16k |
| 审计日志 | 40h | ¥16k | ¥2k/月 | ¥18k |
| iOS SDK | 80h | ¥32k | ¥0 | ¥32k |
| 游戏事件 | 40h | ¥16k | ¥0 | ¥16k |
| 漏斗配置 | 60h | ¥24k | ¥0 | ¥24k |
| 监控告警 | 40h | ¥16k | ¥3k/月 | ¥19k |
| **合计** | **380h** | **¥152k** | **¥10k/月** | **¥162k+** |

---

## 八、风险缓解

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|---------|
| Keycloak学习曲线陡 | 中 | 中 | 寻求商业支持/培训 |
| 迁移兼容性问题 | 中 | 高 | 先灰度迁移10%用户 |
| 性能下降 | 低 | 高 | 充分压测，缓存优化 |
| 依赖版本冲突 | 中 | 中 | 隔离容器部署 |

---

## 九、成功衡量

**完成标准**:
- [ ] 所有API端点需要认证
- [ ] 权限检查覆盖100%
- [ ] 审计日志记录完整
- [ ] iOS SDK测试覆盖>80%
- [ ] 漏斗支持>3步
- [ ] 监控告警自动化100%

**KPI目标**:
- 安全漏洞零容忍 (P0立即修)
- 系统可用性>99.95%
- 平均响应时间<200ms
- 用户增长>30%/月

