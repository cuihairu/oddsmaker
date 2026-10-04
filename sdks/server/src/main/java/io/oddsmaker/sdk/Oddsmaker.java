package io.oddsmaker.sdk;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Oddsmaker Server SDK（一等公民，零依赖）。
 *
 * <p>用法：
 * <pre>{@code
 * Oddsmaker sdk = Oddsmaker.initialize(new Oddsmaker.Config()
 *     .endpoint("http://gateway:8080")
 *     .apiKey("pk_…").secret("sk_…")          // SERVER 型 key（game 须启用 server_events_enabled）
 *     .gameId("game_demo").environment("prod"));
 * sdk.setUser("player-1", Map.of("tier", "gold"));
 * sdk.track("server.purchase.confirmed", Map.of("amount", 9.99, "currency", "USD"));
 * sdk.flush();   // 同步清空队列（可选；后台按 flushIntervalMs 自动 flush）
 * sdk.close();   // 最终 flush 并停泵；未发完的磁盘溢写文件保留，重启续传
 * }</pre>
 *
 * <p>事件契约（v1）：必填 {@code event_id/event_name/ts_client/game_id/environment/device_id}
 * 由 SDK 自动补齐；事件命名建议 {@code server.<domain>.<action>}。充值/经济类结算只认
 * server 事件（06 计划书 §4.3）。契约 v2 增量字段 {@code event_version/source/event_origin}
 * 由 SDK 自动声明（source=server；trust_level 由网关按 source 推导，不可自抬）。
 *
 * <p>投递管道：Memory→Disk Queue→Batch→Gzip→HMAC。2xx 丢弃；401/403（凭证/签名无法
 * 自愈）丢弃并告警；429/5xx/网络异常整批重新入队待下次投递。
 */
