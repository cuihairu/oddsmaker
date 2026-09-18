package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 玩家数据删除请求实体（GDPR erasure）：
 * 输入任一标识经身份图谱展开全集合，异步执行 PG 硬删 + ClickHouse mutation；
 * 风控处置记录仅匿名化占位（erased:&lt;reqId&gt;）保留防欺诈审计线索。
 */
@Entity
@Table(name = "player_erasure_requests")
public class PlayerErasureRequestEntity {

    /** 输入标识类型（identity_links.linkedIdentityType 的取值空间） */
    public enum RequestType {
        PLAYER_ID, USER_ID, DEVICE_ID
    }

    /** 请求状态：PARTIAL = PG 段已提交、CH 段待重试 */
    public enum Status {
        PENDING, PROCESSING, COMPLETED, PARTIAL, FAILED, CANCELLED
    }

    @Id
    @Column(length = 48)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public Status status = Status.PENDING;

    @Column(name = "request_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public RequestType requestType;

    @Column(name = "request_value", nullable = false, length = 200)
    public String requestValue;

    /** JSON：展开后全量标识集合（创建时固化） */
    @Column(name = "resolved_identities", columnDefinition = "TEXT")
    public String resolvedIdentities;

    /** JSON：pgDone/pg 各表行数/ch 状态与逐表确认/retries */
    @Column(name = "execution_summary", columnDefinition = "TEXT")
    public String executionSummary;

    /** null = 立即执行（sweep 只处理到期的） */
    @Column(name = "scheduled_for")
    public LocalDateTime scheduledFor;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "requested_by", length = 64)
    public String requestedBy;

    @Column(name = "cancelled_by", length = 64)
    public String cancelledBy;

    @Column(name = "cancelled_at")
    public LocalDateTime cancelledAt;

    /** 僵尸 PROCESSING 重置依据 */
    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;
}
