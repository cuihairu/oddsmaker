package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.exception.BusinessException;
import io.oddsmaker.control.jpa.SegmentEntity;
import io.oddsmaker.control.jpa.SegmentRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 可复用用户分群（P7-2，竞品差距收敛）。
 *
 * 定义一次、处处可用：定义 JSON（match + conditions）编译为 ClickHouse 查询，
 * 物化到 segment_members 表；报表（在线/留存/财务…）以 subject_id 过滤注入。
 *
 * 主体口径与平台一致（OnlineMetricsService.SUBJECT）：player 优先、device 兜底。
 * 安全：属性列白名单、值全部参数化（不拼接用户输入）。
 */
@Service
public class SegmentService {

    private static final Logger logger = LoggerFactory.getLogger(SegmentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** attribute 条件允许引用的 events 列白名单（标识符直拼，必须是硬编码列名） */
    private static final Set<String> ATTRIBUTE_FIELDS = Set.of(
            "platform", "country", "app_version", "sdk_version", "server_id", "game_mode");

    private static final int DEFAULT_WINDOW_DAYS = 90;
    private static final int MAX_WINDOW_DAYS = 365;
    private static final int MAX_CONDITIONS = 20;

    /** 分群主体表达式：与 OnlineMetricsService.SUBJECT 保持一致 */
    static final String SUBJECT_PLAYER =
            "if(player_id != '', player_id, if(user_id != '', user_id, device_id))";
    static final String SUBJECT_DEVICE = "device_id";

    private final SegmentRepo repo;
    private final ClickHouseClient ch;

    public SegmentService(SegmentRepo repo, ClickHouseClient ch) {
        this.repo = repo;
        this.ch = ch;
    }

    // ---------------- 定义模型 ----------------

    public static class Definition {
        public String match = "all";            // all=AND / any=OR
        public Integer withinDays;             // 候选主体基础窗口（近 N 天有事件）
        public List<Condition> conditions;
    }

    public static class Condition {
        public String kind;                     // attribute | event | event_absent
        public String field;                    // attribute：白名单列
        public String op;                       // attribute: eq|neq|in ；event: gte|lte
        public Object value;                    // attribute: String 或 List<String>
        public String eventName;                // event*：事件名
        public Integer count;                   // event：次数阈值
        public Integer withinDays;              // event*：覆盖窗口，缺省用基础窗口
    }

    /** 编译产物：WHERE 片段参数化 SQL + 依序参数 */
    static class Compiled {
        final String whereFragment;
        final List<Object> args;
        final int maxWindowDays;

        Compiled(String whereFragment, List<Object> args, int maxWindowDays) {
            this.whereFragment = whereFragment;
            this.args = args;
            this.maxWindowDays = maxWindowDays;
        }
    }

    // ---------------- CRUD ----------------

    public SegmentEntity create(String gameId, String name, String displayName, String description,
                                String environment, String subject, String definitionJson) {
        validateName(gameId, name);
        Definition def = parseAndValidate(definitionJson);
        repo.findByGameIdAndNameAndDeletedAtIsNull(gameId, name).ifPresent(s -> {
            throw new BusinessException("SEGMENT_NAME_EXISTS: " + "同名分群已存在: " + name);
        });
        SegmentEntity entity = new SegmentEntity();
        entity.gameId = gameId;
        entity.name = name;
        entity.displayName = displayName;
        entity.description = description;
        entity.environment = normalizeEnv(environment);
        entity.subject = parseSubject(subject);
        entity.definition = canonicalJson(def);
        return repo.save(entity);
    }

    public SegmentEntity update(String segmentId, String displayName, String description,
                                String status, String definitionJson) {
        SegmentEntity entity = get(segmentId);
        if (displayName != null) entity.displayName = displayName;
        if (description != null) entity.description = description;
        if (status != null) {
            SegmentEntity.SegmentStatus st = parseStatus(status);
            entity.status = st;
        }
        if (definitionJson != null) {
            Definition def = parseAndValidate(definitionJson);
            entity.definition = canonicalJson(def);
        }
        entity.updatedAt = java.time.LocalDateTime.now();
        return repo.save(entity);
    }

    public boolean delete(String segmentId) {
        SegmentEntity entity = repo.findById(segmentId).orElse(null);
        if (entity == null || entity.deletedAt != null) return false;
        entity.deletedAt = java.time.LocalDateTime.now();
        entity.status = SegmentEntity.SegmentStatus.INACTIVE;
        repo.save(entity);
        return true;
    }

    public SegmentEntity get(String segmentId) {
        return repo.findByIdAndDeletedAtIsNull(segmentId)
                .orElseThrow(() -> new BusinessException("SEGMENT_NOT_FOUND: " + "分群不存在: " + segmentId));
    }

    public List<SegmentEntity> listByGame(String gameId) {
        return repo.findByGameIdAndDeletedAtIsNullOrderByNameAsc(gameId);
    }

    /**
     * 报表侧分群过滤片段：两个占位参数依序为 (segment_id, game_id)，含前置 AND。
     * 调用方把参数附加在自身参数之后即可。
     */
    public static String segmentFilterFragment(String subjectExpr) {
        return " AND " + subjectExpr + " IN (SELECT subject_id FROM segment_members"
                + " WHERE segment_id = ? AND game_id = ?)";
    }

    // ---------------- 物化 ----------------

    /**
     * 物化分群成员到 ClickHouse segment_members：
     * 先清旧成员（ALTER DELETE mutation），再 INSERT 新集合，回填 memberCount/lastComputedAt。
     */
    public Map<String, Object> compute(String segmentId) {
        SegmentEntity entity = get(segmentId);
        if (!ch.isAvailable()) {
            throw new BusinessException("CLICKHOUSE_UNAVAILABLE: " + "ClickHouse 未配置，无法物化分群");
        }
        Definition def = parseAndValidate(entity.definition);
        Compiled compiled = compile(def, entity.subject);
        String subjectExpr = subjectExpr(entity.subject);

        ch.update("ALTER TABLE segment_members DELETE WHERE segment_id = ?", segmentId);

        StringBuilder sql = new StringBuilder(
                "INSERT INTO segment_members (segment_id, game_id, environment, subject_id, computed_at) "
                + "SELECT DISTINCT ?, game_id, environment, ");
        sql.append(subjectExpr).append(", now64(3) FROM events")
           .append(" WHERE game_id = ? AND environment = ?");
        List<Object> args = new ArrayList<>();
        args.add(entity.id);
        args.add(entity.gameId);
        args.add(entity.environment);
        args.add(entity.gameId);
        args.add(entity.environment);
        sql.append(" AND event_date >= today() - ? AND ts_server >= now() - INTERVAL ? DAY");
        args.add(compiled.maxWindowDays);
        args.add(compiled.maxWindowDays);
        sql.append(" AND (").append(compiled.whereFragment).append(")");
        args.addAll(compiled.args);
        ch.update(sql.toString(), args.toArray());

        long count = countMembers(entity);
        entity.memberCount = count;
        entity.lastComputedAt = java.time.LocalDateTime.now();
        repo.save(entity);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("segmentId", entity.id);
        resp.put("memberCount", count);
        resp.put("computedAt", entity.lastComputedAt.toString());
        logger.info("Segment {} materialized: {} members", entity.id, count);
        return resp;
    }

    public long countMembers(SegmentEntity entity) {
        if (!ch.isAvailable()) return 0;
        List<Map<String, Object>> rows = ch.query(
                "SELECT uniqExact(subject_id) AS c FROM segment_members"
                        + " WHERE segment_id = ? AND game_id = ? AND environment = ?",
                entity.id, entity.gameId, entity.environment);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("c")).longValue();
    }

