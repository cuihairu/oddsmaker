package io.oddsmaker.control.jpa;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * B10 数据质量计数（计划书 B10 行，设计定稿 07-b10 §4.1）。
 * 网关边缘 5 分钟窗口计数，POST /internal/data-quality 按唯一键整行覆盖；
 * 五项率读时计算，不落列。恒等式 received = accepted + Σrejected + sampled_out 由服务层校验。
 */
@Entity
@Table(name = "data_quality_metrics")
public class DataQualityMetricsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "game_id", length = 32)
    public String gameId;

    @Column(name = "environment", length = 100)
    public String environment;

    @Column(name = "window_start")
    public LocalDateTime windowStart;

    @Column(name = "window_sec")
    public int windowSec = 300;

    public long received;
    public long accepted;
    @Column(name = "sampled_out")
    public long sampledOut;
    @Column(name = "rejected_schema")
    public long rejectedSchema;
    @Column(name = "rejected_unknown_event")
    public long rejectedUnknownEvent;
    @Column(name = "rejected_invalid_timestamp")
    public long rejectedInvalidTimestamp;
    @Column(name = "rejected_pii_blocked")
    public long rejectedPiiBlocked;
    @Column(name = "rejected_payload_too_large")
    public long rejectedPayloadTooLarge;
    @Column(name = "rejected_trust_escalation")
    public long rejectedTrustEscalation;
    @Column(name = "rejected_blocked")
    public long rejectedBlocked;
    @Column(name = "rejected_scope_mismatch")
    public long rejectedScopeMismatch;
    @Column(name = "rejected_kafka_error")
    public long rejectedKafkaError;
    @Column(name = "duplicates_gateway")
    public long duplicatesGateway;
    @Column(name = "duplicates_enrich")
    public long duplicatesEnrich;
    public long late;
    @Column(name = "dlq_other")
    public long dlqOther;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;
}
