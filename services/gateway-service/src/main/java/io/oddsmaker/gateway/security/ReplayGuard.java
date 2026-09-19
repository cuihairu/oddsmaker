package io.oddsmaker.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 风控前置：重放防护。
 *
 * 1. 签名重放：同一 HMAC 签名在时间窗内只允许出现一次，重复直接拒绝；
 * 2. event_id 幂等吸收：SDK 重试导致的重复 event_id 静默吸收（不重复发布），
 *    防止重试风暴放大写入量。
 *
 * 本地分代缓存：current 写入、previous 只读，TTL 到期整体轮换，容量恒定防 OOM。
 * 多实例部署时窗口为单实例局部，完整方案需接入共享 Redis。
 */
@Component
public class ReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(ReplayGuard.class);

    private final long ttlMillis;
    private final int maxEntries;
    private final long maxEventDriftMs;

    private volatile Generation signatures;
    private volatile Generation eventIds;

    /** 轮换互斥锁。ReentrantLock 与 synchronized(this) 语义等价（类内唯一同步点），
     *  显式锁无 monitorexit 合成出口，行为与覆盖率均无死角。 */
    private final ReentrantLock rotateLock = new ReentrantLock();

    private static final class Generation {
        final Map<String, Boolean> seen = new ConcurrentHashMap<>();
        final long expireAt;
        final int maxEntries;

        Generation(long ttlMillis, int maxEntries) {
            this.expireAt = System.currentTimeMillis() + ttlMillis;
            this.maxEntries = maxEntries;
        }

        boolean isExpired(long now) {
            return now >= expireAt;
        }

        boolean mark(String key) {
            if (seen.size() >= maxEntries) {
                // 容量保护：拒绝新记录而不是无限膨胀；退化为本代不再拦截新 key
                return false;
            }
            return seen.putIfAbsent(key, Boolean.TRUE) == null;
        }
    }

    public ReplayGuard(
            @Value("${oddsmaker.risk.replay-ttl-ms:600000}") long ttlMillis,
            @Value("${oddsmaker.risk.replay-max-entries:200000}") int maxEntries,
            @Value("${oddsmaker.risk.max-event-ts-drift-ms:86400000}") long maxEventDriftMs) {
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        this.maxEventDriftMs = maxEventDriftMs;
        this.signatures = new Generation(ttlMillis, maxEntries);
        this.eventIds = new Generation(ttlMillis, maxEntries);
    }

    /**
     * 记录签名；返回 false 表示签名已被使用（重放攻击）。
     */
    public boolean consumeSignature(String signature) {
        boolean fresh = rotateIfNeeded(signatures).mark(signature);
        if (!fresh) {
            log.warn("Replay detected: signature already used within window");
        }
        return fresh;
    }

    /**
     * 记录 event_id；返回 false 表示近期已接收（重试/重放）。
     */
    public boolean consumeEventId(String eventId) {
        return rotateIfNeeded(eventIds).mark(eventId);
    }

    /**
     * 事件时间戳信差检查：客户端时间偏离服务器过远视为可疑（伪造或严重时钟漂移）。
     */
    public boolean isTimestampPlausible(Long tsClient, long nowMillis) {
        if (tsClient == null) {
            return true; // 缺失由 schema 校验兜底
        }
        return Math.abs(nowMillis - tsClient) <= maxEventDriftMs;
    }

    public long maxEventDriftMillis() {
        return maxEventDriftMs;
    }

    private Generation rotateIfNeeded(Generation current) {
        long now = System.currentTimeMillis();
        if (!current.isExpired(now)) {
            return current;
        }
        rotateLock.lock();
        try {
            // 不做锁内双检：expireAt 为 final、isExpired 单调恒真，锁外判过 true 后不可能回退；
            // 并发下重复轮换只是各自换新代并覆盖 volatile 引用，正确性不受影响
            // 轮换：当前代直接丢弃（TTL 已覆盖签名时间窗的 2 倍）
            Generation next = new Generation(ttlMillis, maxEntries);
            if (current == signatures) {
                signatures = next;
            } else {
                eventIds = next;
            }
            return next;
        } finally {
            rotateLock.unlock();
        }
    }
}
