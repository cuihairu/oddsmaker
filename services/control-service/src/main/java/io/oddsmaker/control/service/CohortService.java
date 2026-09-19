package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 同期群分析服务
 * 计算和管理用户同期群分析
 */
@Service
@Transactional
public class CohortService {

    private static final Logger logger = LoggerFactory.getLogger(CohortService.class);

    @Autowired
    private CohortRepo cohortRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Autowired
    private ClickHouseClient clickHouse;

    /** 主体口径：与 OnlineMetricsService/ReportService 一致的 subject 三级回落。 */
    private static final String SUBJECT = "if(player_id != '', player_id, if(user_id != '', user_id, device_id))";

    /** timeUnit → 首见分桶函数 / INTERVAL 单元（白名单，标识符零用户输入）。 */
    private static final Map<String, String> BUCKET_FUNCTIONS = Map.of(
        "day", "toStartOfDay", "week", "toMonday", "month", "toStartOfMonth");
    private static final Map<String, String> INTERVAL_UNITS = Map.of(
        "day", "DAY", "week", "WEEK", "month", "MONTH");

    /** 留存周期上限：INTERVAL 字面量按纯数字拼接（int 无法参数化进 INTERVAL），先白名单校验再使用。 */
    private static final int MAX_RETENTION_PERIOD = 3650;

    /**
     * 创建同期群
     */
    public CohortEntity createCohort(String gameId, String environmentId, String name, String displayName,
                                    String description, CohortEntity.CohortType cohortType,
                                    LocalDate startDate, LocalDate endDate, String timeUnit,
                                    String analysisType, String metricType,
                                    Map<String, Object> behaviorDefinition,
                                    List<Integer> retentionPeriods, String createdBy) {

        // 检查名称唯一性
        var existing = cohortRepo.findByGameIdAndName(gameId, name);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Cohort name already exists: " + name);
        }

        CohortEntity cohort = new CohortEntity();
        cohort.id = "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        cohort.gameId = gameId;
        cohort.environmentId = environmentId;
        cohort.name = name;
        cohort.displayName = displayName;
        cohort.description = description;
        cohort.cohortType = cohortType != null ? cohortType : CohortEntity.CohortType.ACQUISITION;
        cohort.startDate = startDate;
        cohort.endDate = endDate;
        cohort.timeUnit = timeUnit != null ? timeUnit : "day";
        cohort.analysisType = analysisType != null ? analysisType : "retention";
        cohort.metricType = metricType != null ? metricType : "return_rate";
        cohort.status = CohortEntity.CohortStatus.PENDING;
        cohort.createdBy = createdBy;

        try {
            if (behaviorDefinition != null) {
                cohort.behaviorDefinition = objectMapper.writeValueAsString(behaviorDefinition);
            }
            if (retentionPeriods != null && !retentionPeriods.isEmpty()) {
                cohort.retentionPeriods = objectMapper.writeValueAsString(retentionPeriods);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize cohort config", e);
            throw new RuntimeException("Failed to serialize cohort config", e);
        }

        cohort = cohortRepo.save(cohort);

        // 记录审计日志
        auditLogService.logCreate("cohort", cohort.id, name, createdBy, null, null,
            Map.of("gameId", gameId, "cohortType", cohort.cohortType.name()));

        logger.info("Created cohort: {} for game: {}", name, gameId);
        return cohort;
    }

