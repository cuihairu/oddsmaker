package io.oddsmaker.control.experiment;

import io.oddsmaker.control.service.ClickHouseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实验指标聚合器：从 ClickHouse 聚合当天窗口的每变体指标，幂等回填 experiment_metric_snapshots，
 * 供 /api/experiments/{id}/results 统计检验消费。
 *
 * 指标（按天窗口，windowStart=UTC 0 点 epoch 秒）：
 * - exposure_users：experiment_exposure 事件的去重用户数（SRM 样本量基础）；
 * - events_count / revenue：业务事件经 experiments map 归因到变体的事件数与收入
 *   （接入方在业务事件里携带 experiments: {expId: variant} 即可被归因）。
 *
 * 幂等：同 (experiment, metric, variant, window) 覆盖更新。
 */
@Component
public class ExperimentMetricsAggregator {

    private static final Logger logger = LoggerFactory.getLogger(ExperimentMetricsAggregator.class);

    private final ExperimentRepo experimentRepo;
    private final ExperimentMetricSnapshotRepo snapshotRepo;
    private final ClickHouseClient clickHouse;

    public ExperimentMetricsAggregator(ExperimentRepo experimentRepo,
                                       ExperimentMetricSnapshotRepo snapshotRepo,
                                       ClickHouseClient clickHouse) {
        this.experimentRepo = experimentRepo;
        this.snapshotRepo = snapshotRepo;
        this.clickHouse = clickHouse;
    }

    /** 定时聚合全部 running 实验的当天窗口（每 10 分钟，幂等覆盖）。 */
    @Scheduled(cron = "3 */10 * * * ?")
    public void aggregateRunning() {
        if (!clickHouse.isAvailable()) {
            return;
        }
        List<ExperimentEntity> running = experimentRepo.findByStatus("running");
        int snapshots = 0;
        for (ExperimentEntity e : running) {
            try {
                snapshots += aggregate(e.id);
            } catch (Exception ex) {
                logger.warn("aggregate experiment {} failed: {}", e.id, ex.getMessage());
            }
        }
        if (!running.isEmpty()) {
            logger.info("experiment metrics aggregated: {} experiments, {} snapshots", running.size(), snapshots);
        }
    }

    /** 聚合单实验当天窗口，返回写入的快照数。 */
    public int aggregate(String experimentId) {
        // experimentId 拼入 CH SQL（数组下标/mapContains 不走占位符），只放行安全字符
        if (experimentId == null || !experimentId.matches("[A-Za-z0-9_\\-]+")) {
            throw new IllegalArgumentException("invalid experimentId: " + experimentId);
        }
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        long windowStart = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        int written = 0;

        // 曝光用户数（exposure 事件的 props 携带 exp/variant）
        List<Map<String, Object>> exposure = clickHouse.query(
            "SELECT JSONExtractString(props_json, 'variant') AS variant, uniqExact(user_id) AS users " +
            "FROM default.events " +
            "WHERE event_name = 'experiment_exposure' AND event_date = ? " +
            "  AND JSONExtractString(props_json, 'exp') = ? " +
            "  AND JSONExtractString(props_json, 'variant') != '' " +
            "GROUP BY variant",
            day, experimentId);
        for (Map<String, Object> row : exposure) {
            String variant = str(row.get("variant"));
            if (variant.isEmpty()) {
                continue;
            }
            written += upsert(experimentId, "exposure_users", variant,
                windowStart, num(row.get("users")), 0.0, 0.0);
        }

        // 业务指标（事件携带 experiments map 归因）：事件数 + 收入
        List<Map<String, Object>> attributed = clickHouse.query(
            "SELECT experiments['" + experimentId + "'] AS variant, count() AS cnt, " +
            "  sum(revenue_amount) AS rev, sum(revenue_amount * revenue_amount) AS rev_squares " +
            "FROM default.events " +
            "WHERE event_date = ? AND mapContains(experiments, '" + experimentId + "') " +
            "GROUP BY variant",
            day);
        for (Map<String, Object> row : attributed) {
            String variant = str(row.get("variant"));
            if (variant.isEmpty()) {
                continue;
            }
            written += upsert(experimentId, "events_count", variant,
                windowStart, num(row.get("cnt")), 0.0, 0.0);
            written += upsert(experimentId, "revenue", variant,
                windowStart, num(row.get("cnt")), dbl(row.get("rev")), dbl(row.get("rev_squares")));
        }
        return written;
    }

    /** 同 (experiment, metric, variant, window) 幂等覆盖。 */
    private int upsert(String experimentId, String metric, String variant,
                       long windowStart, long count, double sum, double sumSquares) {
        ExperimentMetricSnapshotEntity entity = snapshotRepo
            .findByExperimentIdAndMetricNameAndVariantAndWindowStart(experimentId, metric, variant, windowStart)
            .orElseGet(ExperimentMetricSnapshotEntity::new);
        if (entity.id == null) {
            entity.id = "ems_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            entity.experimentId = experimentId;
            entity.metricName = metric;
            entity.variant = variant;
            entity.windowStart = windowStart;
        }
        entity.count = count;
        entity.sum = sum;
        entity.sumSquares = sumSquares;
        snapshotRepo.save(entity);
        return 1;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double dbl(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
