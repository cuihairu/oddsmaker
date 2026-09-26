package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DimensionSyncStatusEntity;
import io.oddsmaker.control.jpa.DimensionSyncStatusRepo;
import io.oddsmaker.control.jpa.GameRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 维度同步状态服务：Agent 心跳/位点上报（最后写入胜出 upsert）与同步延迟查询。
 * 心跳语义：每次上报（无论携带变更与否）都刷新 last_push_at，延迟按"距上次推送/距源头最新变更"两口径展示。
 */
@Service
public class DimensionSyncStatusService {

    private final DimensionSyncStatusRepo statusRepo;
    private final GameRepo gameRepo;

    public DimensionSyncStatusService(DimensionSyncStatusRepo statusRepo, GameRepo gameRepo) {
        this.statusRepo = statusRepo;
        this.gameRepo = gameRepo;
    }

    /** Agent 上报体：gameId/environment/sourceKey 必填，其余可缺省。 */
    public static final class StatusUpsert {
        public String gameId;
        public String environment;
        public String sourceKey;
        public String sourceType;
        public String cursor;
        public Long lastEventTs;      // epoch millis
        public Long pushedCount;      // Agent 侧累计（checkpoint 持久，重启不丢）
        public Long errorCount;
        public String lastError;
    }

    @Transactional
    public DimensionSyncStatusEntity upsert(StatusUpsert req) {
        if (req.gameId == null || req.gameId.isBlank()) {
            throw new IllegalArgumentException("gameId is required");
        }
        if (req.environment == null || req.environment.isBlank()) {
            throw new IllegalArgumentException("environment is required");
        }
        if (req.sourceKey == null || req.sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey is required");
        }
        gameRepo.findById(req.gameId)
                .filter(game -> game.deletedAt == null)
                .orElseThrow(() -> new IllegalArgumentException("Game not found: " + req.gameId));

        String env = req.environment.trim();
        DimensionSyncStatusEntity e = statusRepo
                .findByGameIdAndEnvironmentAndSourceKey(req.gameId, env, req.sourceKey.trim())
                .orElseGet(() -> {
                    DimensionSyncStatusEntity n = new DimensionSyncStatusEntity();
                    n.id = "dss_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                    n.gameId = req.gameId;
                    n.environment = env;
                    n.sourceKey = req.sourceKey.trim();
                    n.createdAt = LocalDateTime.now();
                    return n;
                });
        if (req.sourceType != null && !req.sourceType.isBlank()) e.sourceType = req.sourceType.trim();
        if (e.sourceType == null) e.sourceType = "unknown";
        if (req.cursor != null) e.cursor = req.cursor;
        if (req.lastEventTs != null && req.lastEventTs > 0) {
            e.lastEventTs = LocalDateTime.now().minusSeconds(
                    Math.max(0, (System.currentTimeMillis() - req.lastEventTs) / 1000));
        }
        if (req.pushedCount != null && req.pushedCount >= 0) e.pushedCount = req.pushedCount;
        if (req.errorCount != null && req.errorCount >= 0) e.errorCount = req.errorCount;
        e.lastError = req.lastError;
        e.lastPushAt = LocalDateTime.now();   // 心跳：任何上报都刷新
        e.updatedAt = LocalDateTime.now();
        return statusRepo.save(e);
    }

    @Transactional(readOnly = true)
    public List<DimensionSyncStatusEntity> list(String gameId, String environment) {
        if (environment != null && !environment.isBlank()) {
            return statusRepo.findByGameIdAndEnvironment(gameId, environment);
        }
        return statusRepo.findByGameId(gameId);
    }

    /** 实体 → 响应投影：附两口径同步延迟（秒，null 表示尚无打点）。 */
    public Map<String, Object> toResp(DimensionSyncStatusEntity e) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("gameId", e.gameId);
        m.put("environment", e.environment);
        m.put("sourceKey", e.sourceKey);
        m.put("sourceType", e.sourceType);
        m.put("cursor", e.cursor);
        m.put("lastEventTs", e.lastEventTs);
        m.put("lastPushAt", e.lastPushAt);
        m.put("sinceLastPushSeconds", secondsSince(e.lastPushAt, now));
        m.put("sinceLastEventSeconds", secondsSince(e.lastEventTs, now));
        m.put("pushedCount", e.pushedCount);
        m.put("errorCount", e.errorCount);
        m.put("lastError", e.lastError);
        m.put("updatedAt", e.updatedAt);
        return m;
    }

    static Long secondsSince(LocalDateTime t, LocalDateTime now) {
        if (t == null) return null;
        return Math.max(0, Duration.between(t, now).getSeconds());
    }
}
