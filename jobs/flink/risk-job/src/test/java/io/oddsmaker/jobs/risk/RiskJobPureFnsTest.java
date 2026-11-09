package io.oddsmaker.jobs.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static io.oddsmaker.jobs.risk.RiskJobTestSupport.*;

/**
 * RiskJob 测试（LAY-011 拆分）：str/nz/parseAmount、firstNonBlank、adReward 标识、RiskInput/RiskHit POJO、实例化与 RuleSource 构造。
 */
class RiskJobPureFnsTest {

    @Test
    void strNzParseAmount() {
        assertNull(RiskJob.str(null));
        assertEquals("test", RiskJob.str("test"));
        assertEquals("", RiskJob.nz(null));
        assertEquals("test", RiskJob.nz("test"));

        assertNull(RiskJob.parseAmount(null));
        assertEquals(new BigDecimal("123.45"), RiskJob.parseAmount(123.45));
        assertEquals(new BigDecimal("123.45"), RiskJob.parseAmount("123.45"));
        assertNull(RiskJob.parseAmount("not_a_number"));
    }

    @Test
    void firstNonBlankPicksFirstPresent() {
        assertEquals("rh", RiskJob.firstNonBlank(null, " ", "rh", "order1"));
        assertEquals("order1", RiskJob.firstNonBlank(null, "", "order1"));
        assertNull(RiskJob.firstNonBlank(null, "", "  "));
    }

    @Test
    void adRewardFlagIdentified() {
        RiskJob.RiskInput rewarded = new RiskJob.RiskInput();
        rewarded.adReward = true;
        assertTrue(RiskJob.isAdReward(rewarded));

        RiskJob.RiskInput normal = new RiskJob.RiskInput();
        assertFalse(RiskJob.isAdReward(normal));
    }

    @Test
    void riskInputPojo() {
        RiskJob.RiskInput input = new RiskJob.RiskInput();
        input.gameId = "game_demo";
        input.environment = "prod";
        input.eventId = "event_123";
        input.eventName = "purchase";
        input.userId = "user_456";
        input.deviceId = "device_789";
        input.amount = new BigDecimal("99.99");

        assertEquals("game_demo", input.gameId);
        assertEquals("prod", input.environment);
        assertEquals("event_123", input.eventId);
        assertEquals("purchase", input.eventName);
        assertEquals("user_456", input.userId);
        assertEquals("device_789", input.deviceId);
        assertEquals(new BigDecimal("99.99"), input.amount);
    }

    @Test
    void riskHitPojo() {
        RiskJob.RiskHit hit = new RiskJob.RiskHit();
        hit.gameId = "game_demo";
        hit.environment = "prod";
        hit.subjectId = "user_456";
        hit.riskType = "amount_threshold";
        hit.score = 0.95f;
        hit.reason = "Amount exceeds threshold";

        assertEquals("game_demo", hit.gameId);
        assertEquals("prod", hit.environment);
        assertEquals("user_456", hit.subjectId);
        assertEquals("amount_threshold", hit.riskType);
        assertEquals(0.95f, hit.score, 0.0f);
        assertEquals("Amount exceeds threshold", hit.reason);
    }

    @Test
    void instances() {
        assertNotNull(new RiskJob());
        assertNotNull(new RiskJob.RiskInput());
        assertNotNull(new RiskJob.RiskHit());
        assertNotNull(deadRules());   // RuleSource 构造器
    }

}
