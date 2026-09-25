package io.oddsmaker.gateway.inspector;

import io.oddsmaker.common.model.Event;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时事件检视缓冲（Live Inspector / Debug View，竞品差距 P7-1）。
 *
 * 为每个 (game_id, environment) 作用域维护一条有界环形队列，记录每条到达事件的
 * 处理结局（accepted/rejected/sampled_out/duplicate）与拒绝原因明细，供
 * /v1/inspector/recent 拉取，解决接入期"事件到底到没到、为什么被拒"的第一痛点。
 *
 * 设计约束：
 * - 纯内存、仅元数据（不存 props/PII），重启即清空——检视是调试辅助而非数据面；
 * - 作用域数量有上限（maxScopes），超出按 LRU 淘汰整条队列，防止 key 空间被刷爆；
 * - 记录带 TTL（retainMs），读取时按时间过滤并顺手清理队头过期项；
 * - game_id/environment 缺失的事件无法归属任何作用域，不入缓冲（DLQ 已有全量）。
 */
@Component
public class EventInspectorBuffer {

    public static final String OUTCOME_ACCEPTED = "accepted";
    public static final String OUTCOME_REJECTED = "rejected";
    public static final String OUTCOME_SAMPLED_OUT = "sampled_out";
    public static final String OUTCOME_DUPLICATE = "duplicate";

    public static final Set<String> OUTCOMES =
        Set.of(OUTCOME_ACCEPTED, OUTCOME_REJECTED, OUTCOME_SAMPLED_OUT, OUTCOME_DUPLICATE);

    /** 单作用域队列容量（oddsmaker.inspector.buffer-size，默认 200）。 */
    private final int perScopeCapacity;
    /** 最多追踪的作用域数（oddsmaker.inspector.max-scopes，默认 128）。 */
    private final int maxScopes;
    /** 记录保留时长（oddsmaker.inspector.retain-ms，默认 10 分钟）。 */
    private final long retainMs;

    private final ConcurrentHashMap<String, Deque<InspectorRecord>> scopes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTouch = new ConcurrentHashMap<>();

    /**
     * 单条检视记录：仅路由/分类/身份标识元数据 + 结局与原因。
     * 有意不携带 props、client_ip、user_agent——检视面不引入第二份 PII 拷贝。
     */
    public static class InspectorRecord {
        public long tsServer;
        public String eventId;
        public String eventName;
        public String eventType;
        public String deviceId;
        public String userId;
        public String platform;
        public String appVersion;
        public long tsClient;
        public String outcome;
        public String reason;
        public String detail;
    }

    // 双构造器（Environment 注入 + 测试直调），需显式标注注入构造器
    @org.springframework.beans.factory.annotation.Autowired
    public EventInspectorBuffer(Environment env) {
        this(
            Binder.get(env).bind("oddsmaker.inspector.buffer-size", Integer.class).orElse(200),
            Binder.get(env).bind("oddsmaker.inspector.max-scopes", Integer.class).orElse(128),
            Binder.get(env).bind("oddsmaker.inspector.retain-ms", Long.class).orElse(600_000L));
    }

    /** 测试直调构造（绕过 Spring Environment）。 */
    public EventInspectorBuffer(int perScopeCapacity, int maxScopes, long retainMs) {
        this.perScopeCapacity = perScopeCapacity;
        this.maxScopes = maxScopes;
        this.retainMs = retainMs;
    }

    /**
     * 记录一条事件结局。gameId/environment 为 null/blank 时静默跳过
     * （路由字段缺失的事件无法归属作用域；DLQ 已捕获全量拒绝）。
     */
    public void record(String gameId, String environment, String outcome, String reason,
                       String detail, Event event) {
        if (gameId == null || gameId.isBlank() || environment == null || environment.isBlank()) {
            return;
        }
        InspectorRecord rec = new InspectorRecord();
        rec.tsServer = System.currentTimeMillis();
        rec.eventId = event.eventId;
        rec.eventName = event.eventName;
        rec.eventType = event.eventType;
        rec.deviceId = event.deviceId;
        rec.userId = event.userId;
        rec.platform = event.platform;
        rec.appVersion = event.appVersion;
        rec.tsClient = event.tsClient;
        rec.outcome = outcome;
        rec.reason = reason;
        rec.detail = detail;

        String key = scopeKey(gameId, environment);
        Deque<InspectorRecord> deque = scopes.computeIfAbsent(key, k -> new ArrayDeque<>());
        lastTouch.put(key, rec.tsServer);
        synchronized (deque) {
            deque.addLast(rec);
            while (deque.size() > perScopeCapacity) {
                deque.removeFirst();
            }
        }
        evictStaleScopes();
    }

    /**
     * 读取作用域最近记录（新→旧）。outcome 为 null/blank 表示不过滤；
     * limit 上限固定 200（控制器侧钳制），此处仅防御负数。
     */
    public List<InspectorRecord> recent(String gameId, String environment, String outcome, int limit) {
        String key = scopeKey(gameId, environment);
        Deque<InspectorRecord> deque = scopes.get(key);
        if (deque == null) {
            return List.of();
        }
        lastTouch.put(key, System.currentTimeMillis());
        long cutoff = System.currentTimeMillis() - retainMs;
        String outcomeFilter = outcome == null || outcome.isBlank()
            ? null : outcome.toLowerCase(Locale.ROOT);
        List<InspectorRecord> out = new ArrayList<>();
        synchronized (deque) {
            // 顺手清理队头过期项（addLast 有序，队头最旧）
            while (!deque.isEmpty() && deque.peekFirst().tsServer < cutoff) {
                deque.removeFirst();
            }
            java.util.Iterator<InspectorRecord> it = deque.descendingIterator();
            while (it.hasNext() && out.size() < Math.max(0, limit)) {
                InspectorRecord rec = it.next();
                if (rec.tsServer < cutoff) {
                    break;
                }
                if (outcomeFilter == null || outcomeFilter.equals(rec.outcome)) {
                    out.add(rec);
                }
            }
        }
        return out;
    }

    /** 当前活跃作用域数（测试与自检用）。 */
    public int scopeCount() {
        return scopes.size();
    }

    /** 清空全部作用域（测试隔离用）。 */
    public void clear() {
        scopes.clear();
        lastTouch.clear();
    }

    private void evictStaleScopes() {
        if (scopes.size() <= maxScopes) {
            return;
        }
        // 按 lastTouch 升序淘汰最久未写作用域，直到回到上限内
        List<Map.Entry<String, Long>> byAge = new ArrayList<>(lastTouch.entrySet());
        byAge.sort(Map.Entry.comparingByValue());
        int toEvict = scopes.size() - maxScopes;
        for (Map.Entry<String, Long> entry : byAge) {
            if (toEvict <= 0) {
                break;
            }
            if (scopes.remove(entry.getKey()) != null) {
                lastTouch.remove(entry.getKey());
                toEvict--;
            }
        }
    }

    private String scopeKey(String gameId, String environment) {
        return gameId + "|" + environment;
    }
}
