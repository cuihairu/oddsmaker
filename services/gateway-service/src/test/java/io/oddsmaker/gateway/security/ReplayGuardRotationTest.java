package io.oddsmaker.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReplayGuard 分代缓存：TTL 轮换（双检锁两分支）、容量保护、时间戳信差与缺省放行。
 */
@DisplayName("ReplayGuard 分代与防护测试")
class ReplayGuardRotationTest {

    @Test
    @DisplayName("签名与 event_id 各自独立去重")
    void consumesIndependently() {
        ReplayGuard guard = new ReplayGuard(600_000, 200_000, 86_400_000);
        assertTrue(guard.consumeSignature("sig-1"));
        assertFalse(guard.consumeSignature("sig-1"));
        assertTrue(guard.consumeEventId("evt-1"));
        assertFalse(guard.consumeEventId("evt-1"));
    }

    @Test
    @DisplayName("容量打满后本代放行新 key（防 OOM 降级），已见 key 仍拦截")
    void capacityFullDegradesGracefully() {
        ReplayGuard guard = new ReplayGuard(600_000, 1, 86_400_000);
        assertTrue(guard.consumeSignature("sig-1"));
        // 本代已满：新 key 不再登记、返回 false（调用方按已见处理），防缓存无限膨胀
        assertFalse(guard.consumeSignature("sig-2"));
        assertFalse(guard.consumeSignature("sig-3"));
        // 已见 key 同样 false
        assertFalse(guard.consumeSignature("sig-1"));
        // event_ids 是独立的一代：不受签名代容量影响，但自身容量同样为 1
        assertTrue(guard.consumeEventId("evt-1"));
        assertFalse(guard.consumeEventId("evt-2"));
    }

    @Test
    @DisplayName("TTL 到期后整体轮换：同 key 重新可写入（signatures 与 eventIds 两分支）")
    void rotatesGenerationsAfterTtl() throws InterruptedException {
        ReplayGuard guard = new ReplayGuard(1, 100, 86_400_000);
        assertTrue(guard.consumeSignature("sig-1"));
        assertTrue(guard.consumeEventId("evt-1"));
        Thread.sleep(5); // 过期
        // 轮换后新代：同 key 再次视为新签名/新事件
        assertTrue(guard.consumeSignature("sig-1"));
        assertTrue(guard.consumeEventId("evt-1"));
    }

    @Test
    @DisplayName("时间戳信差：null 放行（schema 兜底），偏差内放行、超差拒绝")
    void timestampPlausibility() {
        ReplayGuard guard = new ReplayGuard(600_000, 100, 1_000);
        long now = System.currentTimeMillis();
        assertTrue(guard.isTimestampPlausible(null, now));
        assertTrue(guard.isTimestampPlausible(now - 1_000, now));
        assertTrue(guard.isTimestampPlausible(now + 1_000, now));
        assertFalse(guard.isTimestampPlausible(now - 2_000, now));    }
}
