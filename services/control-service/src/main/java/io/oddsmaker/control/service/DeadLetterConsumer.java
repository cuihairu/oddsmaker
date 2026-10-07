package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.service.DataQualityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * 死信队列消费者（B10，设计定稿 07-b10 §4.3）——补齐 oddsmaker.deadletter 有生产无消费的缺口。
 *
 * 口径（防双计是本消费者的核心纪律）：
 * <ul>
 *   <li>reason=duplicate：只可能来自 enrich 侧 DedupFunction（网关幂等吸收静默不进 DLQ）
 *       → 计 duplicates_enrich；</li>
 *   <li>网关 9 个拒绝 reason：网关边缘已计入对应 rejected_* 列（同一条消息经 reject() 双写），
 *       此处必须跳过，再计即双计；</li>
 *   <li>其余未知 reason（未来的第三方 DLQ 生产者）→ 计 dlq_other 兜底。</li>
 * </ul>
 * 窗口与作用域取自 raw 内事件字段（game_id/environment/ts_server），缺失落 unknown 桶当前窗口——
 * 观测面不丢计数。
 */
@Component
public class DeadLetterConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterConsumer.class);

    /** 网关拒绝 reason 全集（BatchController.rejectCounter 同源）；这些消息的计数已在网关边缘落地。 */
    static final Set<String> GATEWAY_REASONS = Set.of(
        "invalid_schema", "unknown_event", "invalid_timestamp", "pii_blocked",
        "payload_too_large", "trust_escalation", "blocked",
        "api_key_scope_mismatch", "kafka_error");

    static final String REASON_DUPLICATE = "duplicate";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataQualityService dataQualityService;

    @KafkaListener(topics = "${oddsmaker.kafka.dlq-topic:oddsmaker.deadletter}")
    public void onDeadLetter(String message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (Exception e) {
            logger.warn("[dlq] undecodable dead-letter message skipped: {}", e.getMessage());
            return;
        }
        String reason = node.path("reason").asText(null);
        if (reason == null || reason.isBlank()) {
            logger.warn("[dlq] dead-letter message without reason skipped");
            return;
        }
        if (GATEWAY_REASONS.contains(reason)) {
            // 网关拒绝：计数已在 BatchController 埋点落地，此处双计会污染恒等式
            return;
        }
        JsonNode raw = node.path("raw");
        String gameId = raw.path("game_id").asText(null);
        String environment = raw.path("environment").asText(null);
        long windowEpoch = currentWindowEpoch();
        if (raw.hasNonNull("ts_server")) {
            windowEpoch = raw.path("ts_server").asLong(System.currentTimeMillis()) / 1000L / 300 * 300;
        }
        LocalDateTime windowStart = LocalDateTime.ofInstant(Instant.ofEpochSecond(windowEpoch), ZoneOffset.UTC);
        long duplicatesDelta = REASON_DUPLICATE.equals(reason) ? 1 : 0;
        long otherDelta = duplicatesDelta == 0 ? 1 : 0;
        try {
            dataQualityService.mergeEnrichCounts(
                gameId == null || gameId.isBlank() ? "unknown" : gameId,
                environment == null || environment.isBlank() ? "unknown" : environment,
                windowStart, duplicatesDelta, otherDelta);
        } catch (Exception e) {
            // 落库失败不阻塞消费位（Kafka 重投递为 at-least-once，下一轮以累计口径修正）
            logger.error("[dlq] failed to merge dead-letter counts (reason={}): {}", reason, e.getMessage());
        }
    }

    private static long currentWindowEpoch() {
        return System.currentTimeMillis() / 1000L / 300 * 300;
    }
}
