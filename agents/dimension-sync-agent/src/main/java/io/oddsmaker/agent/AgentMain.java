package io.oddsmaker.agent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环：poll → push → checkpoint 落盘 → 状态心跳。
 * checkpoint 只在推送全部成功后前进；失败路径 errorCount++ / lastError 落盘并上报，
 * 下一轮以旧水位重放（下游 ReplacingMergeTree 幂等）。
 * Control 托管配置热更新在轮询等待间隙执行（{@link #tickRefresh()}）：下发原文有变才
 * 重建 source/sink/status（构造成功才释放旧件），checkpoint 不动——断点跨换源续传。
 */
public final class AgentMain {

    private AgentConfig cfg;
    private DimensionSource source;
    private GatewaySink sink;
    private StatusReporter status;
    private final CheckpointStore store;
    /** 包内可见：shutdown hook 与测试置停用。 */
    final AtomicBoolean running = new AtomicBoolean(true);
    private Checkpoint current;
    /** 启动参数（热更新重建配置需重读本地文件 + System property，保留 args）。 */
    private final String[] args;
    private final ControlConfigFetcher fetcher;
    /** 上次实际应用的 Control 下发原文；热更新以此为变更基线（未启用托管或拉取失败为 null）。 */
    private String lastRemoteText;
    private long nextRefreshAtMillis;
    /** 热更新时钟，测试注入以确定性驱动刷新门槛（缺省墙钟，经构造器注入）。 */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    AgentMain(AgentConfig cfg, DimensionSource source, GatewaySink sink,
              StatusReporter status, CheckpointStore store) throws IOException {
        this(cfg, source, sink, status, store, null, null);
    }

    AgentMain(AgentConfig cfg, DimensionSource source, GatewaySink sink,
              StatusReporter status, CheckpointStore store,
              String[] args, ControlConfigFetcher fetcher) throws IOException {
        this(cfg, source, sink, status, store, args, fetcher, System::currentTimeMillis);
    }

    AgentMain(AgentConfig cfg, DimensionSource source, GatewaySink sink,
              StatusReporter status, CheckpointStore store,
              String[] args, ControlConfigFetcher fetcher,
              java.util.function.LongSupplier clock) throws IOException {
        this.cfg = cfg;
        this.source = source;
        this.sink = sink;
        this.status = status;
        this.store = store;
        this.current = store.load();
        this.args = args;
        this.fetcher = fetcher;
        this.clock = clock;
        this.lastRemoteText = cfg.appliedRemoteText;
        // 启动已完成首次拉取：首个热更新门槛 = 启动时刻 + 刷新间隔
        this.nextRefreshAtMillis = clock.getAsLong() + cfg.configRefreshSeconds * 1000L;
    }

    public static void main(String[] args) throws Exception {
        AgentConfig cfg = AgentConfig.load(args);
        cfg.validate();
        System.out.println("[agent] 启动 source=" + cfg.sourceType
                + " game=" + cfg.gameId + "/" + cfg.environment
                + " poll=" + cfg.pollSeconds + "s"
                + (cfg.configRefreshSeconds > 0 ? " 托管热更新=" + cfg.configRefreshSeconds + "s" : ""));
        AgentMain agent = new AgentMain(cfg, sourceOf(cfg),
                new GatewaySink(cfg),
                new StatusReporter(cfg),
                new CheckpointStore(java.nio.file.Path.of(cfg.checkpointPath)),
                args, new ControlConfigFetcher());
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
                    cfg.kafkaBootstrap, cfg.kafkaGroupId, cfg.kafkaTopic,
                    cfg.kafkaSecurityProtocol, cfg.kafkaSaslMechanism,
                    cfg.kafkaUsername, cfg.kafkaPassword,
                    new KafkaConsumerAdapter.SslSettings(
                            cfg.kafkaSslTruststorePath, cfg.kafkaSslTruststorePassword,
                            cfg.kafkaSslTruststoreType,
                            cfg.kafkaSslKeystorePath, cfg.kafkaSslKeystorePassword,
                            cfg.kafkaSslKeystoreType)));
            default -> throw new IllegalArgumentException("未知 source.type: " + cfg.sourceType);
        };
    }

    /**
     * 轮询主循环；shutdown hook 置 running=false 后最迟一个 poll 周期内退出。
     * 等待间隙每 500ms 醒一次尝试热更新（{@link #tickRefresh()} 内部门槛：refresh 关/未到
     * 间隔零开销返回），配置有变立即以新配置进入下一轮（不等旧 poll 间隔走完）。
     */
    void run() throws Exception {
        while (running.get()) {
            cycle();
            long deadline = clock.getAsLong() + cfg.pollSeconds * 1000L;
            while (running.get() && clock.getAsLong() < deadline) {
                if (tickRefresh()) {
                    break;   // 配置已换：立即以新 source/sink 进入下一轮
                }
                Thread.sleep(500);
            }
        }
    }

    /**
     * 到点热更新：重拉 Control 托管配置，下发原文有变才重建（文本相同零动作）。
     * 门槛：refresh 关（键缺省或 config-url 缺/测试注入路径）或未到 nextRefreshAt 零开销返回；
     * 任何失败（网络/非 2xx/新配置校验不过/新件构造失败）沿用当前配置——Control 不可达
     * 不阻断轮询。return true=已切换到新配置。
     */
    boolean tickRefresh() {
        if (args == null || fetcher == null
                || cfg.configRefreshSeconds <= 0
                || cfg.configUrl == null || cfg.configUrl.isBlank()) {
            return false;
        }
        long now = clock.getAsLong();
        if (now < nextRefreshAtMillis) {
            return false;
        }
        nextRefreshAtMillis = now + cfg.configRefreshSeconds * 1000L;
        try {
            String text = fetcher.fetch(cfg.configUrl, cfg.gameId, cfg.environment, cfg.sourceKey, cfg.statusToken);
            if (text.equals(lastRemoteText)) {
                return false;
            }
            AgentConfig next = AgentConfig.load(args, fetcher, text);
            next.validate();
            swapTo(next, text);
            return true;
        } catch (Exception e) {
            System.err.println("[config] 托管配置热更新失败，沿用当前配置: " + e.getMessage());
            return false;
        }
    }

    /** 换件：全部新件构造成功才释放旧件（任一构造抛出则旧件未动，本方法不达 swap）。 */
    private void swapTo(AgentConfig next, String remoteText) {
        DimensionSource newSource = sourceOf(next);
        GatewaySink newSink = new GatewaySink(next);
        StatusReporter newStatus = new StatusReporter(next);
        closeQuietly(source);
        closeQuietly(sink);
        closeQuietly(status);
        this.cfg = next;
        this.source = newSource;
        this.sink = newSink;
        this.status = newStatus;
        this.lastRemoteText = remoteText;
        System.out.println("[config] 托管配置热更新生效: source=" + next.sourceType
                + " poll=" + next.pollSeconds + "s refresh=" + next.configRefreshSeconds + "s");
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception e) {
            System.err.println("[config] 旧组件释放失败（忽略）: " + e.getMessage());
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

    /** 包内自省：当前生效配置（热更新后为新件）。 */
    AgentConfig config() {
        return cfg;
    }

    /** 包内自省：当前数据源（热更新后为新件）。 */
    DimensionSource source() {
        return source;
    }
}
