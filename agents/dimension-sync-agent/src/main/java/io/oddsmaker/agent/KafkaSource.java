package io.oddsmaker.agent;

import java.io.IOException;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Kafka 维度源：消息体为 JSON 对象（字段语义见 {@link KafkaRecordMapper}）。
 * 位点 checkpoint 自管（cursor 形如 "k:0=42;1=57"，分区 → 下一 offset），
 * 不依赖 broker group offset 提交——checkpoint.json 是唯一位点事实源，
 * 重启时按断点 assign+seek 确定性恢复。消费端口抽象为 {@link KafkaConsumerPort}，
 * 本类位点推进/drain/坏消息跳过逻辑全部可离线测试（真实实现见 {@link KafkaConsumerAdapter}，
 * 该路径仅编译期验证，尚无 broker 端到端）。
 *
 * <p>坏消息（解析失败/缺 resource_id）跳过但 offset 照常前进，防毒丸消息卡死位点——
 * 与 Csv/Excel 源「跳过坏行但文件照常记断点」同语义。
 */
public final class KafkaSource implements DimensionSource {

    /** 单轮 drain 上限：防高速 topic 饿死推送循环；未消费完的 offset 不推进，下轮续传。 */
    private static final int MAX_DRAIN_ROUNDS = 100;

    private final AgentConfig cfg;
    private final KafkaConsumerPort port;

    public KafkaSource(AgentConfig cfg, KafkaConsumerPort port) {
        this.cfg = cfg;
        this.port = port;
    }

    @Override
    public String name() {
        return "kafka:" + cfg.kafkaBootstrap + "/" + cfg.kafkaTopic;
    }

    @Override
    public String type() {
        return "kafka";
    }

    @Override
    public PollResult poll(Checkpoint current) throws Exception {
        SortedMap<Integer, Long> start = KafkaOffsets.decode(current.cursor);
        if (start.isEmpty() && cfg.kafkaCursorInitial != null && !cfg.kafkaCursorInitial.isBlank()) {
            // cursor-initial 复用 k: 编解码；非数字映射（如 "earliest"）无害回落空起点
            start = KafkaOffsets.decode(KafkaOffsets.PREFIX + cfg.kafkaCursorInitial.trim());
        }
        port.assign(start);

        List<DimensionChange> changes = new java.util.ArrayList<>();
        SortedMap<Integer, Long> nextOffsets = new TreeMap<>();
        boolean consumedAny = false;
        long now = System.currentTimeMillis();
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            List<KafkaConsumerPort.PortRecord> batch = port.poll(cfg.kafkaPollTimeoutMs);
            if (batch.isEmpty()) {
                break;
            }
            for (KafkaConsumerPort.PortRecord rec : batch) {
                consumedAny = true;
                nextOffsets.put(rec.partition(), rec.offset() + 1);
                DimensionChange change;
                try {
                    change = KafkaRecordMapper.map(rec.value(), cfg.dimType, now);
                } catch (IOException e) {
                    System.err.println("[kafka] 跳过坏消息（分区 " + rec.partition() + " 偏移 " + rec.offset() + "）: " + e.getMessage());
                    continue;
                }
                if (change.resourceId == null || change.resourceId.isBlank()) {
                    System.err.println("[kafka] 跳过缺 resource_id 的消息（分区 " + rec.partition() + " 偏移 " + rec.offset() + "）");
                    continue;
                }
                changes.add(change);
            }
        }
        if (!consumedAny) {
            return PollResult.of(changes, current.copy());
        }
        Checkpoint next = current.copy();
        next.cursor = KafkaOffsets.encode(nextOffsets);
        if (!changes.isEmpty()) {
            next.lastEventTs = changes.stream().mapToLong(c -> c.versionTs).max().orElse(now);
        }
        return PollResult.of(changes, next);
    }
}
