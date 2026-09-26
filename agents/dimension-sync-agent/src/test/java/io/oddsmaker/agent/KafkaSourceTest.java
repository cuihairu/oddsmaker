package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kafka 源（假端口离线测试）：位点推进、drain、坏消息跳过、断点续传；k: 位点编解码。 */
class KafkaSourceTest {

    /** 内存假端口：按轮返回预置批次，记录 assign 收到的起点。 */
    private static final class FakePort implements KafkaConsumerPort {
        Map<Integer, Long> assigned;
        List<List<PortRecord>> batches = new ArrayList<>();
        int round = 0;
        long timeoutSeen = -1;

        @Override
        public void assign(Map<Integer, Long> startOffsets) {
            this.assigned = new HashMap<>(startOffsets);
        }

        @Override
        public List<PortRecord> poll(long timeoutMs) {
            timeoutSeen = timeoutMs;
            return round < batches.size() ? batches.get(round++) : List.of();
        }

        @Override
        public void close() {
        }
    }

    private static AgentConfig cfg() {
        AgentConfig cfg = new AgentConfig();
        cfg.dimType = "item";
        cfg.kafkaBootstrap = "b:9092";
        cfg.kafkaTopic = "dims";
        cfg.kafkaPollTimeoutMs = 1234;
        return cfg;
    }

    private static KafkaConsumerPort.PortRecord rec(int partition, long offset, String json) {
        return new KafkaConsumerPort.PortRecord(partition, offset, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("位点编解码：k:0=42;1=57 往返；残缺/非 k: 前缀回退空表")
    void offsetsCodec() {
        SortedMap<Integer, Long> m = new TreeMap<>(Map.of(0, 42L, 1, 57L));
        String encoded = KafkaOffsets.encode(m);
        assertEquals("k:0=42;1=57", encoded);
        assertEquals(m, KafkaOffsets.decode(encoded));

        assertTrue(KafkaOffsets.decode(null).isEmpty());
        assertTrue(KafkaOffsets.decode("n:123").isEmpty());
        assertTrue(KafkaOffsets.decode("k:").isEmpty());
        assertTrue(KafkaOffsets.decode("k:bad").isEmpty());
        assertTrue(KafkaOffsets.decode("k:0=oops").isEmpty());
        assertNull(KafkaOffsets.encode(new TreeMap<>()));   // 无位点 → null（不写 cursor）
    }

    @Test
    @DisplayName("首轮（无断点）：空起点传端口 → 消费两分区记录，next cursor 记 next-offset")
    void firstPollConsumesAndAdvancesOffsets() throws Exception {
        FakePort port = new FakePort();
        port.batches.add(List.of(
                rec(0, 10, "{\"resource_id\":\"sword_01\",\"version_ts\":1735689605000}"),
                rec(1, 5, "{\"resource_id\":\"shield_01\",\"version_ts\":1000}")));
        KafkaSource s = new KafkaSource(cfg(), port);

        DimensionSource.PollResult r = s.poll(new Checkpoint());

        assertTrue(port.assigned.isEmpty());   // 无断点 → 空起点（adapter 按 earliest 指派）
        assertEquals(2, r.changes().size());
        assertEquals("k:0=11;1=6", r.next().cursor);   // offset + 1
        assertEquals(1735689605000L, r.next().lastEventTs);
        assertEquals(1234L, port.timeoutSeen);
        assertEquals("kafka", s.type());
        assertEquals("kafka:b:9092/dims", s.name());
    }

    @Test
    @DisplayName("断点续传：cursor 里的起点传给 assign；无新记录时 next 沿用原 cursor")
    void resumesFromCheckpointCursor() throws Exception {
        FakePort port = new FakePort();
        KafkaSource s = new KafkaSource(cfg(), port);

        Checkpoint current = new Checkpoint();
        current.cursor = "k:0=11;1=6";
        DimensionSource.PollResult r = s.poll(current);

        assertEquals(Map.of(0, 11L, 1, 6L), port.assigned);
        assertTrue(r.changes().isEmpty());
        assertEquals("k:0=11;1=6", r.next().cursor);
    }

    @Test
    @DisplayName("cursor-initial 配置：无断点时作为起点；格式残缺无害回落空")
    void cursorInitialUsedWhenNoCheckpoint() throws Exception {
        FakePort port = new FakePort();
        AgentConfig c = cfg();
        c.kafkaCursorInitial = "0=42;1=57";
        new KafkaSource(c, port).poll(new Checkpoint());
        assertEquals(Map.of(0, 42L, 1, 57L), port.assigned);

        FakePort bad = new FakePort();
        AgentConfig badCfg = cfg();
        badCfg.kafkaCursorInitial = "earliest";   // 非数字映射 → 空起点
        new KafkaSource(badCfg, bad).poll(new Checkpoint());
        assertTrue(bad.assigned.isEmpty());
    }

    @Test
    @DisplayName("坏消息跳过、offset 照常前进；usable 变更与坏消息混排")
    void badMessagesSkippedButOffsetsAdvance() throws Exception {
        FakePort port = new FakePort();
        port.batches.add(List.of(
                rec(0, 5, "not-json{"),
                rec(0, 6, "{\"resource_id\":\"sword_01\",\"version_ts\":7}"),
                rec(1, 2, "{\"no_resource_id\":\"x\"}")));
        KafkaSource s = new KafkaSource(cfg(), port);

        DimensionSource.PollResult r = s.poll(new Checkpoint());

        assertEquals(1, r.changes().size());
        assertEquals("sword_01", r.changes().get(0).resourceId);
        assertEquals("k:0=7;1=3", r.next().cursor);   // 坏消息分区同样推进
    }

    @Test
    @DisplayName("drain：多批连续拉取合并；纯坏消息轮 changes 空但位点已前进（不重放）")
    void drainMergesBatchesAndPureBadRoundStillAdvances() throws Exception {
        FakePort port = new FakePort();
        port.batches.add(List.of(rec(0, 1, "{\"resource_id\":\"a\"}")));
        port.batches.add(List.of(rec(0, 2, "bad{")));
        port.batches.add(List.of());   // 空批 → drain 结束
        KafkaSource s = new KafkaSource(cfg(), port);

        DimensionSource.PollResult r = s.poll(new Checkpoint());

        assertEquals(3, port.round);   // 拉了 3 轮才见空
        assertEquals(1, r.changes().size());
        assertEquals("k:0=3", r.next().cursor);
    }

    @Test
    @DisplayName("端口异常向上传播（AgentMain 走既有失败路径：errorCount++ 且 checkpoint 不前进）")
    void portFailurePropagates() {
        KafkaConsumerPort failing = new KafkaConsumerPort() {
            @Override public void assign(Map<Integer, Long> startOffsets) {
                throw new IllegalStateException("broker 不可达");
            }

            @Override public List<PortRecord> poll(long timeoutMs) {
                return List.of();
            }

            @Override public void close() {
            }
        };
        Checkpoint current = new Checkpoint();
        current.cursor = "k:0=1";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new KafkaSource(cfg(), failing).poll(current));
        assertEquals("broker 不可达", e.getMessage());
        assertEquals("k:0=1", current.cursor);   // poll 不落盘，current 未被改动
    }
}
