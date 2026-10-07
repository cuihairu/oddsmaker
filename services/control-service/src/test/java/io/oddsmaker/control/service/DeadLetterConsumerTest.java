package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static java.time.LocalDateTime.ofInstant;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DLQ 消费者计数口径测试（防双计是核心纪律）：
 * duplicate → duplicates_enrich；网关 9 reason 跳过；未知 reason → dlq_other；
 * raw 缺字段落 unknown 桶；窗口由 raw.ts_server 定。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("死信队列消费者计数口径测试")
class DeadLetterConsumerTest {

    @Mock
    private DataQualityService dataQualityService;

    private DeadLetterConsumer consumer;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new DeadLetterConsumer();
        ReflectionTestUtils.setField(consumer, "objectMapper", om);
        ReflectionTestUtils.setField(consumer, "dataQualityService", dataQualityService);
    }

    private static String msg(String reason, String rawJson) {
        return "{\"event_id\":\"e1\",\"reason\":\"" + reason + "\",\"raw\":" + rawJson + "}";
    }

    @Test
    @DisplayName("reason=duplicate → duplicates_enrich +1，窗口取 raw.ts_server 所在 5 分钟桶")
    void duplicateCountsToEnrichColumn() {
        long ts = 1_730_000_230_000L;   // 落 1_729_999_800 窗口（230s 处）
        consumer.onDeadLetter(msg("duplicate",
            "{\"event_id\":\"e1\",\"game_id\":\"g1\",\"environment\":\"prod\",\"ts_server\":" + ts + "}"));
        verify(dataQualityService).mergeEnrichCounts(
            eq("g1"), eq("prod"),
            eq(ofInstant(Instant.ofEpochSecond(1_730_000_230_000L / 1000 / 300 * 300), ZoneOffset.UTC)),
            eq(1L), eq(0L));
    }

    @Test
    @DisplayName("网关 9 reason 跳过（网关边缘已计数，再计即双计）")
    void gatewayReasonsSkipped() {
        for (String reason : DeadLetterConsumer.GATEWAY_REASONS) {
            consumer.onDeadLetter(msg(reason, "{\"game_id\":\"g1\",\"environment\":\"prod\"}"));
        }
        verify(dataQualityService, never()).mergeEnrichCounts(any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("未知 reason → dlq_other 兜底；raw 缺作用域落 unknown 桶当前窗口")
    void unknownReasonToDlqOther() {
        consumer.onDeadLetter(msg("future_producer_reason", "{}"));
        verify(dataQualityService).mergeEnrichCounts(
            eq("unknown"), eq("unknown"), any(), eq(0L), eq(1L));
    }

    @Test
    @DisplayName("坏 JSON 与缺 reason 静默跳过，不落计数")
    void malformedSkipped() {
        consumer.onDeadLetter("not-json{");
        consumer.onDeadLetter("{\"event_id\":\"e1\",\"raw\":{}}");
        verify(dataQualityService, never()).mergeEnrichCounts(any(), any(), any(), anyLong(), anyLong());
    }
}