    /**
     * 计算同期群
     */
    public CohortEntity calculateCohort(String cohortId) {
        CohortEntity cohort = cohortRepo.findById(cohortId)
            .orElseThrow(() -> new IllegalArgumentException("Cohort not found: " + cohortId));

        if (!cohort.isPending()) {
            throw new IllegalStateException("Cohort is not in PENDING status: " + cohort.status);
        }

        cohort.markAsCalculating();
        cohortRepo.save(cohort);

        try {
            LocalDateTime startTime = LocalDateTime.now();

            // 真实计算：ClickHouse 首见分桶 + 逐周期回访（配置非法/CH 不可达均诚实落 FAILED）
            Map<String, Object> resultData = computeCohort(cohort);

            long cohortCount = ((Number) resultData.getOrDefault("cohortCount", 0L)).longValue();
            String resultSummary = buildResultSummary(resultData);

            long calculationTime = java.time.temporal.ChronoUnit.MILLIS.between(startTime, LocalDateTime.now());

            cohort.markAsCompleted(
                cohortCount,
                objectMapper.writeValueAsString(resultData),
                resultSummary,
                calculationTime
            );
            cohortRepo.save(cohort);

            logger.info("Calculated cohort: {} with {} users, took {}ms", cohort.name, cohortCount, calculationTime);

        } catch (Exception e) {
            cohort.markAsFailed(e.getMessage());
            cohortRepo.save(cohort);
            logger.error("Failed to calculate cohort: {} - {}", cohort.name, e.getMessage(), e);
            throw new RuntimeException("Failed to calculate cohort", e);
        }

        return cohort;
    }

    /**
     * 获取同期群详情
     */
    @Transactional(readOnly = true)
    public CohortEntity getCohort(String cohortId) {
        return cohortRepo.findById(cohortId)
            .orElseThrow(() -> new IllegalArgumentException("Cohort not found: " + cohortId));
    }

    /**
     * 获取游戏的同期群列表
     */
    @Transactional(readOnly = true)
    public List<CohortEntity> getGameCohorts(String gameId) {
        return cohortRepo.findByGameId(gameId);
    }

    /**
     * 获取已完成的同期群
     */
    @Transactional(readOnly = true)
    public List<CohortEntity> getCompletedCohorts(String gameId) {
        return cohortRepo.findCompletedByGameId(gameId);
    }

    /**
     * 获取同期群结果
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getCohortResults(String cohortId) {
        CohortEntity cohort = getCohort(cohortId);

        if (!cohort.hasResults()) {
            return Map.of("status", "no_results", "cohortId", cohortId);
        }

        try {
            return objectMapper.readValue(cohort.resultData, Map.class);
        } catch (Exception e) {
            logger.error("Failed to parse cohort results", e);
            return Map.of("status", "parse_error", "cohortId", cohortId);
        }
    }

    /**
     * 获取同期群统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getCohortStats(String gameId) {
        List<CohortEntity> allCohorts = cohortRepo.findByGameId(gameId);
        List<CohortEntity> completedCohorts = cohortRepo.findCompletedByGameId(gameId);

        long totalUsers = completedCohorts.stream()
            .mapToLong(c -> c.cohortCount != null ? c.cohortCount : 0)
            .sum();

        Map<String, Long> byType = allCohorts.stream()
            .collect(Collectors.groupingBy(c -> c.cohortType.name(), Collectors.counting()));

        Map<String, Long> byStatus = allCohorts.stream()
            .collect(Collectors.groupingBy(c -> c.status.name(), Collectors.counting()));

        Map<String, Long> byAnalysisType = allCohorts.stream()
            .filter(c -> c.analysisType != null)
            .collect(Collectors.groupingBy(c -> c.analysisType, Collectors.counting()));

        return Map.of(
            "totalCohorts", allCohorts.size(),
            "completedCohorts", completedCohorts.size(),
            "pendingCohorts", allCohorts.stream().filter(CohortEntity::isPending).count(),
            "totalUsersAnalyzed", totalUsers,
            "byType", byType,
            "byStatus", byStatus,
            "byAnalysisType", byAnalysisType
        );
    }

    /**
     * 定期处理待计算的同期群
     */
    @Scheduled(fixedDelay = 300000)  // 每5分钟执行一次
    public void processPendingCohorts() {
        try {
            List<CohortEntity> pendingCohorts = cohortRepo.findPending();

            for (CohortEntity cohort : pendingCohorts) {
                try {
                    calculateCohort(cohort.id);
                } catch (Exception e) {
                    logger.error("Failed to calculate cohort {}: {}", cohort.id, e.getMessage());
                }
            }

            if (!pendingCohorts.isEmpty()) {
                logger.info("Processed {} pending cohorts", pendingCohorts.size());
            }
        } catch (Exception e) {
            logger.error("Failed to process pending cohorts", e);
        }
    }

