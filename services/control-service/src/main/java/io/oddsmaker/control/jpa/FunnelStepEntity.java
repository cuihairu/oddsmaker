package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * 漏斗步骤实体
 * 定义漏斗的每个步骤
 */
@Entity
@Table(name = "funnel_steps")
public class FunnelStepEntity {

    /**
     * 主键：迁移中为 varchar(32)（非自增），@PrePersist 生成 32 位无横杠 UUID 对齐。
     */
    @Id
    public String id;

    @PrePersist
    void ensureId() {
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "");
        }
    }

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funnel_id", nullable = false)
    public FunnelConfigEntity funnel;

    /**
     * 步骤顺序
     */
    @Column(name = "step_order", nullable = false)
    public Integer stepOrder;

    /**
     * 步骤名称
     */
    @Column(nullable = false, length = 100)
    public String name;

    /**
     * 步骤描述
     */
    @Column(length = 500)
    public String description;

    /**
     * 事件名称
     */
    @Column(name = "event_name", nullable = false, length = 100)
    public String eventName;

    /**
     * 事件过滤条件（JSON格式）
     */
    @Column(name = "event_filter", columnDefinition = "TEXT")
    public String eventFilter;

    /**
     * 时间窗口（秒），用于时间窗口漏斗的步骤间时间限制
     */
    @Column(name = "time_window_sec")
    public Long timeWindowSec;

    /**
     * 是否可选步骤
     */
    @Column(name = "optional")
    public Boolean optional = false;

    /**
     * 步骤图标
     */
    @Column(length = 50)
    public String icon;

    /**
     * 步骤颜色
     */
    @Column(length = 20)
    public String color;

    // 业务方法
    public boolean isOptional() {
        return optional != null && optional;
    }

    public boolean hasFilter() {
        return eventFilter != null && !eventFilter.isEmpty();
    }

    public boolean hasTimeWindow() {
        return timeWindowSec != null && timeWindowSec > 0;
    }
}