package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RetentionEnforcementEntity;
import io.oddsmaker.control.jpa.RetentionEnforcementRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据保留策略自动化：把 game/environment 的 dataRetentionDays 配置落到 ClickHouse 表级 TTL。
 *
 * <p>CH 表全库共享（不分 game/environment），TTL 是表级物理属性，故表级期望值取
 * 全部游戏有效保留天数的 min（最严格承诺生效，数据最小化合规安全方向）。
 * 有效保留天数：env.dataRetentionDays ?? game.dataRetentionDays ?? default-days，
 * 最终 clamp 到 [min-days, max-days]（防误配 0 导致 TTL 立即清光数据）。
 *
 * <p>对账：读 system.tables 的 engine_full 解析当前 TTL，漂移则逐表
 * {@code ALTER TABLE ... MODIFY TTL}（metadata 操作，不产生 mutation；
 * materialize_ttl_after_modify 默认开启，CH 后台自动重写既有 parts）。
 * 仅管理白名单内的明细/事实表——inline MV 聚合草图内表与业务自管 expires_at 的表排除。
 */
@Service
public class RetentionEnforcementService {

    private static final Logger logger = LoggerFactory.getLogger(RetentionEnforcementService.class);

    /** 受管表 → TTL 基准列白名单（SQL 字面量，无注入面）。排除 MV 草图内表/predictions/维度表。 */
    static final Map<String, String> TABLE_TTL_COLUMNS = new LinkedHashMap<>();
    static {
        TABLE_TTL_COLUMNS.put("events", "event_date");
        TABLE_TTL_COLUMNS.put("game_events", "event_date");
        TABLE_TTL_COLUMNS.put("sessions", "session_start");
        TABLE_TTL_COLUMNS.put("identities", "last_seen");
        TABLE_TTL_COLUMNS.put("risk_events", "ts");
        TABLE_TTL_COLUMNS.put("risk_scores", "updated_at");
        TABLE_TTL_COLUMNS.put("risk_actions", "ts");
        TABLE_TTL_COLUMNS.put("retention", "cohort_date");
        TABLE_TTL_COLUMNS.put("retention_daily", "cohort_date");
        TABLE_TTL_COLUMNS.put("retention_rolling", "cohort_date");
        TABLE_TTL_COLUMNS.put("funnels", "ts");
        TABLE_TTL_COLUMNS.put("funnels_2step", "event_date");
        TABLE_TTL_COLUMNS.put("funnels_configurable", "event_date");
        TABLE_TTL_COLUMNS.put("exp_exposure_users", "expose_ts");
        TABLE_TTL_COLUMNS.put("exp_first_level_complete", "conv_ts");
        TABLE_TTL_COLUMNS.put("exp_daily_exposures", "event_date");
        TABLE_TTL_COLUMNS.put("exp_daily_conv_24h", "exposure_date");
        TABLE_TTL_COLUMNS.put("exp_daily_conv_7d", "exposure_date");
        TABLE_TTL_COLUMNS.put("exp_daily_exposures_dim", "event_date");
        TABLE_TTL_COLUMNS.put("exp_daily_conv_24h_dim", "exposure_date");
        TABLE_TTL_COLUMNS.put("exp_daily_conv_7d_dim", "exposure_date");
    }

    /** engine_full 中 {@code TTL <col> + INTERVAL <n> DAY}（容忍反引号列名与 DELETE 后缀） */
    private static final Pattern TTL_PATTERN =
            Pattern.compile("TTL\\s+`?\\w+`?\\s*\\+\\s*INTERVAL\\s+(\\d+)\\s+DAY", Pattern.CASE_INSENSITIVE);

    private final ClickHouseClient clickHouse;
    private final GameRepo gameRepo;
    private final GameEnvironmentRepo gameEnvironmentRepo;
    private final RetentionEnforcementRepo enforcementRepo;
    private final AuditLogService auditLog;
    private final boolean enabled;
    private final int defaultDays;
    private final int minDays;
    private final int maxDays;

