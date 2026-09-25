package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 自定义仪表盘实体（P7-3 仪表盘 widget 化）。
 *
 * 布局（layout JSON：widgets[{type, source, title, span, params}]）存 Postgres；
 * widget 数据不落库——前端按 source 白名单调用既有报表 API 拉取。
 */
@Entity
@Table(name = "dashboards", indexes = @Index(name = "idx_dashboards_game", columnList = "game_id"))
public class DashboardEntity {

    public enum DashboardStatus {
        ACTIVE,    // 出现在仪表盘入口
        INACTIVE   // 保留布局但不在入口展示
    }

    @Id
    @Column(length = 64)
    public String id;

    @jakarta.persistence.PrePersist
    void ensureId() {
        if (id == null || id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(length = 1000)
    public String description;

    /** DashboardService 校验并规范化的布局 JSON（widgets 数组） */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String layout;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public DashboardStatus status = DashboardStatus.ACTIVE;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;
}
