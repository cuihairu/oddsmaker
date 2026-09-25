package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.exception.BusinessException;
import io.oddsmaker.control.jpa.DashboardEntity;
import io.oddsmaker.control.jpa.DashboardRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自定义仪表盘（P7-3 仪表盘 widget 化，竞品差距收敛）。
 *
 * 布局 JSON（widgets）校验并规范化后存 Postgres；widget 数据由前端按
 * source 白名单调用既有报表 API 拉取——服务端不代理数据，只治理布局。
 *
 * 安全：source/type 枚举白名单（前端据 source 映射到固定 API），
 * params 仅放行数值钳制过的查询参数，不承载任何自由 SQL。
 */
@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** widget 展示类型（前端渲染器一一对应） */
    static final Set<String> WIDGET_TYPES = Set.of("kpi", "line", "bar", "table");

    /** widget 数据源白名单：前端映射到既有报表 API，新增数据源需同步两端 */
    static final Set<String> WIDGET_SOURCES = Set.of(
            "online-overview",   // /api/online-metrics/{gameId}
            "retention-trend",   // /api/retention-metrics/{gameId}/trend
            "payment-funnel",    // /api/payment-metrics/{gameId}/funnel
            "crash-trend"        // /api/crash-metrics/{gameId}/trend
    );

    /** params 放行的键 → 钳制规则（环境字符串另行枚举校验） */
    private static final Set<String> ENVIRONMENTS = Set.of("dev", "staging", "prod");
    private static final int MAX_WIDGETS = 30;
    private static final int DEFAULT_SPAN = 6;

    private final DashboardRepo repo;

    public DashboardService(DashboardRepo repo) {
        this.repo = repo;
    }

    // ---------------- 布局模型 ----------------

    public static class Layout {
        public List<Widget> widgets;
    }

    public static class Widget {
        public String id;         // 前端生成，保存后原样回读
        public String type;       // kpi | line | bar | table
        public String source;     // WIDGET_SOURCES 白名单
        public String title;      // 缺省用 source 标签
        public Integer span;      // 12 栅格宽度，缺省 6，钳制 1..12
        public Map<String, Object> params;
    }

    // ---------------- CRUD ----------------

    public DashboardEntity create(String gameId, String name, String description, String layoutJson) {
        validateName(gameId, name);
        Layout layout = parseAndValidate(layoutJson);
        repo.findByGameIdAndNameAndDeletedAtIsNull(gameId, name).ifPresent(d -> {
            throw new BusinessException("DASHBOARD_NAME_EXISTS: " + "同名仪表盘已存在: " + name);
        });
        DashboardEntity entity = new DashboardEntity();
        entity.gameId = gameId;
        entity.name = name;
        entity.description = description;
        entity.layout = canonicalJson(layout);
        return repo.save(entity);
    }

    public DashboardEntity update(String dashboardId, String description, String status, String layoutJson) {
        DashboardEntity entity = get(dashboardId);
        if (description != null) entity.description = description;
        if (status != null) {
            try {
                entity.status = DashboardEntity.DashboardStatus.valueOf(status.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_STATUS: " + "非法状态: " + status);
            }
        }
        if (layoutJson != null) {
            entity.layout = canonicalJson(parseAndValidate(layoutJson));
        }
        entity.updatedAt = LocalDateTime.now();
        return repo.save(entity);
    }

    public DashboardEntity get(String dashboardId) {
        return repo.findByIdAndDeletedAtIsNull(dashboardId)
                .orElseThrow(() -> new BusinessException("DASHBOARD_NOT_FOUND: " + "仪表盘不存在: " + dashboardId));
    }

    public List<DashboardEntity> listByGame(String gameId) {
        return repo.findByGameIdAndDeletedAtIsNullOrderByNameAsc(gameId);
    }

    public boolean delete(String dashboardId) {
        DashboardEntity entity = repo.findById(dashboardId).orElse(null);
        if (entity == null || entity.deletedAt != null) return false;
        entity.deletedAt = LocalDateTime.now();
        entity.status = DashboardEntity.DashboardStatus.INACTIVE;
        repo.save(entity);
        logger.info("Dashboard soft-deleted: id={} game={}", dashboardId, entity.gameId);
        return true;
    }

    // ---------------- 布局校验与规范化 ----------------

    private void validateName(String gameId, String name) {
        if (gameId == null || gameId.isBlank()) {
            throw new BusinessException("INVALID_GAME: " + "缺少游戏 ID");
        }
        if (name == null || name.isBlank() || name.length() > 100
                || !name.matches("[\\w\\-\\u4e00-\\u9fa5]+")) {
            throw new BusinessException("INVALID_NAME: " + "名称仅允许中英文/数字/下划线/连字符: " + name);
        }
    }

    Layout parseAndValidate(String layoutJson) {
        Layout layout;
        try {
            layout = JSON.readValue(layoutJson == null ? "" : layoutJson, Layout.class);
        } catch (Exception e) {
            throw new BusinessException("INVALID_LAYOUT: " + "布局不是合法 JSON");
        }
        if (layout == null || layout.widgets == null || layout.widgets.isEmpty()) {
            throw new BusinessException("EMPTY_WIDGETS: " + "至少需要一个 widget");
        }
        if (layout.widgets.size() > MAX_WIDGETS) {
            throw new BusinessException("TOO_MANY_WIDGETS: " + "widget 数上限 " + MAX_WIDGETS);
        }
        for (Widget w : layout.widgets) {
            validateWidget(w);
        }
        return layout;
    }

    private void validateWidget(Widget w) {
        String at = "widget[" + (w == null ? "null" : w.id) + "]";
        if (w == null || w.type == null || !WIDGET_TYPES.contains(w.type)) {
            throw new BusinessException("INVALID_WIDGET_TYPE: " + at + " 类型必须是 " + WIDGET_TYPES);
        }
        if (w.source == null || !WIDGET_SOURCES.contains(w.source)) {
            throw new BusinessException("INVALID_WIDGET_SOURCE: " + at + " 数据源不在白名单");
        }
        if (w.title != null && w.title.length() > 100) {
            throw new BusinessException("INVALID_WIDGET_TITLE: " + at + " 标题过长");
        }
        if (w.params != null) {
            validateParams(w.params, at);
        }
    }

    private void validateParams(Map<String, Object> params, String at) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            switch (key) {
                case "environment" -> {
                    if (value != null && !ENVIRONMENTS.contains(String.valueOf(value))) {
                        throw new BusinessException("INVALID_PARAM: " + at + " environment 非法");
                    }
                }
                case "minutes" -> {
                    int v = asInt(value, at, "minutes");
                    if (v < 1 || v > 60) {
                        throw new BusinessException("INVALID_PARAM: " + at + " minutes 需在 1..60");
                    }
                }
                case "days" -> {
                    int v = asInt(value, at, "days");
                    if (v < 1 || v > 365) {
                        throw new BusinessException("INVALID_PARAM: " + at + " days 需在 1..365");
                    }
                }
                case "granularity" -> {
                    if (value != null && !Set.of("day", "week").contains(String.valueOf(value))) {
                        throw new BusinessException("INVALID_PARAM: " + at + " granularity 非法");
                    }
                }
                case "limit" -> {
                    int v = asInt(value, at, "limit");
                    if (v < 1 || v > 50) {
                        throw new BusinessException("INVALID_PARAM: " + at + " limit 需在 1..50");
                    }
                }
                default -> throw new BusinessException("INVALID_PARAM: " + at + " 不支持的参数: " + key);
            }
        }
    }

    private int asInt(Object value, String at, String key) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BusinessException("INVALID_PARAM: " + at + " " + key + " 不是整数");
        }
    }

    String canonicalJson(Layout layout) {
        // span 缺省补 6 并钳制；title 缺省补 source
        for (Widget w : layout.widgets) {
            if (w.span == null) w.span = DEFAULT_SPAN;
            w.span = Math.max(1, Math.min(12, w.span));
            if (w.title == null || w.title.isBlank()) w.title = w.source;
            if (w.params == null) w.params = new LinkedHashMap<>();
        }
        try {
            return JSON.writeValueAsString(layout);
        } catch (Exception e) {
            throw new BusinessException("LAYOUT_SERIALIZE_FAILED: " + "布局序列化失败");
        }
    }
}