    // 私有辅助方法

    /**
     * 真实同期群计算（ACQUISITION + retention）：
     * 首见分桶 = 窗口内按 SUBJECT 的 min(桶函数(ts_server)) 分组；
     * 周期留存 = 首见群体中在首见桶 + N 个 timeUnit 仍有活动的主体数。
     * 标识符全部来自服务端静态映射（零用户输入拼接）；值全 ? 参数化，
     * 唯一例外是 INTERVAL 字面量的纯数字（int 白名单校验后拼接，无注入面）。
     * 语义收窄：非 ACQUISITION 类型 / 非 retention 分析无执行定义，诚实 FAILED。
     */
    private Map<String, Object> computeCohort(CohortEntity cohort) throws Exception {
        if (cohort.gameId == null || cohort.gameId.isBlank()) {
            throw new IllegalArgumentException("Cohort has no gameId; cannot scope the query");
        }
        if (cohort.cohortType != CohortEntity.CohortType.ACQUISITION) {
            throw new IllegalArgumentException("Only ACQUISITION cohorts are computed for real; '"
                + cohort.cohortType + "' has no executable definition (behaviorDefinition carries no query semantics yet)");
        }
        if (!"retention".equals(cohort.analysisType)) {
            throw new IllegalArgumentException("Only 'retention' analysis is supported; got '" + cohort.analysisType + "'");
        }
        if (cohort.startDate == null || cohort.endDate == null) {
            throw new IllegalArgumentException("Cohort window is required: set startDate and endDate");
        }
        if (!cohort.startDate.isBefore(cohort.endDate)) {
            throw new IllegalArgumentException("startDate must be before endDate: "
                + cohort.startDate + " >= " + cohort.endDate);
        }

        String unit = cohort.timeUnit == null || cohort.timeUnit.isBlank() ? "day" : cohort.timeUnit;
        String bucketFn = BUCKET_FUNCTIONS.get(unit);
        if (bucketFn == null) {
            throw new IllegalArgumentException("Unsupported timeUnit '" + unit + "'; valid: " + BUCKET_FUNCTIONS.keySet());
        }
        String intervalUnit = INTERVAL_UNITS.get(unit);
        List<Integer> periods = parseRetentionPeriods(cohort);

        // 环境过滤：environmentId 是环境表 id，事件表存的是环境名，需映射
        String envName = null;
        if (cohort.environmentId != null && !cohort.environmentId.isBlank()) {
            GameEnvironmentEntity env = gameEnvironmentRepo.findById(cohort.environmentId)
                .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + cohort.environmentId));
            envName = env.name;
        }

        if (!clickHouse.isAvailable()) {
            throw new IllegalStateException(
                "ClickHouse is not configured (oddsmaker.clickhouse.url empty); cohort calculation unavailable");
        }

        LocalDateTime start = cohort.startDate.atStartOfDay();
        LocalDateTime end = cohort.endDate.plusDays(1).atStartOfDay();  // 含 endDate 全天

        // 首见分桶子查询（群体查询与逐周期回访查询共用）
        String firstBucketSubquery = "SELECT " + SUBJECT + " AS subject, min(" + bucketFn
            + "(ts_server)) AS first_bucket FROM events WHERE game_id = ? AND ts_server >= ? AND ts_server < ?";
        if (envName != null) {
            firstBucketSubquery += " AND environment = ?";
        }
        firstBucketSubquery += " GROUP BY subject";

        List<Object> args = new ArrayList<>(List.of(cohort.gameId, start, end));
        if (envName != null) {
            args.add(envName);
        }

        List<Map<String, Object>> bucketRows = clickHouse.query(
            "SELECT first_bucket, uniqExact(subject) AS size FROM (" + firstBucketSubquery
                + ") GROUP BY first_bucket ORDER BY first_bucket",
            args.toArray());

        // 桶行 → 有序映射（CH 返回 Timestamp/LocalDateTime，统一 toString 展示）
        List<Map<String, Object>> buckets = new ArrayList<>();
        Map<String, Map<String, Object>> byBucket = new LinkedHashMap<>();
        for (Map<String, Object> row : bucketRows) {
            String bucket = String.valueOf(row.get("first_bucket"));
            long size = ((Number) row.getOrDefault("size", 0)).longValue();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("bucket", bucket);
            b.put("size", size);
            b.put("retention", new LinkedHashMap<String, Object>());
            byBucket.put(bucket, b);
            buckets.add(b);
        }

        // 逐周期回访：INTERVAL 字面量仅接受 parseInt 白名单后的纯数字
        for (Integer period : periods) {
            List<Map<String, Object>> returnedRows = clickHouse.query(
                "SELECT f.first_bucket AS first_bucket, uniqExact(f.subject) AS returned FROM ("
                    + firstBucketSubquery + ") f INNER JOIN (SELECT DISTINCT " + SUBJECT + " AS subject, "
                    + bucketFn + "(ts_server) AS act_bucket FROM events WHERE game_id = ? AND ts_server >= ? AND ts_server < ?"
                    + (envName != null ? " AND environment = ?" : "")
                    + ") a ON f.subject = a.subject WHERE a.act_bucket = f.first_bucket + INTERVAL "
                    + period.intValue() + " " + intervalUnit + " GROUP BY first_bucket",
                args.toArray());
            for (Map<String, Object> row : returnedRows) {
                Map<String, Object> b = byBucket.get(String.valueOf(row.get("first_bucket")));
                if (b == null) {
                    continue;
                }
                long size = ((Number) b.get("size")).longValue();
                long returned = ((Number) row.getOrDefault("returned", 0)).longValue();
                Map<String, Object> stat = new LinkedHashMap<>();
                stat.put("count", returned);
                stat.put("rate", size > 0 ? (double) returned / size : 0.0);
                ((Map<String, Object>) b.get("retention")).put(String.valueOf(period), stat);
            }
        }

        long cohortCount = buckets.stream().mapToLong(b -> ((Number) b.get("size")).longValue()).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cohortId", cohort.id);
        result.put("cohortName", cohort.name);
        result.put("cohortType", cohort.cohortType.name());
        result.put("analysisType", "retention");
        result.put("timeUnit", unit);
        result.put("window", Map.of("start", cohort.startDate.toString(), "end", cohort.endDate.toString()));
        result.put("environment", envName);
        result.put("periods", periods);
        result.put("cohortCount", cohortCount);
        result.put("buckets", buckets);
        result.put("calculatedAt", LocalDateTime.now().toString());
        return result;
    }

    /** 留存周期解析：getRetentionPeriods 的元素逐一 parseInt 白名单（1..3650），坏元素直接报错而非静默回落。 */
    private List<Integer> parseRetentionPeriods(CohortEntity cohort) {
        List<Integer> periods = new ArrayList<>();
        for (Object raw : cohort.getRetentionPeriods()) {
            int period;
            try {
                period = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid retention period '" + raw + "': must be an integer");
            }
            if (period < 1 || period > MAX_RETENTION_PERIOD) {
                throw new IllegalArgumentException("Retention period " + period + " out of range [1, " + MAX_RETENTION_PERIOD + "]");
            }
            periods.add(period);
        }
        return periods;
    }

    private String buildResultSummary(Map<String, Object> resultData) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "cohortCount", resultData.get("cohortCount"),
                "buckets", ((List<?>) resultData.get("buckets")).size(),
                "periods", resultData.get("periods")
            ));
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 搜索同期群
     */
    @Transactional(readOnly = true)
    public List<CohortEntity> searchCohorts(String gameId, String query) {
        return cohortRepo.search(gameId, query);
    }

    /**
     * 获取最近的同期群
     */
    @Transactional(readOnly = true)
    public List<CohortEntity> getRecentCohorts(String gameId) {
        return cohortRepo.findRecent(gameId).stream()
            .limit(10)
            .collect(Collectors.toList());
    }
}
