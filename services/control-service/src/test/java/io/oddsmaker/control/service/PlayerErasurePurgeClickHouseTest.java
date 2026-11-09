package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求服务测试（LAY-014 拆分）：CH 清洗——不可用降级、9 表 mutation 提交与确认、提交异常 PENDING_RETRY、确认轮询超时/周期/异常容忍、预估异常容忍。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("玩家数据删除请求服务")
class PlayerErasurePurgeClickHouseTest extends PlayerErasureServiceTestBase {

    @Test
    @DisplayName("CH 不可用降级：SKIPPED_UNCONFIGURED 且零 SQL")
    void purgeClickHouseSkipsWhenUnavailable() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of("idt1"), List.of("u1"), List.of("p1"), List.of(), List.of(), false);
        when(clickHouse.isAvailable()).thenReturn(false);

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("SKIPPED_UNCONFIGURED");
        assertThat(ch.get("confirmed")).isEqualTo(true);
        verify(clickHouse, never()).execute(any());
    }

    @Test
    @DisplayName("CH mutation：9 张表提交 + 谓词裸值 + per 标记 + 确认完成")
    void purgeClickHouseSubmitsMutations() throws Exception {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of("idt1"), List.of("u1"), List.of("p1"),
            List.of("d1", "d2"), List.of("c1"), false);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 5);
        List<String> sqlLog = new ArrayList<>();
        stubChHappyPath(sqlLog);

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_abc");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(true);
        assertThat((List<?>) ch.get("mutations")).hasSize(9);
        assertThat(sqlLog).hasSize(9);
        assertThat(sqlLog.get(0)).startsWith("ALTER TABLE events DELETE").contains("/* per:per_abc */");
        // risk_* 的 subject_id 是裸 id（两列分离存储），无 "PLAYER:" 前缀
        assertThat(sqlLog.get(3)).contains("subject_id IN ('p1','u1','d1','d2','c1')");
        assertThat(sqlLog.get(2)).contains("hasAny(device_ids, ['d1','d2'])").contains("identity_id IN ('idt1')");
        assertThat(sqlLog.get(8)).isEqualTo("ALTER TABLE predictions DELETE WHERE game_id='g1' "
            + "AND user_id IN ('u1') /* per:per_abc */");
    }

    @Test
    @DisplayName("CH 提交异常：PENDING_RETRY（整体走 PARTIAL 重试）")
    void purgeClickHouseSubmitFailure() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        when(clickHouse.isAvailable()).thenReturn(true);
        // 预估返回无 c 键的行：estimate 按 0 处理，不影响提交
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("x", 1L)));
        when(clickHouse.execute(any(ConnectionCallback.class)))
            .thenThrow(new RuntimeException("clickhouse down"));

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("PENDING_RETRY");
        assertThat(ch.get("confirmed")).isEqualTo(false);
        assertThat(ch.get("error")).isEqualTo("clickhouse down");
    }

    @Test
    @DisplayName("CH 确认轮询超时：EXECUTED 但 confirmed=false（mutation 已持久化不判失败）")
    void purgeClickHouseConfirmTimeout() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 1L)));
        // mutationTimeoutSeconds=0：轮询立即超时（confirmed=false），不真挂 300s
        when(clickHouse.query(contains("system.mutations"), any(String.class)))
            .thenReturn(List.of(Map.of("c", 1L)));

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(false);
    }

    @Test
    @DisplayName("CH 确认轮询：pending 高于基线时继续轮询，回落后确认成功")
    void purgeClickHousePollConfirmCycles() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 2);
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 1L)));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        // 前 9 次 = 基线（0），第一轮轮询 pending=1，之后回落 0
        when(clickHouse.query(contains("system.mutations"), any(String.class))).thenAnswer(inv -> {
            int i = calls.getAndIncrement();
            long c = i < 9 ? 0L : (i < 18 ? 1L : 0L);
            return List.of(Map.of("c", c));
        });
        when(clickHouse.execute(any(ConnectionCallback.class))).thenReturn(null);

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(true);
    }

    @Test
    @DisplayName("CH 轮询期查询异常：降级为 confirmed=false 不抛出（基线正常取得）")
    void purgeClickHouseToleratesMutationQueryErrors() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 5);
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 0L)));
        // 前 9 次（基线）正常返回 0，轮询期起查询失败（pendingMutations 返回 null → 无法确认）
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        when(clickHouse.query(contains("system.mutations"), any(String.class))).thenAnswer(inv -> {
            if (n.getAndIncrement() < 9) {
                return List.of(Map.of("c", 0L));
            }
            throw new RuntimeException("mutations query down");
        });

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(false);
    }

    @Test
    @DisplayName("CH 预估查询异常：estimate=-1 不影响 mutation 提交")
    void purgeClickHouseEstimateFailureIsTolerated() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 5);
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenThrow(new RuntimeException("estimate fail"));
        when(clickHouse.query(contains("system.mutations"), any(String.class)))
            .thenReturn(List.of(Map.of("c", 0L)));
        when(clickHouse.execute(any(ConnectionCallback.class))).thenReturn(null);

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mutations = (List<Map<String, Object>>) ch.get("mutations");
        assertThat(mutations.get(0).get("estimate")).isEqualTo(-1L);
    }

}
