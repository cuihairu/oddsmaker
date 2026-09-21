package io.oddsmaker.jobs.enrich;

import nl.basjes.parse.useragent.UserAgentAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * INSTR 收口：buildPipeline 内联 lambda（keyBy/map）的方法体——pipeline DSL 不经
 * Flink 执行不可达，反射直调合成方法；以及 Enrichers.ensureInit 的 uaa 构建失败侧。
 */
@DisplayName("pipeline lambda 直调与 uaa 初始化失败侧")
class EventsEnrichJobLambdaEdgesTest {

    /** 按前缀+参数类型定位 lambda 合成方法（hash 中缀随编译可能漂移，不做精确匹配）。 */
    private static Method synthetic(String prefix, Class<?>... paramTypes) {
        return Arrays.stream(EventsEnrichJob.class.getDeclaredMethods())
            .filter(m -> m.getName().startsWith(prefix) && m.getParameterCount() == paramTypes.length
                && Arrays.equals(m.getParameterTypes(), paramTypes))
            .findFirst()
            .orElseThrow(() -> new AssertionError("未找到合成方法 " + prefix));
    }

    @Test
    @DisplayName("keyBy lambda：返回 event_id（合成方法体直调）")
    void keyByLambdaBody() throws Exception {
        Method keyBy = synthetic("lambda$buildPipeline$", RawEvent.class);
        keyBy.setAccessible(true);
        RawEvent e = new RawEvent();
        e.event_id = "evt-42";
        assertEquals("evt-42", keyBy.invoke(null, e));
    }

    @Test
    @DisplayName("map lambda：等价 toRow(record, enrichers)（合成方法体直调）")
    void mapLambdaBody() throws Exception {
        Method map = synthetic("lambda$buildPipeline$",
            EventsEnrichJob.Enrichers.class, RawEvent.class);
        map.setAccessible(true);
        EventsEnrichJob.Enrichers enrichers = EventsEnrichJob.Enrichers.create("");
        RawEvent e = new RawEvent();
        e.event_id = "evt-7";
        e.game_id = "g1";
        e.event_type = "login";
        e.event_name = "Login";
        Object row = map.invoke(null, enrichers, e);
        java.lang.reflect.Field id = row.getClass().getDeclaredField("event_id");
        id.setAccessible(true);
        assertEquals("evt-7", id.get(row));
    }

    @Test
    @DisplayName("ensureInit：yauaa 构建抛异常 → uaa 置 null，UA 解析安静返回 null")
    void uaaBuildFailureFallsBackToNull() {
        try (var mocked = Mockito.mockStatic(UserAgentAnalyzer.class, Mockito.RETURNS_DEEP_STUBS)) {
            mocked.when(() -> UserAgentAnalyzer.newBuilder()
                    .hideMatcherLoadStats()
                    .withCache(anyInt())
                    .build())
                .thenThrow(new RuntimeException("yauaa init boom"));

            EventsEnrichJob.Enrichers enrichers = EventsEnrichJob.Enrichers.create("");
            // uaa == null 早退：不抛、返回 null
            assertNull(enrichers.uaFamily("Mozilla/5.0"));
            assertNull(enrichers.osFamily("Mozilla/5.0"));
            assertNull(enrichers.deviceClass("Mozilla/5.0"));
        }
    }
}
