package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DataQualityMetricsEntity;
import io.oddsmaker.control.jpa.DataQualityMetricsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B10 数据质量指标服务（设计定稿 07-b10 §4）。
 *
 * 摄取：网关每 60s 快照 POST /internal/data-quality，携带自窗口起点以来的累计值，
 * 按唯一键 (game_id, environment, window_start, window_sec) 整行覆盖——乱序与重复投递无害。
 * 落表时校验恒等式 received = accepted + Σrejected + sampled_out（accepted 含 duplicates_gateway），
 * 破式记日志不拒收——计数路径漏分支时恒等式先于任何阈值告警发现问题。
 *
 * 查询：健康页两个只读面——窗口序列（五率逐窗）与汇总（时段聚合 + 拒绝原因 Top）。
 * 率一律读时计算：分子分母同行存储，预落率必然产生与计数不一致的第二份真相。
 */
@Service
public class DataQualityService {

    private static final Logger logger = LoggerFactory.getLogger(DataQualityService.class);

    /** 拒绝原因列名 → 展示名（列集合由网关枚举封闭，见 BatchController 拒绝路径）。 */
    private static final String[] REJECT_FIELDS = {
        "rejectedSchema", "rejectedUnknownEvent", "rejectedInvalidTimestamp", "rejectedPiiBlocked",
        "rejectedPayloadTooLarge", "rejectedTrustEscalation", "rejectedBlocked",
        "rejectedScopeMismatch", "rejectedKafkaError"
    };

    @Autowired
    private DataQualityMetricsRepo repo;

    @Transactional
    public DataQualityMetricsEntity ingest(DataQualityMetricsEntity incoming) {
        if (incoming.gameId == null || incoming.gameId.isBlank()
            || incoming.environment == null || incoming.environment.isBlank()
            || incoming.windowStart == null) {
            throw new IllegalArgumentException("gameId, environment and windowStart are required");
        }
        if (incoming.windowSec <= 0) {
            incoming.windowSec = 300;
        }
        long rejectedTotal = rejectTotal(incoming);
        long identity = incoming.received - (incoming.accepted + rejectedTotal + incoming.sampledOut);
        if (identity != 0) {
            logger.warn("[data-quality] identity breach for {} @ {}: received={} accepted={} rejected={} sampled={}",
                incoming.gameId, incoming.windowStart, incoming.received, incoming.accepted,
                rejectedTotal, incoming.sampledOut);
        }
        Optional<DataQualityMetricsEntity> existing = repo.findByGameIdAndEnvironmentAndWindowStartAndWindowSec(
            incoming.gameId, incoming.environment, incoming.windowStart, incoming.windowSec);
        DataQualityMetricsEntity entity = existing.orElseGet(DataQualityMetricsEntity::new);
        entity.gameId = incoming.gameId;
        entity.environment = incoming.environment;
        entity.windowStart = incoming.windowStart;
        entity.windowSec = incoming.windowSec;
        entity.received = incoming.received;
        entity.accepted = incoming.accepted;
        entity.sampledOut = incoming.sampledOut;
        entity.rejectedSchema = incoming.rejectedSchema;
        entity.rejectedUnknownEvent = incoming.rejectedUnknownEvent;
        entity.rejectedInvalidTimestamp = incoming.rejectedInvalidTimestamp;
        entity.rejectedPiiBlocked = incoming.rejectedPiiBlocked;
        entity.rejectedPayloadTooLarge = incoming.rejectedPayloadTooLarge;
        entity.rejectedTrustEscalation = incoming.rejectedTrustEscalation;
        entity.rejectedBlocked = incoming.rejectedBlocked;
        entity.rejectedScopeMismatch = incoming.rejectedScopeMismatch;
        entity.rejectedKafkaError = incoming.rejectedKafkaError;
        entity.duplicatesGateway = incoming.duplicatesGateway;
        entity.duplicatesEnrich = incoming.duplicatesEnrich;
        entity.late = incoming.late;
        entity.dlqOther = incoming.dlqOther;
        entity.updatedAt = LocalDateTime.now();
        return repo.save(entity);
    }

