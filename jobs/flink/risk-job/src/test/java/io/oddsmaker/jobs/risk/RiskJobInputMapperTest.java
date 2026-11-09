package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.RawEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static io.oddsmaker.jobs.risk.RiskJobTestSupport.*;

/**
 * RiskJob 测试（LAY-011 拆分）：RawEvent → RiskInput 映射全字段、必填缺失丢弃、receipt/时间戳回退、inputMapper 收集与丢弃。
 */
class RiskJobInputMapperTest {

    @Test
    @DisplayName("toRiskInput：全字段映射 + ts_server 优先 + receipt_hash 优先 + ad_reward 判定")
    void toRiskInputMapsAllFields() {
        RawEvent full = fullEvent();
        RiskJob.RiskInput in = RiskJob.toRiskInput(full);
        assertEquals("g", in.gameId);
        assertEquals("prod", in.environment);
        assertEquals("e1", in.eventId);
        assertEquals("ad_reward_claim", in.eventName);
        assertEquals("economy", in.eventType);
        assertEquals("u1", in.userId);
        assertEquals("d1", in.deviceId);
        assertEquals("1.2.3.4", in.clientIp);
        assertEquals(new Timestamp(2_000L), in.ts);
        assertEquals(new BigDecimal("3.5"), in.amount);
        assertEquals("sink", in.flowType);
        assertEquals("rh1", in.receiptKey);
        assertEquals(new BigDecimal("9.99"), in.revenueAmount);
        assertTrue(in.adReward);
    }

    @Test
    @DisplayName("toRiskInput：必填字段缺失返回 null（四种各验）")
    void toRiskInputDropsOnMissingRequired() {
        assertNull(RiskJob.toRiskInput(new RawEvent()));

        RawEvent noGame = fullEvent();
        noGame.game_id = null;
        assertNull(RiskJob.toRiskInput(noGame));

        RawEvent noEnv = fullEvent();
        noEnv.environment = null;
        assertNull(RiskJob.toRiskInput(noEnv));

        RawEvent noEventId = fullEvent();
        noEventId.event_id = null;
        assertNull(RiskJob.toRiskInput(noEventId));

        RawEvent noDevice = fullEvent();
        noDevice.device_id = null;
        assertNull(RiskJob.toRiskInput(noDevice));
    }

    @Test
    @DisplayName("toRiskInput：ts 缺省回退 now；receiptKey 回退 order_id；ad_reward 按事件名")
    void toRiskInputFallbacks() {
        RawEvent tsMissing = fullEvent();
        tsMissing.ts_server = null;
        long before = System.currentTimeMillis();
        RiskJob.RiskInput noTs = RiskJob.toRiskInput(tsMissing);
        assertTrue(noTs.ts.getTime() >= before && noTs.ts.getTime() <= System.currentTimeMillis());

        RawEvent receiptFallback = fullEvent();
        receiptFallback.receipt_hash = null;
        receiptFallback.order_id = "o9";
        assertEquals("o9", RiskJob.toRiskInput(receiptFallback).receiptKey);
        RawEvent noReceipt = fullEvent();
        noReceipt.receipt_hash = null;
        assertNull(RiskJob.toRiskInput(noReceipt).receiptKey);

        RawEvent byFormatCase = fullEvent();
        byFormatCase.ad_format = "REWARDED";   // 大小写不敏感
        byFormatCase.event_name = "n1";
        assertTrue(RiskJob.toRiskInput(byFormatCase).adReward);

        RawEvent byName = fullEvent();
        byName.ad_format = null;
        byName.event_name = "watch_ad_reward_video";
        assertTrue(RiskJob.toRiskInput(byName).adReward);

        RawEvent neither = fullEvent();
        neither.ad_format = null;
        neither.event_name = "level_up";
        assertFalse(RiskJob.toRiskInput(neither).adReward);
    }

    @Test
    @DisplayName("inputMapper：合法事件收集，非法事件丢弃")
    void inputMapperCollectsAndDrops() throws Exception {
        List<RiskJob.RiskInput> out = new ArrayList<>();
        RiskJob.inputMapper().flatMap(fullEvent(), sinkTo(out));
        assertEquals(1, out.size());
        RiskJob.inputMapper().flatMap(new RawEvent(), sinkTo(out));   // 必填缺失丢弃
        assertEquals(1, out.size());
    }

}
