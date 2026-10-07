package io.oddsmaker.gateway.metrics;

import io.oddsmaker.gateway.metrics.DataQualityCounters.Counter;
import io.oddsmaker.gateway.metrics.DataQualityCounters.WindowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B10 数据质量快照上报器（设计定稿 07-b10 §4.2，BlockListClient 通道先例）。
 *
 * 每 60s 把当前/上一窗口的累计计数逐 key POST /internal/data-quality（x-internal-token，
 * control 侧 AdminTokenFilter 同款鉴权）。fire-and-forget：失败仅 debug log，
 * 计数不清窗，下一轮以更大累计值重报，最终一致。
 * 未配 internal-token 时静默跳过——空令牌请求只会换 401，白打。
 */
@Component
public class DataQualityReporter {

    private static final Logger log = LoggerFactory.getLogger(DataQualityReporter.class);

    private final DataQualityCounters counters;
    private final WebClient client;
    private final String internalToken;
    private final boolean enabled;

    @Autowired
    public DataQualityReporter(Environment env, DataQualityCounters counters) {
        this.counters = counters;
        String controlUrl = env.getProperty("oddsmaker.control.url", "http://localhost:8085");
        this.internalToken = env.getProperty("oddsmaker.control.internal-token", "");
        this.enabled = env.getProperty("oddsmaker.dataquality.reporting-enabled", boolean.class, true);
        this.client = WebClient.builder().baseUrl(controlUrl).build();
    }

    @Scheduled(fixedDelay = 60_000)
    public void report() {
        if (!enabled || internalToken == null || internalToken.isBlank()) {
            return;
        }
        for (WindowSnapshot snapshot : counters.snapshotAndEvict()) {
            client.post()
                .uri("/internal/data-quality")
                .header("x-internal-token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildPayload(snapshot))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    log.debug("data-quality report failed for {} @ {}: {}",
                        snapshot.gameId, snapshot.windowEpochSec, e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
        }
    }

    /**
     * 载荷键名与 control 侧 InternalDataQualityController.Snapshot 字段精确一致
     * （camelCase 字面量，不依赖任何全局命名策略）。windowStart 为 UTC 窗口起点 ISO 串。
     */
    static Map<String, Object> buildPayload(WindowSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", s.gameId);
        m.put("environment", s.environment);
        m.put("windowStart", LocalDateTime.ofInstant(Instant.ofEpochSecond(s.windowEpochSec), ZoneOffset.UTC).toString());
        m.put("windowSec", DataQualityCounters.WINDOW_SEC);
        m.put("received", s.count(Counter.RECEIVED));
        m.put("accepted", s.count(Counter.ACCEPTED));
        m.put("sampledOut", s.count(Counter.SAMPLED_OUT));
        m.put("rejectedSchema", s.count(Counter.REJECTED_SCHEMA));
        m.put("rejectedUnknownEvent", s.count(Counter.REJECTED_UNKNOWN_EVENT));
        m.put("rejectedInvalidTimestamp", s.count(Counter.REJECTED_INVALID_TIMESTAMP));
        m.put("rejectedPiiBlocked", s.count(Counter.REJECTED_PII_BLOCKED));
        m.put("rejectedPayloadTooLarge", s.count(Counter.REJECTED_PAYLOAD_TOO_LARGE));
        m.put("rejectedTrustEscalation", s.count(Counter.REJECTED_TRUST_ESCALATION));
        m.put("rejectedBlocked", s.count(Counter.REJECTED_BLOCKED));
        m.put("rejectedScopeMismatch", s.count(Counter.REJECTED_SCOPE_MISMATCH));
        m.put("rejectedKafkaError", s.count(Counter.REJECTED_KAFKA_ERROR));
        m.put("duplicatesGateway", s.count(Counter.DUPLICATES));
        m.put("duplicatesEnrich", 0L);
        m.put("late", s.count(Counter.LATE));
        m.put("dlqOther", 0L);
        return m;
    }
}