    /** 窗口序列：近 hours 小时，新窗在前；每窗带五率与恒等式余项。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> series(String gameId, String environment, int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, hours));
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataQualityMetricsEntity row : repo
            .findByGameIdAndEnvironmentAndWindowStartGreaterThanEqualOrderByWindowStartDesc(
                gameId, environment, since)) {
            out.add(withRates(row));
        }
        return out;
    }

    /** 汇总：近 hours 小时计数聚合 → 五率 + 拒绝原因 Top（非零降序）+ DLQ 计数。 */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String gameId, String environment, int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, hours));
        DataQualityMetricsEntity agg = new DataQualityMetricsEntity();
        agg.gameId = gameId;
        agg.environment = environment;
        for (DataQualityMetricsEntity row : repo
            .findByGameIdAndEnvironmentAndWindowStartGreaterThanEqualOrderByWindowStartDesc(
                gameId, environment, since)) {
            agg.received += row.received;
            agg.accepted += row.accepted;
            agg.sampledOut += row.sampledOut;
            agg.rejectedSchema += row.rejectedSchema;
            agg.rejectedUnknownEvent += row.rejectedUnknownEvent;
            agg.rejectedInvalidTimestamp += row.rejectedInvalidTimestamp;
            agg.rejectedPiiBlocked += row.rejectedPiiBlocked;
            agg.rejectedPayloadTooLarge += row.rejectedPayloadTooLarge;
            agg.rejectedTrustEscalation += row.rejectedTrustEscalation;
            agg.rejectedBlocked += row.rejectedBlocked;
            agg.rejectedScopeMismatch += row.rejectedScopeMismatch;
            agg.rejectedKafkaError += row.rejectedKafkaError;
            agg.duplicatesGateway += row.duplicatesGateway;
            agg.duplicatesEnrich += row.duplicatesEnrich;
            agg.late += row.late;
            agg.dlqOther += row.dlqOther;
        }

        Map<String, Object> out = withRates(agg);
        out.put("hours", Math.max(1, hours));
        out.put("rejectTop", rejectTop(agg));
        return out;
    }

    /** 五率 + 恒等式余项挂到计数 Map 上（0 分母产出 0.0 而非 NaN/异常）。 */
    private static Map<String, Object> withRates(DataQualityMetricsEntity row) {
        Map<String, Object> out = baseMap(row);
        long rejectedTotal = rejectTotal(row);
        out.put("rejectedTotal", rejectedTotal);
        out.put("eventValidRate", rate(row.accepted, row.received));
        out.put("dropRate", rate(rejectedTotal, row.received));
        out.put("unknownEventRate", rate(row.rejectedUnknownEvent, row.received));
        out.put("duplicateRate", rate(row.duplicatesGateway + row.duplicatesEnrich, row.received));
        out.put("lateRate", rate(row.late, row.received));
        out.put("identityDelta", row.received - (row.accepted + rejectedTotal + row.sampledOut));
        return out;
    }

    private static Map<String, Object> baseMap(DataQualityMetricsEntity row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", row.gameId);
        out.put("environment", row.environment);
        if (row.windowStart != null) {
            out.put("windowStart", row.windowStart.toString());
            out.put("windowSec", row.windowSec);
        }
        out.put("received", row.received);
        out.put("accepted", row.accepted);
        out.put("sampledOut", row.sampledOut);
        for (String field : REJECT_FIELDS) {
            out.put(field, entityField(row, field));
        }
        out.put("duplicatesGateway", row.duplicatesGateway);
        out.put("duplicatesEnrich", row.duplicatesEnrich);
        out.put("late", row.late);
        out.put("dlqOther", row.dlqOther);
        return out;
    }

    /** 非零拒绝原因降序（kafka_error 是平台故障，页面单列标记，健康页不摊进接入质量）。 */
    private static List<Map<String, Object>> rejectTop(DataQualityMetricsEntity agg) {
        List<Map<String, Object>> top = new ArrayList<>();
        for (String field : REJECT_FIELDS) {
            long count = entityField(agg, field);
            if (count > 0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("reason", field);
                item.put("count", count);
                top.add(item);
            }
        }
        top.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        return top;
    }

    private static long rejectTotal(DataQualityMetricsEntity row) {
        long total = 0;
        for (String field : REJECT_FIELDS) {
            total += entityField(row, field);
        }
        return total;
    }

    private static long entityField(DataQualityMetricsEntity row, String field) {
        return switch (field) {
            case "rejectedSchema" -> row.rejectedSchema;
            case "rejectedUnknownEvent" -> row.rejectedUnknownEvent;
            case "rejectedInvalidTimestamp" -> row.rejectedInvalidTimestamp;
            case "rejectedPiiBlocked" -> row.rejectedPiiBlocked;
            case "rejectedPayloadTooLarge" -> row.rejectedPayloadTooLarge;
            case "rejectedTrustEscalation" -> row.rejectedTrustEscalation;
            case "rejectedBlocked" -> row.rejectedBlocked;
            case "rejectedScopeMismatch" -> row.rejectedScopeMismatch;
            case "rejectedKafkaError" -> row.rejectedKafkaError;
            default -> 0;
        };
    }

    private static double rate(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }
}