public final class Oddsmaker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Oddsmaker.class.getName());
    private static final AtomicReference<Oddsmaker> DEFAULT = new AtomicReference<>();

    /** SDK 版本（event_origin 审计字段组成部分）。 */
    public static final String VERSION = "0.1.0";

    private final Config config;
    private final EventQueue queue;
    private final HttpSink sink;
    private final ScheduledExecutorService pump;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object sendLock = new Object();

    /** setUser 上下文：volatile 引用，track 时快照读取。 */
    private volatile UserContext user = new UserContext(null, Map.of());

    private Oddsmaker(Config config) {
        this.config = config;
        Path dir = Path.of(config.queueDir);
        this.queue = new EventQueue(dir, config.maxMemoryEvents, config.maxDiskEvents, this::warn);
        this.sink = new HttpSink(config);
        this.pump = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oddsmaker-server-sdk-flush");
            t.setDaemon(true);
            return t;
        });
        this.pump.scheduleWithFixedDelay(this::flushQuietly,
            config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    // ===== 静态门面 =====

    /** 初始化进程级默认实例；重复初始化（未 close）抛 IllegalStateException。 */
    public static Oddsmaker initialize(Config config) {
        Oddsmaker instance = new Oddsmaker(config);
        if (!DEFAULT.compareAndSet(null, instance)) {
            instance.close();
            throw new IllegalStateException(
                "Oddsmaker already initialized; call close() before re-initializing");
        }
        return instance;
    }

    // ===== 实例 API =====

    /** 设置后续事件的默认用户（user_id）。 */
    public void setUser(String userId) {
        this.user = new UserContext(userId, this.user.traits);
    }

    /** 设置默认用户及随事件携带的属性（显式 track props 同名键优先）。 */
    public void setUser(String userId, Map<String, Object> traits) {
        this.user = new UserContext(userId, traits == null ? Map.of() : Map.copyOf(traits));
    }

    public void track(String eventName) {
        track(eventName, null, null);
    }

    public void track(String eventName, Map<String, Object> props) {
        track(eventName, null, props);
    }

    /** 显式指定 user_id 的事件（覆盖 setUser 上下文）。 */
    public void track(String eventName, String userId, Map<String, Object> props) {
        if (closed.get()) {
            warn("closed", "track after close, event dropped: " + eventName);
            return;
        }
        if (eventName == null || eventName.isBlank()) {
            warn("invalid-event", "event_name is blank, dropped");
            return;
        }
        UserContext ctx = user;
        String uid = userId != null ? userId : ctx.userId;
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(ctx.traits);
        if (props != null) {
            merged.putAll(props);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_name", eventName);
        event.put("ts_client", System.currentTimeMillis());
        event.put("game_id", config.gameId);
        event.put("environment", config.environment);
        event.put("device_id", uid != null && !uid.isBlank() ? uid : config.deviceId);
        // 事件契约 v2 声明（06 计划书 §4.2）：source 为 SERVER SDK 必填档；
        // trust_level 由网关按 source 推导，SDK 不声明、声明了也不被采信
        event.put("event_version", 1);
        event.put("source", "server");
        event.put("event_origin", "server-java/" + VERSION);
        if (uid != null && !uid.isBlank()) {
            event.put("user_id", uid);
        }
        if (!merged.isEmpty()) {
            event.put("props", merged);
        }
        queue.enqueue(Json.write(event));
    }

    /** 同步清空队列（内存+磁盘溢写），返回投递统计。可重试失败整批退回即止，待下次投递。 */
    public FlushResult flush() {
        FlushResult result = new FlushResult();
        while (true) {
            List<String> batch = queue.take(config.batchSize);
            if (batch.isEmpty()) {
                break;
            }
            if (!deliver(batch, result)) {
                break; // 可重试失败：整批已退回队列，本轮止步（防死循环），pump/下次 flush 续投
            }
        }
        return result;
    }

    /** 最终 flush 并停泵（幂等）。未能发出的内存事件落盘、磁盘溢写文件保留——重启续传。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            DEFAULT.compareAndSet(this, null);
            pump.shutdownNow();
            try {
                flush();
            } catch (Exception e) {
                warn("close-flush-failed", e.toString());
            } finally {
                queue.spillAll();   // 内存残留落盘：endpoint 不可达时 close 不丢事件
            }
        }
    }

    // ===== 内部 =====

    private void flushQuietly() {
        if (queue.isEmpty()) {
            return;
        }
        try {
            flush();
        } catch (Exception e) {
            warn("pump-flush-failed", e.toString());
        }
    }

    /** @return true=本批已终结（接受或丢弃），可继续下一批；false=整批退回，本轮止步。 */
    private boolean deliver(List<String> batch, FlushResult result) {
        result.attempted += batch.size();
        try {
            int status = sink.send(batch);
            if (status >= 200 && status < 300) {
                result.accepted += batch.size();
                result.batches++;
                return true;
            }
            if (status == 401 || status == 403) {
                // 凭证/签名错误不会自愈：重试只会永远失败；丢弃并高声告警
                result.dropped += batch.size();
                warn("auth-rejected", "HTTP " + status + ", dropped " + batch.size()
                    + " events (check SERVER key secret/rotation)");
                return true;
            }
            // 429/5xx 等：整批退回，等下次投递
            return retryLater(batch, result, status);
        } catch (Exception e) {
            return retryLater(batch, result, e);
        }
    }

    private boolean retryLater(List<String> batch, FlushResult result, Object cause) {
        queue.reoffer(batch);
        result.pending += batch.size();
        result.retries++;
        warn("retry", "kept " + batch.size() + " events for retry, cause: " + cause);
        return false;
    }

    private void warn(String code, String message) {
        LOG.log(Level.WARNING, "[oddsmaker-server-sdk:{0}] {1}", new Object[]{code, message});
    }

    private record UserContext(String userId, Map<String, Object> traits) {}

    /** flush 统计。 */
    public static final class FlushResult {
        public long accepted;
        public long attempted;
        public long dropped;
        public long pending;
        public long batches;
        public long retries;

        @Override
        public String toString() {
            return "FlushResult{accepted=" + accepted + ", attempted=" + attempted
                + ", dropped=" + dropped + ", pending=" + pending
                + ", batches=" + batches + ", retries=" + retries + "}";
        }
    }

    /** 配置（fluent）。必填：endpoint/apiKey/secret/gameId/environment。 */
    public static final class Config {
        public String endpoint;
        public String apiKey;
        public String secret;
        public String gameId;
        public String environment;
        /** 事件 device_id 兜底（无用户上下文的服务端事件主体标识）。 */
        public String deviceId = "server";
        /** 磁盘溢写目录（跨进程续传；默认系统临时目录下）。 */
        public String queueDir = System.getProperty("java.io.tmpdir", "/tmp")
            + "/oddsmaker-server-sdk";
        public int maxMemoryEvents = 10_000;
        public long maxDiskEvents = 100_000;
        public int batchSize = 100;
        public long flushIntervalMs = 5_000;
        public int connectTimeoutMs = 2_000;
        public int requestTimeoutMs = 10_000;

        public Config endpoint(String v) { this.endpoint = v; return this; }
        public Config apiKey(String v) { this.apiKey = v; return this; }
        public Config secret(String v) { this.secret = v; return this; }
        public Config gameId(String v) { this.gameId = v; return this; }
        public Config environment(String v) { this.environment = v; return this; }
        public Config deviceId(String v) { this.deviceId = v; return this; }
        public Config queueDir(String v) { this.queueDir = v; return this; }
        public Config maxMemoryEvents(int v) { this.maxMemoryEvents = v; return this; }
        public Config maxDiskEvents(long v) { this.maxDiskEvents = v; return this; }
        public Config batchSize(int v) { this.batchSize = v; return this; }
        public Config flushIntervalMs(long v) { this.flushIntervalMs = v; return this; }
        public Config connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
        public Config requestTimeoutMs(int v) { this.requestTimeoutMs = v; return this; }
    }
}
