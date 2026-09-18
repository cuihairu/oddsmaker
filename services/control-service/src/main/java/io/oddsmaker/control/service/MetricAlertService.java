package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.MetricAlertRuleEntity;
import io.oddsmaker.control.jpa.MetricAlertRuleRepo;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业务指标告警：规则 CRUD + 定时评估 + 站内告警与 Webhook 通知。
 * 指标当前值与昨日基线均查 ClickHouse events 明细；CH 未配置时评估整体跳过。
 * 沿触发抑制：同一规则存在活跃告警时不新建不重发，仅累加 occurrenceCount；
 * 恢复只回写规则 last_state=OK，告警由人工确认/解决。
 */
@Service
public class MetricAlertService {

    private static final Logger logger = LoggerFactory.getLogger(MetricAlertService.class);

    /** 主体口径与在线监控/财务报表一致：player_id > user_id > device_id */
    private static final String SUBJECT =
            "if(player_id != '', player_id, if(user_id != '', user_id, device_id))";

    private final MetricAlertRuleRepo ruleRepo;
    private final SystemAlertRepo alertRepo;
    private final ClickHouseClient clickHouse;
    private final WebhookService webhookService;
    private final AuditLogService auditLog;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public MetricAlertService(MetricAlertRuleRepo ruleRepo,
                              SystemAlertRepo alertRepo,
                              ClickHouseClient clickHouse,
                              WebhookService webhookService,
                              AuditLogService auditLog,
                              ObjectMapper objectMapper,
                              @Value("${oddsmaker.metric-alert.enabled:true}") boolean enabled) {
        this.ruleRepo = ruleRepo;
        this.alertRepo = alertRepo;
        this.clickHouse = clickHouse;
        this.webhookService = webhookService;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    // ===== 规则 CRUD =====

    public List<MetricAlertRuleEntity> list(String gameId) {
        return ruleRepo.findByGameIdAndDeletedAtIsNullOrderByName(gameId);
    }

    public MetricAlertRuleEntity get(String gameId, String ruleId) {
        MetricAlertRuleEntity rule = ruleRepo.findById(ruleId).orElse(null);
        return rule != null && rule.gameId.equals(gameId) && rule.deletedAt == null ? rule : null;
    }

    public MetricAlertRuleEntity create(String gameId, MetricAlertRuleEntity req, String operator) {
        validate(req);
        req.id = "alr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        req.gameId = gameId;
        req.createdBy = operator;
        req.updatedBy = operator;
        MetricAlertRuleEntity saved = ruleRepo.save(req);
        auditLog.log(AuditLogEntity.AuditAction.CREATE, "metric_alert_rule", saved.id, saved.name,
                null, AuditLogEntity.AuditResult.SUCCESS, operator, null, json(saved), null, null, Map.of("gameId", gameId));
        return saved;
    }

    public MetricAlertRuleEntity update(String gameId, String ruleId, MetricAlertRuleEntity req, String operator) {
        MetricAlertRuleEntity existing = get(gameId, ruleId);
        if (existing == null) {
            throw new IllegalArgumentException("alert rule not found: " + ruleId);
        }
        validate(req);
        String before = json(existing);
        existing.name = req.name;
        existing.metricType = req.metricType;
        existing.conditionType = req.conditionType;
        existing.comparison = req.comparison;
        existing.threshold = req.threshold;
        existing.deviationPct = req.deviationPct;
        existing.environment = req.environment;
        existing.window = req.window;
        existing.severity = req.severity;
        existing.enabled = req.enabled;
        existing.notifyWebhook = req.notifyWebhook;
        existing.updatedBy = operator;
        MetricAlertRuleEntity saved = ruleRepo.save(existing);
        auditLog.log(AuditLogEntity.AuditAction.UPDATE, "metric_alert_rule", saved.id, saved.name,
                null, AuditLogEntity.AuditResult.SUCCESS, operator, before, json(saved), null, null, Map.of("gameId", gameId));
        return saved;
    }

    public boolean delete(String gameId, String ruleId, String operator) {
        MetricAlertRuleEntity existing = get(gameId, ruleId);
        if (existing == null) {
            return false;
        }
        existing.deletedAt = LocalDateTime.now();
        existing.updatedBy = operator;
        ruleRepo.save(existing);
        auditLog.log(AuditLogEntity.AuditAction.DELETE, "metric_alert_rule", existing.id, existing.name,
                null, AuditLogEntity.AuditResult.SUCCESS, operator, json(existing), null, null, null, Map.of("gameId", gameId));
        return true;
    }

    /** ABSOLUTE 需 threshold 且 comparison 只能 GT/LT；BASELINE_DEVIATION 需正数 deviationPct */
    private void validate(MetricAlertRuleEntity rule) {
        if (rule.name == null || rule.name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (rule.metricType == null || rule.conditionType == null || rule.comparison == null) {
            throw new IllegalArgumentException("metricType/conditionType/comparison are required");
        }
        if (rule.conditionType == MetricAlertRuleEntity.ConditionType.ABSOLUTE) {
            if (rule.threshold == null) {
                throw new IllegalArgumentException("threshold is required for ABSOLUTE condition");
            }
            if (rule.comparison == MetricAlertRuleEntity.Comparison.BOTH) {
                throw new IllegalArgumentException("BOTH comparison is only allowed for BASELINE_DEVIATION");
            }
        } else {
            if (rule.deviationPct == null || rule.deviationPct <= 0) {
                throw new IllegalArgumentException("deviationPct must be positive for BASELINE_DEVIATION");
            }
        }
    }

    // ===== 手动试算（不改任何状态） =====

    public Map<String, Object> evaluateNow(String gameId, String ruleId) {
        MetricAlertRuleEntity rule = get(gameId, ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("alert rule not found: " + ruleId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", ruleId);
        if (!clickHouse.isAvailable()) {
            out.put("available", false);
            return out;
        }
        LocalDateTime now = LocalDateTime.now();
        Double current = currentValue(gameId, rule.environment, rule.metricType, rule.window, now);
        Double baseline = rule.conditionType == MetricAlertRuleEntity.ConditionType.BASELINE_DEVIATION
                ? baselineValue(gameId, rule.environment, rule.metricType, rule.window, now) : null;
        boolean fired = current != null && fired(rule, current, baseline);
        out.put("available", true);
        out.put("currentValue", current);
        out.put("baseline", baseline);
        out.put("deviationPct", deviationPct(current, baseline));
        out.put("fired", fired);
        return out;
    }

    // ===== 告警历史与处理 =====

    public List<SystemAlertEntity> alertHistory(String gameId, int limit) {
        return alertRepo.findByGameId(gameId, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    public SystemAlertEntity acknowledge(String gameId, String alertId, String by, String comment) {
        SystemAlertEntity alert = ownedAlert(gameId, alertId);
        alert.acknowledge(by, comment);
        SystemAlertEntity saved = alertRepo.save(alert);
        auditLog.log(AuditLogEntity.AuditAction.UPDATE, "system_alert", saved.id, saved.title,
                null, AuditLogEntity.AuditResult.SUCCESS, by, null, "acknowledged", null, null, Map.of("gameId", gameId));
        return saved;
    }

    public SystemAlertEntity resolve(String gameId, String alertId, String by, String comment) {
        SystemAlertEntity alert = ownedAlert(gameId, alertId);
        alert.resolve(by, comment);
        SystemAlertEntity saved = alertRepo.save(alert);
        auditLog.log(AuditLogEntity.AuditAction.UPDATE, "system_alert", saved.id, saved.title,
                null, AuditLogEntity.AuditResult.SUCCESS, by, null, "resolved", null, null, Map.of("gameId", gameId));
        return saved;
    }

    private SystemAlertEntity ownedAlert(String gameId, String alertId) {
        SystemAlertEntity alert = alertRepo.findById(alertId)
                .filter(a -> a.deletedAt == null)
                .orElseThrow(() -> new IllegalArgumentException("alert not found: " + alertId));
        if (alert.gameId == null || !alert.gameId.equals(gameId)) {
            throw new IllegalArgumentException("alert not found: " + alertId);
        }
        return alert;
    }

    // ===== 调度评估 =====

    /** 三道守卫：enabled → CH 可用 → 逐规则 try-catch 隔离。 */
    @Scheduled(cron = "${oddsmaker.metric-alert.cron:0 */10 * * * *}")
    public void evaluateAll() {
        if (!enabled) {
            return;
        }
        if (!clickHouse.isAvailable()) {
            return;
        }
        List<MetricAlertRuleEntity> rules = ruleRepo.findByEnabledTrueAndDeletedAtIsNull();
        int fired = 0;
        for (MetricAlertRuleEntity rule : rules) {
            try {
                if (evaluateRule(rule)) {
                    fired++;
                }
            } catch (Exception ex) {
                logger.warn("evaluate alert rule {} failed: {}", rule.id, ex.getMessage());
            }
        }
        if (!rules.isEmpty()) {
            logger.info("metric alert evaluation: {} rules, {} firing", rules.size(), fired);
        }
    }

    /**
     * 评估单条规则：查当前值/基线 → 判定 → 告警沿触发 upsert → 回写规则评估状态。
     * @return 本轮是否处于触发状态
     */
    boolean evaluateRule(MetricAlertRuleEntity rule) {
        LocalDateTime now = LocalDateTime.now();
        Double current = currentValue(rule.gameId, rule.environment, rule.metricType, rule.window, now);
        Double baseline = rule.conditionType == MetricAlertRuleEntity.ConditionType.BASELINE_DEVIATION
                ? baselineValue(rule.gameId, rule.environment, rule.metricType, rule.window, now) : null;

        if (current == null) {
            return false;   // 查询异常，本轮跳过，不动 last_state
        }
        boolean firing = fired(rule, current, baseline);

        if (firing) {
            fireOrAccumulate(rule, current, baseline);
        } else if (rule.lastState == MetricAlertRuleEntity.State.FIRING) {
            logger.info("metric alert rule {} recovered (value={})", rule.id, current);
        }

        rule.lastEvaluatedAt = now;
        rule.lastValue = current;
        rule.lastBaseline = baseline;
        rule.lastState = firing ? MetricAlertRuleEntity.State.FIRING : MetricAlertRuleEntity.State.OK;
        ruleRepo.save(rule);
        return firing;
    }

    private boolean fired(MetricAlertRuleEntity rule, Double current, Double baseline) {
        if (rule.conditionType == MetricAlertRuleEntity.ConditionType.ABSOLUTE) {
            if (rule.comparison == MetricAlertRuleEntity.Comparison.LT) {
                return current < rule.threshold;
            }
            return current > rule.threshold;
        }
        Double deviation = deviationPct(current, baseline);
        if (deviation == null) {
            return false;
        }
        double n = rule.deviationPct;
        return switch (rule.comparison) {
            case LT -> deviation <= -n;
            case GT -> deviation >= n;
            case BOTH -> Math.abs(deviation) >= n;
        };
    }

    /** 沿触发抑制：已有活跃告警则累加，否则新建并发 Webhook。 */
    private void fireOrAccumulate(MetricAlertRuleEntity rule, Double current, Double baseline) {
        SystemAlertEntity active = alertRepo.findActiveByRuleId(rule.id).stream().findFirst().orElse(null);
        if (active != null) {
            active.occurrenceCount = (active.occurrenceCount == null ? 1 : active.occurrenceCount) + 1;
            active.lastOccurredAt = LocalDateTime.now();
            active.metricValue = current;
            alertRepo.save(active);
            return;
        }

        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        alert.gameId = rule.gameId;
        alert.ruleId = rule.id;
        alert.alertType = SystemAlertEntity.AlertType.ANOMALY_DETECTED;
        alert.severity = rule.severity;
        alert.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        alert.title = rule.name;
        alert.description = describe(rule, current, baseline);
        alert.source = "metric_alert";
        alert.affectedResource = rule.gameId;
        alert.metricValue = current;
        alert.thresholdValue = rule.conditionType == MetricAlertRuleEntity.ConditionType.ABSOLUTE
                ? rule.threshold : rule.deviationPct;
        alert.condition = conditionLabel(rule);
        alert.context = context(rule, current, baseline);
        alert.notificationSent = false;
        alertRepo.save(alert);

        if (Boolean.TRUE.equals(rule.notifyWebhook)) {
            webhookService.sendCustomWebhook(rule.gameId, "metric_alert", webhookPayload(rule, alert, current, baseline));
            alert.notificationSent = true;   // 语义为"已派发"，投递结果看 webhook_logs
            alertRepo.save(alert);
        }
        logger.warn("metric alert fired: rule={}, metric={}, value={}, baseline={}",
                rule.id, rule.metricType, current, baseline);
    }

    private String describe(MetricAlertRuleEntity rule, Double current, Double baseline) {
        if (rule.conditionType == MetricAlertRuleEntity.ConditionType.ABSOLUTE) {
            return String.format("%s: current %.2f %s %.2f", rule.name, current,
                    "LT".equals(rule.comparison.name()) ? "<" : ">", rule.threshold);
        }
        Double deviation = deviationPct(current, baseline);
        return String.format("%s: current %.2f vs baseline %.2f (deviation %s%%, threshold ±%.1f%%)",
                rule.name, current, baseline,
                deviation == null ? "n/a" : String.format("%+.1f", deviation), rule.deviationPct);
    }

    private String conditionLabel(MetricAlertRuleEntity rule) {
        if (rule.conditionType == MetricAlertRuleEntity.ConditionType.ABSOLUTE) {
            return rule.comparison == MetricAlertRuleEntity.Comparison.LT ? "lt" : "gt";
        }
        return switch (rule.comparison) {
            case LT -> "baseline_down";
            case GT -> "baseline_up";
            case BOTH -> "baseline_both";
        };
    }

    private String context(MetricAlertRuleEntity rule, Double current, Double baseline) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("ruleId", rule.id);
        ctx.put("metricType", rule.metricType.name());
        ctx.put("conditionType", rule.conditionType.name());
        ctx.put("window", rule.window.name());
        ctx.put("environment", rule.environment);
        ctx.put("currentValue", current);
        ctx.put("baseline", baseline);
        ctx.put("deviationPct", deviationPct(current, baseline));
        return json(ctx);
    }

    private Map<String, Object> webhookPayload(MetricAlertRuleEntity rule, SystemAlertEntity alert,
                                               Double current, Double baseline) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "metric_alert");
        payload.put("alertId", alert.id);
        payload.put("ruleId", rule.id);
        payload.put("ruleName", rule.name);
        payload.put("gameId", rule.gameId);
        payload.put("severity", rule.severity.name());
        payload.put("metricType", rule.metricType.name());
        payload.put("condition", conditionLabel(rule));
        payload.put("currentValue", current);
        payload.put("baseline", baseline);
        payload.put("deviationPct", deviationPct(current, baseline));
        payload.put("thresholdValue", alert.thresholdValue);
        payload.put("window", rule.window.name());
        payload.put("firedAt", LocalDateTime.now().toString());
        return payload;
    }

    /** 偏差百分比；baseline 缺失或为 0 时返回 null（除零噪声跳过） */
    static Double deviationPct(Double current, Double baseline) {
        if (current == null || baseline == null || baseline == 0) {
            return null;
        }
        return (current - baseline) / baseline * 100.0;
    }

    // ===== 指标查询（package-private，便于单测替换 now） =====

    Double currentValue(String gameId, String environment, MetricAlertRuleEntity.MetricType type,
                        MetricAlertRuleEntity.Window window, LocalDateTime now) {
        return queryMetric(gameId, environment, type, currentRange(window, now));
    }

    Double baselineValue(String gameId, String environment, MetricAlertRuleEntity.MetricType type,
                         MetricAlertRuleEntity.Window window, LocalDateTime now) {
        return queryMetric(gameId, environment, type, baselineRange(window, now));
    }

    private Double queryMetric(String gameId, String environment, MetricAlertRuleEntity.MetricType type,
                               LocalDateTime[] range) {
        String env = environment == null || environment.isBlank() ? "" : " AND environment = ?";
        List<Object> args = new ArrayList<>();
        args.add(gameId);
        if (!env.isEmpty()) {
            args.add(environment);
        }
        args.add(range[0]);
        args.add(range[1]);
        String sql = switch (type) {
            case DAU -> "SELECT uniqExact(" + SUBJECT + ") AS v FROM events "
                    + "WHERE game_id = ?" + env + " AND ts_server >= ? AND ts_server < ?";
            case REVENUE -> "SELECT coalesce(sumIf(revenue_amount, revenue_amount > 0), 0) AS v FROM events "
                    + "WHERE game_id = ?" + env + " AND ts_server >= ? AND ts_server < ?";
            case CRASH_RATE -> "SELECT uniqExactIf(" + SUBJECT + ", event_type = 'error') "
                    + "/ greatest(uniqExact(" + SUBJECT + "), 1) AS v FROM events "
                    + "WHERE game_id = ?" + env + " AND ts_server >= ? AND ts_server < ?";
        };
        try {
            List<Map<String, Object>> rows = clickHouse.query(sql, args.toArray());
            if (rows.isEmpty() || rows.get(0).get("v") == null) {
                return null;
            }
            Object v = rows.get(0).get("v");
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
        } catch (Exception ex) {
            logger.warn("metric query failed ({}): {}", type, ex.getMessage());
            return null;
        }
    }

    /** 当前窗口：TODAY=今日 00:00→now；HOUR_1=now-1h→now */
    static LocalDateTime[] currentRange(MetricAlertRuleEntity.Window window, LocalDateTime now) {
        if (window == MetricAlertRuleEntity.Window.HOUR_1) {
            return new LocalDateTime[]{now.minusHours(1), now};
        }
        return new LocalDateTime[]{now.toLocalDate().atStartOfDay(), now};
    }

    /** 基线窗口：昨日同时段（墙钟对比）；now 由调用方传入便于测试 */
    static LocalDateTime[] baselineRange(MetricAlertRuleEntity.Window window, LocalDateTime now) {
        LocalDateTime yesterday = now.minusDays(1);
        if (window == MetricAlertRuleEntity.Window.HOUR_1) {
            return new LocalDateTime[]{yesterday.minusHours(1), yesterday};
        }
        return new LocalDateTime[]{yesterday.toLocalDate().atStartOfDay(), yesterday};
    }

    private String json(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return String.valueOf(obj);
        }
    }
}
