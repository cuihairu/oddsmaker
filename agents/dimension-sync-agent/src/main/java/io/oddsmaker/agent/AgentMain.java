package io.oddsmaker.agent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环：poll → push → checkpoint 落盘 → 状态心跳。
 * checkpoint 只在推送全部成功后前进；失败路径 errorCount++ / lastError 落盘并上报，
 * 下一轮以旧水位重放（下游 ReplacingMergeTree 幂等）。
 */
public final class AgentMain {

    private final AgentConfig cfg;
    private final DimensionSource source;
    private final GatewaySink sink;
    private final StatusReporter status;
    private final CheckpointStore store;
    /** 包内可见：shutdown hook 与测试置停用。 */
    final AtomicBoolean running = new AtomicBoolean(true);
    private Checkpoint current;

    AgentMain(AgentConfig cfg, DimensionSource source, GatewaySink sink,
              StatusReporter status, CheckpointStore store) throws IOException {
        this.cfg = cfg;
        this.source = source;
        this.sink = sink;
        this.status = status;
        this.store = store;
        this.current = store.load();
    }

    public static void main(String[] args) throws Exception {
        AgentConfig cfg = AgentConfig.load(args);
        cfg.validate();
        System.out.println("[agent] 启动 source=" + cfg.sourceType
                + " game=" + cfg.gameId + "/" + cfg.environment
                + " poll=" + cfg.pollSeconds + "s");
        AgentMain agent = new AgentMain(cfg, sourceOf(cfg),
                new GatewaySink(cfg),
                new StatusReporter(cfg),
                new CheckpointStore(java.nio.file.Path.of(cfg.checkpointPath)));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> agent.running.set(false)));
        agent.run();
    }

    /** 按配置构造数据源；validate 已保证 source.type 合法。 */
    static DimensionSource sourceOf(AgentConfig cfg) {
        if (cfg.isJdbc()) {
            return new JdbcSource(cfg);
        }
        return switch (cfg.sourceType) {
            case "csv" -> new CsvSource(cfg);
            case "excel" -> new ExcelSource(cfg);
            case "kafka" -> new KafkaSource(cfg, new KafkaConsumerAdapter(
                    cfg.kafkaBootstrap, cfg.kafkaGroupId, cfg.kafkaTopic));
            default -> throw new IllegalArgumentException("未知 source.type: " + cfg.sourceType);
        };
    }

    /** 轮询主循环；shutdown hook 置 running=false 后最迟一个 poll 周期内退出。 */
    void run() throws Exception {
        while (running.get()) {
            cycle();
            for (int waited = 0; waited < cfg.pollSeconds * 1000 && running.get(); waited += 500) {
                Thread.sleep(500);
            }
        }
    }

    /** 单轮：拉取 → 推送 → 断点落盘 → 状态心跳（每轮必报，Control 以 last_push_at 判活）。 */
    synchronized void cycle() {
        try {
            DimensionSource.PollResult result = source.poll(current);
            if (!result.changes().isEmpty()) {
                long pushed = sink.push(result.changes());
                Checkpoint next = result.next();
                next.pushedCount = current.pushedCount + pushed;
                next.errorCount = current.errorCount;
                next.lastError = current.lastError;
                current = next;
                store.save(current);
                System.out.println("[agent] 已推送 " + pushed + " 条维度变更, cursor=" + current.cursor);
            }
            status.report(current);
        } catch (Exception e) {
            current.errorCount++;
            current.lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            try {
                store.save(current);
            } catch (IOException ioe) {
                System.err.println("[agent] checkpoint 落盘失败: " + ioe.getMessage());
            }
            status.report(current);
            System.err.println("[agent] 轮询/推送异常(第 " + current.errorCount + " 次): " + current.lastError);
        }
    }

    Checkpoint checkpoint() {
        return current;
    }
}
