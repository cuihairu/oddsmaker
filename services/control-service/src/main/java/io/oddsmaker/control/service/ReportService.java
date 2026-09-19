package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 报表服务
 * 管理自定义报表的创建、执行和调度
 */
@Service
@Transactional
public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    @Autowired
    private ReportRepo reportRepo;

    @Autowired
    private ReportExecutionRepo executionRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ClickHouseClient clickHouse;

    // ===== 真实查询引擎：词汇白名单（标识符全部来自服务端静态映射，用户输入零拼接） =====

    /** 主体口径与 OnlineMetricsService/MetricAlertService 一致：player_id > user_id > device_id */
    private static final String SUBJECT =
        "if(player_id != '', player_id, if(user_id != '', user_id, device_id))";

    /** 指标键 → 列表达式（聚合 op 作用于该列；events 恒 1 供纯计数） */
    private static final Map<String, String> METRIC_COLUMNS = Map.of(
        "players", SUBJECT,
        "events", "1",
        "revenue", "revenue_amount");

    /** 维度键 → 安全分组表达式（OnlineMetricsService DIMENSIONS 同款模式） */
    private static final Map<String, String> DIMENSION_COLUMNS = Map.of(
        "event_type", "if(event_type = '', 'unknown', event_type)",
        "platform", "if(platform = '', 'unknown', platform)",
        "app_version", "if(app_version = '', 'unknown', app_version)",
        "channel", "if(attribution['channel'] != '', attribution['channel'], 'unknown')",
        "environment", "if(environment = '', 'unknown', environment)");

    /** 时间粒度 → CH 时间桶函数（作用于 ts_server；周桶对齐周一） */
    private static final Map<String, String> GRANULARITY_FUNCTIONS = Map.of(
        "minute", "toStartOfMinute",
        "hour", "toStartOfHour",
        "day", "toStartOfDay",
        "week", "toMonday",
        "month", "toStartOfMonth");

    /** 聚合 op 白名单（sum/avg 带 coalesce 保数字稳定） */
    private static final Set<String> AGG_OPS = Set.of("count", "count_distinct", "sum", "avg", "min", "max");

    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("^(\\d+)([hd])$");
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,40}$");
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_RANGE_DAYS = 365;

    /**
     * 创建报表
     */
    public ReportEntity createReport(String gameId, String environmentId, String name, String displayName,
                                     String description, ReportEntity.ReportType reportType, String reportCategory,
                                     Map<String, Object> queryConfig, Map<String, Object> visualization,
                                     String chartType, String createdBy) {

        // 检查名称唯一性
        var existing = reportRepo.findByGameIdAndName(gameId, name);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Report name already exists: " + name);
        }

        ReportEntity report = new ReportEntity();
        report.id = "rpt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        report.gameId = gameId;
        report.environmentId = environmentId;
        report.name = name;
        report.displayName = displayName;
        report.description = description;
        report.reportType = reportType != null ? reportType : ReportEntity.ReportType.CUSTOM;
        report.reportCategory = reportCategory;
        report.chartType = chartType;
        report.status = ReportEntity.ReportStatus.DRAFT;
        report.createdBy = createdBy;

        try {
            if (queryConfig != null) {
                report.queryConfig = objectMapper.writeValueAsString(queryConfig);
            }
            if (visualization != null) {
                report.visualization = objectMapper.writeValueAsString(visualization);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize report config", e);
            throw new RuntimeException("Failed to serialize report config", e);
        }

        report = reportRepo.save(report);

        // 记录审计日志
        auditLogService.logCreate("report", report.id, name, createdBy, null, null,
            Map.of("gameId", gameId, "reportType", report.reportType.name()));

        logger.info("Created report: {} for game: {}", name, gameId);
        return report;
    }

    /**
     * 发布报表
     */
    public ReportEntity publishReport(String reportId, String publishedBy) {
        ReportEntity report = reportRepo.findById(reportId)
            .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

        report.markAsPublished();
        report.updatedBy = publishedBy;
        report = reportRepo.save(report);

        // 记录审计日志
        auditLogService.log(
            AuditLogEntity.AuditAction.ACTIVATE,
            "report",
            report.id,
            report.name,
            "Published report",
            AuditLogEntity.AuditResult.SUCCESS,
            publishedBy,
            null,
            null,
            null,
            null,
            Map.of("gameId", report.gameId)
        );

        logger.info("Published report: {}", report.name);
        return report;
    }

    /**
     * 执行报表
     */
    public ReportExecutionEntity executeReport(String reportId, String triggeredBy, String triggerType,
                                             Map<String, Object> parameters, Map<String, Object> filters) {
        ReportEntity report = reportRepo.findById(reportId)
            .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));

        if (report.deletedAt != null) {
            throw new IllegalArgumentException("Report is deleted: " + reportId);
        }

        // 创建执行记录
        ReportExecutionEntity execution = new ReportExecutionEntity();
        execution.id = "re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        execution.reportId = reportId;
        execution.gameId = report.gameId;
        execution.triggeredBy = triggeredBy;
        execution.triggerType = triggerType != null ? triggerType : "manual";
        execution.executionStatus = ReportExecutionEntity.ExecutionStatus.PENDING;

        try {
            if (parameters != null) {
                execution.parameters = objectMapper.writeValueAsString(parameters);
            }
            if (filters != null) {
                execution.filters = objectMapper.writeValueAsString(filters);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize execution parameters", e);
        }

        execution = executionRepo.save(execution);

        // 异步执行报表
        executeReportAsync(report, execution);

        return execution;
    }

    /**
     * 获取报表详情
     */
    @Transactional(readOnly = true)
    public ReportEntity getReport(String reportId) {
        return reportRepo.findById(reportId)
            .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));
    }

    /**
     * 获取游戏的报表列表
     */
    @Transactional(readOnly = true)
    public List<ReportEntity> getGameReports(String gameId) {
        return reportRepo.findByGameId(gameId);
    }

    /**
     * 获取已发布的报表
     */
    @Transactional(readOnly = true)
    public List<ReportEntity> getPublishedReports(String gameId) {
        return reportRepo.findPublishedByGameId(gameId);
    }

    /**
     * 获取报表执行历史
     */
    @Transactional(readOnly = true)
    public List<ReportExecutionEntity> getReportExecutions(String reportId) {
        return executionRepo.findByReportId(reportId);
    }

    /**
     * 获取报表统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getReportStats(String reportId) {
        long totalRuns = executionRepo.countByReportId(reportId);
        long successRuns = executionRepo.countSuccessByReportId(reportId);
        Double avgExecutionTime = executionRepo.averageExecutionTime(reportId);
        Long totalRows = executionRepo.sumRowCountByReportId(reportId);

        List<ReportExecutionEntity> recentExecutions = executionRepo.findByReportId(reportId);

        Map<String, Long> byStatus = recentExecutions.stream()
            .collect(Collectors.groupingBy(e -> e.executionStatus.name(), Collectors.counting()));

        Map<String, Long> byTriggerType = recentExecutions.stream()
            .collect(Collectors.groupingBy(e -> e.triggerType != null ? e.triggerType : "unknown", Collectors.counting()));

        return Map.of(
            "totalRuns", totalRuns,
            "successRuns", successRuns,
            "failedRuns", totalRuns - successRuns,
            "successRate", totalRuns > 0 ? (double) successRuns / totalRuns : 0.0,
            "avgExecutionTimeMs", avgExecutionTime != null ? avgExecutionTime : 0.0,
            "totalRows", totalRows != null ? totalRows : 0L,
            "byStatus", byStatus,
            "byTriggerType", byTriggerType
        );
    }

    /**
     * 获取游戏的报表概览
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getGameReportOverview(String gameId) {
        List<ReportEntity> allReports = reportRepo.findByGameId(gameId);
        List<ReportEntity> publishedReports = reportRepo.findPublishedByGameId(gameId);
        List<ReportEntity> scheduledReports = reportRepo.findScheduledReports().stream()
            .filter(r -> r.gameId.equals(gameId))
            .collect(Collectors.toList());

        long totalExecutions = 0;
        long totalRows = 0;

        for (ReportEntity report : allReports) {
            Map<String, Object> stats = getReportStats(report.id);
            totalExecutions += (Long) stats.get("totalRuns");
            totalRows += (Long) stats.get("totalRows");
        }

        Map<String, Long> byType = allReports.stream()
            .collect(Collectors.groupingBy(r -> r.reportType.name(), Collectors.counting()));

        Map<String, Long> byCategory = allReports.stream()
            .filter(r -> r.reportCategory != null)
            .collect(Collectors.groupingBy(r -> r.reportCategory, Collectors.counting()));

        return Map.of(
            "totalReports", allReports.size(),
            "publishedReports", publishedReports.size(),
            "scheduledReports", scheduledReports.size(),
            "draftReports", allReports.stream().filter(r -> r.isDraft()).count(),
            "totalExecutions", totalExecutions,
            "totalRowsProcessed", totalRows,
            "byType", byType,
            "byCategory", byCategory
        );
    }

    /**
     * 定期检查超时的执行
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    @Transactional
    public void checkTimeoutExecutions() {
        try {
            LocalDateTime timeout = LocalDateTime.now().minusMinutes(30);  // 30分钟超时
            List<ReportExecutionEntity> timeoutExecutions = executionRepo.findTimeout(timeout);

            for (ReportExecutionEntity execution : timeoutExecutions) {
                execution.executionStatus = ReportExecutionEntity.ExecutionStatus.TIMEOUT;
                execution.statusMessage = "Execution timeout";
                execution.completedAt = LocalDateTime.now();
                executionRepo.save(execution);

                logger.warn("Report execution timed out: {}", execution.id);
            }

            if (!timeoutExecutions.isEmpty()) {
                logger.info("Marked {} executions as timeout", timeoutExecutions.size());
            }
        } catch (Exception e) {
            logger.error("Failed to check timeout executions", e);
        }
    }

    /**
     * 清理过期执行记录
     */
    @Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨3点执行
    @Transactional
    public void cleanupExpiredExecutions() {
        try {
            LocalDateTime expireAt = LocalDateTime.now().minusDays(90);  // 保留90天
            int deleted = executionRepo.deleteExpired(expireAt);
            if (deleted > 0) {
                logger.info("Cleaned up {} expired report executions", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired executions", e);
        }
    }

    // 私有辅助方法

    private void executeReportAsync(ReportEntity report, ReportExecutionEntity execution) {
        try {
            // 标记为运行中
            execution.markAsRunning();
            executionRepo.save(execution);

            // 更新报表运行记录（开始只标记状态；totalRuns 由完成/失败各计一次，避免双计）
            report.markRunning();
            reportRepo.save(report);

            // 真实执行：ClickHouse 白名单聚合查询（配置解析失败/CH 不可达均诚实落 FAILED）
            executeRealReport(report, execution);
            executionRepo.save(execution);

            // 成功侧回写最后运行状态（旧实现只写 running 从不收口）
            report.recordRun("completed");
            reportRepo.save(report);

        } catch (Exception e) {
            execution.markAsFailed(e.getMessage());
            executionRepo.save(execution);

            report.recordRun("failed");
            reportRepo.save(report);

            logger.error("Report execution failed: {} - {}", execution.id, e.getMessage(), e);
        }
    }

    /**
     * 真实报表执行：把报表词汇（timeGranularity/defaultTimeRange/groupBy/aggregations/queryConfig）
     * 编译成 ClickHouse 白名单聚合查询并写回结果。标识符全部来自服务端静态映射表（零用户输入拼接），
     * 值全部走 ? 参数化；坏配置/CH 不可达抛异常由外层诚实落 FAILED。
     */
    private void executeRealReport(ReportEntity report, ReportExecutionEntity execution) throws Exception {
        long startMs = System.currentTimeMillis();

        // 时间窗：执行参数 timeRange 优先于报表默认
        Map<String, Object> execParams = readJsonMap(execution.parameters);
        Map<String, Object> config = readJsonMap(report.queryConfig);
        String rangeSpec = execParams != null && execParams.get("timeRange") != null
            ? String.valueOf(execParams.get("timeRange")) : report.defaultTimeRange;
        LocalDateTime[] range = parseTimeRange(rangeSpec);

        // 时间桶
        String granularity = report.timeGranularity == null || report.timeGranularity.isBlank()
            ? "day" : report.timeGranularity;
        String bucketFn = GRANULARITY_FUNCTIONS.get(granularity);
        if (bucketFn == null) {
            throw new IllegalArgumentException("Unsupported timeGranularity '" + granularity
                + "'; valid: " + GRANULARITY_FUNCTIONS.keySet());
        }

        // 聚合指标（alias → "op" 或 "op:metric"）；缺省 = 事件数 + 玩家数
        Map<String, Object> aggregations = readJsonMap(report.aggregations);
        List<String> aggAliases = new ArrayList<>();
        List<String> aggExprs = new ArrayList<>();
        if (aggregations == null || aggregations.isEmpty()) {
            aggregations = Map.of("events", "count", "players", "count_distinct:players");
        }
        for (Map.Entry<String, Object> entry : aggregations.entrySet()) {
            aggAliases.add("a_" + validateAlias(entry.getKey()));
            aggExprs.add(parseAggregation(entry.getKey(), entry.getValue()));
        }

        // 分组维度（report.groupBy JSON 数组，键限维度白名单）
        List<String> dimensions = readJsonList(report.groupBy);
        List<String> dimAliases = new ArrayList<>();
        List<String> dimExprs = new ArrayList<>();
        if (dimensions != null) {
            for (String dim : dimensions) {
                String expr = DIMENSION_COLUMNS.get(dim);
                if (expr == null) {
                    throw new IllegalArgumentException("Unknown groupBy dimension '" + dim
                        + "'; valid: " + DIMENSION_COLUMNS.keySet());
                }
                dimAliases.add("d_" + dim);
                dimExprs.add(expr);
            }
        }

        // 可用性检查放配置解析后：坏配置先报配置错（即使 CH 未配置，错误也更可行动）
        if (!clickHouse.isAvailable()) {
            throw new IllegalStateException(
                "ClickHouse is not configured (oddsmaker.clickhouse.url empty); report query unavailable");
        }

        // SQL：SELECT bucket, d_*, a_* FROM events WHERE ... GROUP BY bucket, d_* ORDER BY bucket
        List<String> selects = new ArrayList<>();
        selects.add(bucketFn + "(ts_server) AS bucket");
        for (int i = 0; i < dimExprs.size(); i++) {
            selects.add(dimExprs.get(i) + " AS " + dimAliases.get(i));
        }
        for (int i = 0; i < aggExprs.size(); i++) {
            selects.add(aggExprs.get(i) + " AS " + aggAliases.get(i));
        }

        List<Object> args = new ArrayList<>();
        args.add(report.gameId);
        args.add(range[0]);
        args.add(range[1]);
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", selects))
            .append(" FROM events WHERE game_id = ? AND ts_server >= ? AND ts_server < ?");

        // 过滤：queryConfig.environment 等值 + queryConfig.eventTypes 集合（值全参数化）
        if (config != null) {
            Object env = config.get("environment");
            if (env != null && !String.valueOf(env).isBlank()) {
                sql.append(" AND environment = ?");
                args.add(String.valueOf(env));
            }
            Object eventTypes = config.get("eventTypes");
            if (eventTypes instanceof List<?> types && !types.isEmpty()) {
                sql.append(" AND event_type IN (");
                for (int i = 0; i < types.size(); i++) {
                    sql.append(i > 0 ? ", ?" : "?");
                    args.add(String.valueOf(types.get(i)));
                }
                sql.append(")");
            }
        }

        sql.append(" GROUP BY bucket");
        for (String dimAlias : dimAliases) {
            sql.append(", ").append(dimAlias);
        }
        sql.append(" ORDER BY bucket LIMIT ").append(MAX_ROWS);

        long queryStartMs = System.currentTimeMillis();
        List<Map<String, Object>> rows = clickHouse.query(sql.toString(), args.toArray());
        long queryMs = System.currentTimeMillis() - queryStartMs;

        // 写回结果（小数据集内联 resultData；行数/耗时为真实值）
        List<String> columns = new ArrayList<>();
        columns.add("bucket");
        columns.addAll(dimAliases);
        columns.addAll(aggAliases);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rowCount", rows.size());
        summary.put("columns", columns);
        summary.put("table", "events");
        summary.put("timeRange", rangeSpec == null ? "7d" : rangeSpec);
        summary.put("granularity", granularity);

        execution.queryTimeMs = queryMs;
        execution.resultData = objectMapper.writeValueAsString(rows);
        execution.markAsCompleted((long) rows.size(),
            objectMapper.writeValueAsString(summary), System.currentTimeMillis() - startMs);
        logger.info("Report executed for real: {} rows={} queryMs={}", execution.id, rows.size(), queryMs);
    }

    /** "1h"/"24h"/"7d"/"30d"/"90d" 形态时间窗，上限 365 天/8760 小时。 */
    private LocalDateTime[] parseTimeRange(String spec) {
        if (spec == null || spec.isBlank()) {
            spec = "7d";
        }
        Matcher matcher = TIME_RANGE_PATTERN.matcher(spec.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid timeRange '" + spec + "': expected <n>h or <n>d");
        }
        long n = Long.parseLong(matcher.group(1));
        boolean hours = matcher.group(2).equals("h");
        if (hours ? n > MAX_RANGE_DAYS * 24L : n > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("timeRange '" + spec + "' exceeds max " + MAX_RANGE_DAYS + "d");
        }
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = hours ? end.minusHours(n) : end.minusDays(n);
        return new LocalDateTime[]{start, end};
    }

    /** 聚合 spec 编译："count" | "op:metric"，op/metric 均过白名单（未知 op 由 switch default 报错）。 */
    private String parseAggregation(String alias, Object specObj) {
        String spec = String.valueOf(specObj).trim();
        String op = spec;
        String metric = null;
        int colon = spec.indexOf(':');
        if (colon >= 0) {
            op = spec.substring(0, colon);
            metric = spec.substring(colon + 1);
        }
        if ("count".equals(op)) {
            return "count()";
        }
        String column = METRIC_COLUMNS.get(metric);
        if (column == null) {
            throw new IllegalArgumentException("Unknown metric '" + metric + "' (alias " + alias
                + "); valid: " + METRIC_COLUMNS.keySet());
        }
        return switch (op) {
            case "count_distinct" -> "uniqExact(" + column + ")";
            case "sum" -> "coalesce(sum(" + column + "), 0)";
            case "avg" -> "avg(" + column + ")";
            case "min" -> "min(" + column + ")";
            case "max" -> "max(" + column + ")";
            default -> throw new IllegalArgumentException("Unsupported aggregation op '" + op
                + "' (alias " + alias + "); valid: " + AGG_OPS);
        };
    }

    /** 聚合别名最终会拼成列别名 a_<alias>，限标识符安全字符。 */
    private String validateAlias(String alias) {
        if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
            throw new IllegalArgumentException(
                "Invalid aggregation alias '" + alias + "': must match [a-zA-Z0-9_]{1,40}");
        }
        return alias;
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON config: " + e.getMessage());
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON config: " + e.getMessage());
        }
    }

    /**
     * 搜索报表
     */
    @Transactional(readOnly = true)
    public List<ReportEntity> searchReports(String gameId, String query) {
        return reportRepo.search(gameId, query);
    }

    /**
     * 获取热门报表
     */
    @Transactional(readOnly = true)
    public List<ReportEntity> getPopularReports(String gameId) {
        return reportRepo.findPopularReports(gameId).stream()
            .limit(10)
            .collect(Collectors.toList());
    }

    /**
     * 获取最近运行的报表
     */
    @Transactional(readOnly = true)
    public List<ReportEntity> getRecentlyRunReports(String gameId) {
        return reportRepo.findRecentlyRun(gameId).stream()
            .limit(10)
            .collect(Collectors.toList());
    }
}