    public List<String> members(String segmentId, int limit) {
        SegmentEntity entity = get(segmentId);
        if (!ch.isAvailable()) return List.of();
        int capped = Math.min(Math.max(limit, 1), 1000);
        List<Map<String, Object>> rows = ch.query(
                "SELECT subject_id FROM segment_members"
                        + " WHERE segment_id = ? AND game_id = ? AND environment = ?"
                        + " ORDER BY subject_id LIMIT " + capped,
                entity.id, entity.gameId, entity.environment);
        return rows.stream().map(r -> String.valueOf(r.get("subject_id"))).toList();
    }

    // ---------------- 定义解析与编译 ----------------

    Definition parseAndValidate(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank()) {
            throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "分群定义不能为空");
        }
        Definition def;
        try {
            def = JSON.readValue(definitionJson, Definition.class);
        } catch (Exception e) {
            throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "分群定义不是合法 JSON: " + e.getMessage());
        }
        if (def.conditions == null || def.conditions.isEmpty()) {
            throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "至少需要一条条件");
        }
        if (def.conditions.size() > MAX_CONDITIONS) {
            throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "条件数超过上限 " + MAX_CONDITIONS);
        }
        if (def.match == null || (!def.match.equalsIgnoreCase("all") && !def.match.equalsIgnoreCase("any"))) {
            def.match = "all";
        }
        for (Condition c : def.conditions) {
            validateCondition(c);
        }
        return def;
    }

    private void validateCondition(Condition c) {
        if (c.kind == null) {
            throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "条件缺少 kind");
        }
        switch (c.kind) {
            case "attribute" -> {
                if (c.field == null || !ATTRIBUTE_FIELDS.contains(c.field)) {
                    throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "属性条件字段不在白名单: " + c.field);
                }
                if (c.op == null || !Set.of("eq", "neq", "in").contains(c.op)) {
                    throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "属性条件 op 仅支持 eq/neq/in: " + c.op);
                }
                if ("in".equals(c.op)) {
                    if (!(c.value instanceof List<?> list) || list.isEmpty()
                            || list.stream().anyMatch(v -> !(v instanceof String))) {
                        throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "in 条件 value 必须为非空字符串数组");
                    }
                } else if (!(c.value instanceof String)) {
                    throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "eq/neq 条件 value 必须为字符串");
                }
            }
            case "event" -> {
                if (isBlank(c.eventName)) {
                    throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "事件条件缺少 event_name");
                }
                if (c.count == null || c.count < 1) {
                    throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "事件条件 count 需 >= 1");
                }
                if (c.op == null) c.op = "gte";
                if (!Set.of("gte", "lte").contains(c.op)) {
                    throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "事件条件 op 仅支持 gte/lte");
                }
            }
            case "event_absent" -> {
                if (isBlank(c.eventName)) {
                    throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "事件缺失条件缺少 event_name");
                }
                if (c.withinDays == null || c.withinDays < 1) {
                    throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "事件缺失条件需指定 within_days >= 1");
                }
            }
            default -> throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "未知条件类型: " + c.kind);
        }
        if (c.withinDays != null && (c.withinDays < 1 || c.withinDays > MAX_WINDOW_DAYS)) {
            throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "within_days 需在 1.." + MAX_WINDOW_DAYS);
        }
    }

    /**
     * 把定义编译为参数化 WHERE 片段。事件条件编译为主体子查询（按主体聚合计数后按阈值筛选）。
     */
    Compiled compile(Definition def, SegmentEntity.SegmentSubject subject) {
        String subjectExpr = subjectExpr(subject);
        List<String> fragments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        int maxWindow = def.withinDays == null ? DEFAULT_WINDOW_DAYS : clampWindow(def.withinDays);

        for (Condition c : def.conditions) {
            int window = c.withinDays == null ? maxWindow : clampWindow(c.withinDays);
            maxWindow = Math.max(maxWindow, window);
            switch (c.kind) {
                case "attribute" -> {
                    String col = c.field;
                    switch (c.op) {
                        case "eq" -> { fragments.add(col + " = ?"); args.add(c.value); }
                        case "neq" -> { fragments.add(col + " != ?"); args.add(c.value); }
                        case "in" -> {
                            List<?> values = (List<?>) c.value;
                            String marks = String.join(",", values.stream().map(v -> "?").toList());
                            fragments.add(col + " IN (" + marks + ")");
                            values.forEach(args::add);
                        }
                        default -> throw new IllegalStateException("unreachable");
                    }
                }
                case "event" -> {
                    // 主体近 window 天内该事件次数（>= / <= 阈值）
                    // null 视作 gte（与 validateCondition 缺省一致）
                    String cmp = "lte".equals(c.op) ? "<=" : ">=";
                    fragments.add(subjectExpr + " IN (SELECT s FROM (SELECT " + subjectExpr
                            + " AS s, count() AS c FROM events WHERE event_name = ?"
                            + " AND ts_server >= now() - INTERVAL ? DAY GROUP BY s HAVING c " + cmp + " ?))");
                    args.add(c.eventName);
                    args.add(window);
                    args.add(c.count);
                }
                case "event_absent" -> {
                    // 主体近 window 天内活跃、且该事件发生次数为 0
                    fragments.add(subjectExpr + " IN (SELECT s FROM (SELECT " + subjectExpr
                            + " AS s FROM events WHERE ts_server >= now() - INTERVAL ? DAY"
                            + " GROUP BY s HAVING countIf(event_name = ?) = 0))");
                    args.add(window);
                    args.add(c.eventName);
                }
                default -> throw new IllegalStateException("unreachable");
            }
        }
        String joiner = "all".equalsIgnoreCase(def.match) ? " AND " : " OR ";
        return new Compiled(String.join(joiner, fragments), args, maxWindow);
    }

    // ---------------- 辅助 ----------------

    static String subjectExpr(SegmentEntity.SegmentSubject subject) {
        return subject == SegmentEntity.SegmentSubject.DEVICE ? SUBJECT_DEVICE : SUBJECT_PLAYER;
    }

    private String canonicalJson(Definition def) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(def);
        } catch (Exception e) {
            throw new BusinessException("SEGMENT_DEFINITION_INVALID: " + "定义序列化失败");
        }
    }

    private void validateName(String gameId, String name) {
        if (isBlank(name) || name.length() > 100) {
            throw new BusinessException("SEGMENT_NAME_INVALID: " + "分群名需为 1..100 字符");
        }
        if (!name.matches("[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+")) {
            throw new BusinessException("SEGMENT_NAME_INVALID: " + "分群名仅允许字母/数字/下划线/连字符/中文");
        }
    }

    private String normalizeEnv(String environment) {
        if (isBlank(environment)) {
            throw new BusinessException("SEGMENT_ENV_INVALID: " + "必须指定物化环境");
        }
        return environment.trim().toLowerCase(Locale.ROOT);
    }

    private SegmentEntity.SegmentSubject parseSubject(String subject) {
        if (subject == null || subject.isBlank() || "player".equalsIgnoreCase(subject)) {
            return SegmentEntity.SegmentSubject.PLAYER;
        }
        if ("device".equalsIgnoreCase(subject)) {
            return SegmentEntity.SegmentSubject.DEVICE;
        }
        throw new BusinessException("SEGMENT_SUBJECT_INVALID: " + "主体仅支持 player/device");
    }

    private SegmentEntity.SegmentStatus parseStatus(String status) {
        try {
            return SegmentEntity.SegmentStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("SEGMENT_STATUS_INVALID: " + "状态仅支持 ACTIVE/INACTIVE");
        }
    }

    static int clampWindow(int days) {
        return Math.min(Math.max(days, 1), MAX_WINDOW_DAYS);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
