package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 业务告警邮件通道配置（调研「通知通道三件套收敛」落点，webhook 之外的第二通道）
 * 每游戏一条（environment_id 为空 = 全部环境）；recipients 逗号/分号/空白分隔。
 * SMTP 未配置时投递跳过（fail-open，告警链路不因邮件失败受阻）。
 */
@Entity
@Table(name = "alert_email_configs")
public class AlertEmailConfigEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;  // 为空 = 全部环境

    @Column(nullable = false, columnDefinition = "TEXT")
    public String recipients;  // 收件人：逗号/分号/空白分隔

    @Column(nullable = false)
    public boolean enabled = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    /** 解析收件人为列表（逗号/分号/空白分隔，去空项） */
    public List<String> recipientList() {
        if (recipients == null || recipients.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : recipients.split("[,;\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