    public RetentionEnforcementService(ClickHouseClient clickHouse,
                                       GameRepo gameRepo,
                                       GameEnvironmentRepo gameEnvironmentRepo,
                                       RetentionEnforcementRepo enforcementRepo,
                                       AuditLogService auditLog,
                                       @Value("${oddsmaker.retention.enabled:false}") boolean enabled,
                                       @Value("${oddsmaker.retention.default-days:90}") int defaultDays,
                                       @Value("${oddsmaker.retention.min-days:7}") int minDays,
                                       @Value("${oddsmaker.retention.max-days:3650}") int maxDays) {
        this.clickHouse = clickHouse;
        this.gameRepo = gameRepo;
        this.gameEnvironmentRepo = gameEnvironmentRepo;
        this.enforcementRepo = enforcementRepo;
        this.auditLog = auditLog;
        this.enabled = enabled;
        this.defaultDays = defaultDays;
        this.minDays = minDays;
        this.maxDays = maxDays;
    }

    // ===== 状态查询（Controller 用） =====

    public boolean isConfigured() {
        return clickHouse.isAvailable();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<RetentionEnforcementEntity> listStates() {
        return enforcementRepo.findAllByOrderByChTable();
    }

    // ===== 配置解析 =====

    /** 有效保留天数：env 覆盖 ?? 游戏级 ?? 全局默认（实现 EnvironmentEntity"继承自游戏配置"注释约定）。 */
    int effectiveDays(GameEntity game, GameEnvironmentEntity env) {
        Integer envDays = env != null ? env.dataRetentionDays : null;
        if (envDays != null) {
            return envDays;
        }
        Integer gameDays = game != null ? game.dataRetentionDays : null;
        return gameDays != null ? gameDays : defaultDays;
    }

    /**
     * 表级期望天数 = min(全部游戏及其环境的有效保留天数)，clamp 到 [minDays, maxDays]。
     * @return null = 无未删除游戏（无有效配置）
     */
    public Integer resolveExpectedDays() {
        List<GameEntity> games = gameRepo.findByDeletedAtIsNull();
        if (games.isEmpty()) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        for (GameEntity game : games) {
            min = Math.min(min, effectiveDays(game, null));
            for (GameEnvironmentEntity env : gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull(game.id)) {
                min = Math.min(min, effectiveDays(game, env));
            }
        }
        return clamp(min);
    }

    int clamp(int days) {
        return Math.max(minDays, Math.min(maxDays, days));
    }

    /** @return 解析出的 TTL 天数；null = engine_full 无 TTL 子句（调用方需区分"真无 TTL"与"解析失败"） */
    Integer parseTtlDays(String engineFull) {
        Matcher m = TTL_PATTERN.matcher(engineFull);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    // ===== 对账 =====

    /** 定时对账：仅 enabled 开启时执行（TTL 清理不可逆，默认关，显式开启后生效）。 */
    @Scheduled(cron = "${oddsmaker.retention.cron:0 40 * * * *}")
    public void scheduledEnforce() {
        if (!enabled) {
            return;
        }
        enforceNow();
    }

    /**
     * 手动/定时共用的对账主链路：CH 未配置时空转返回空列表。
     * 期望缺失 → 全表 SKIPPED_NO_CONFIG；逐表 try-catch 隔离（单表失败不影响其余）。
     */
    public List<RetentionEnforcementEntity> enforceNow() {
        if (!clickHouse.isAvailable()) {
            return List.of();
        }
        Integer expected = resolveExpectedDays();
        Map<String, String> tables = loadEngineFulls();
        LocalDateTime now = LocalDateTime.now();
        List<RetentionEnforcementEntity> results = new ArrayList<>();
        List<String> altered = new ArrayList<>();

        for (Map.Entry<String, String> managed : TABLE_TTL_COLUMNS.entrySet()) {
            String table = managed.getKey();
            String column = managed.getValue();
            RetentionEnforcementEntity existing = enforcementRepo.findById(table).orElse(null);
            RetentionEnforcementEntity state = new RetentionEnforcementEntity();
            state.chTable = table;
            state.ttlColumn = column;
            state.desiredDays = expected;
            state.lastCheckedAt = now;
            state.status = RetentionEnforcementEntity.Status.SKIPPED_NO_CONFIG;
            try {
                String engineFull = tables.get(table);
                if (engineFull == null) {
                    state.status = RetentionEnforcementEntity.Status.UNKNOWN;
                    state.errorMessage = "table not found in clickhouse";
                } else if (expected == null) {
                    // 无有效配置：保持现状，仅记录
                } else {
                    Integer actual = parseTtlDays(engineFull);
                    state.actualDays = actual;
                    if (actual != null && actual == expected) {
                        state.status = RetentionEnforcementEntity.Status.IN_SYNC;
                    } else if (actual == null && engineFull.contains("TTL")) {
                        // 有 TTL 子句但不符合预期格式：拒绝盲改，防每轮反复 ALTER
                        state.status = RetentionEnforcementEntity.Status.UNKNOWN;
                        state.errorMessage = "unrecognized TTL expression: " + engineFull;
                    } else {
                        alterTtl(table, column, expected);
                        state.status = RetentionEnforcementEntity.Status.UPDATING;
                        state.actualDays = expected;
                        state.lastUpdatedAt = now;
                        state.updateCount = (existing != null ? existing.updateCount : 0) + 1;
                        altered.add(table);
                    }
                }
            } catch (Exception ex) {
                state.status = RetentionEnforcementEntity.Status.FAILED;
                state.errorMessage = ex.getMessage();
                logger.warn("retention enforcement for {} failed: {}", table, ex.getMessage());
            }
            results.add(upsert(state, existing));
        }

        if (!altered.isEmpty()) {
            auditLog.log(AuditLogEntity.AuditAction.UPDATE, "retention_enforcement", "clickhouse_ttl",
                    "ClickHouse TTL", altered.size() + " table(s) altered", AuditLogEntity.AuditResult.SUCCESS,
                    "scheduler", null, "desiredDays=" + expected, null, null,
                    Map.of("alteredTables", String.join(",", altered), "desiredDays", String.valueOf(expected)));
            logger.info("retention enforcement: {} table(s) altered to {} days", altered.size(), expected);
        }
        return results;
    }

    /** ALTER TABLE ... MODIFY TTL（metadata 操作立即返回；白名单表名/列名无注入面）。 */
    private void alterTtl(String table, String column, int days) {
        clickHouse.execute(conn -> conn.createStatement().execute(
                "ALTER TABLE `" + table + "` MODIFY TTL `" + column + "` + INTERVAL " + days + " DAY"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadEngineFulls() {
        List<Map<String, Object>> rows = clickHouse.query(
                "SELECT table, engine_full FROM system.tables WHERE database = currentDatabase()");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object table = row.get("table");
            Object engineFull = row.get("engine_full");
            if (table != null && engineFull != null) {
                out.put(table.toString(), engineFull.toString());
            }
        }
        return out;
    }

    private RetentionEnforcementEntity upsert(RetentionEnforcementEntity state,
                                              RetentionEnforcementEntity existing) {
        if (existing != null) {
            // 非 UPDATING 轮次保留历史累计修正次数（UPDATING 已在 state 上 +1）
            if (state.status != RetentionEnforcementEntity.Status.UPDATING) {
                state.updateCount = existing.updateCount;
            }
            existing.ttlColumn = state.ttlColumn;
            existing.actualDays = state.actualDays;
            existing.desiredDays = state.desiredDays;
            existing.status = state.status;
            existing.lastCheckedAt = state.lastCheckedAt;
            existing.lastUpdatedAt = state.lastUpdatedAt;
            existing.errorMessage = state.errorMessage;
            existing.updateCount = state.updateCount;
            return enforcementRepo.save(existing);
        }
        return enforcementRepo.save(state);
    }
}
