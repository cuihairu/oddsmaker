package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 维度同步 HTTP Pull 配置（dimension-sync.md HTTP Pull 链路）。
 * 每个 (game, environment, sourceKey) 一条：Control scheduler 定期拉游戏方查询接口
 * （GET {endpoint}?updated_after=&limit=，Bearer 凭证 AES-GCM 加密托管），翻译成
 * RawDimensionChange(source_type=pull) 走既有 /v1/batch 事件入口进 item_dim/level_dim。
 * 断点续传：cursor = 服务端 next_cursor（缺省回落本页最大 version_ts），推送成功才前进；
 * 下游 ReplacingMergeTree(version_ts) 重放幂等。
 */
@Entity
@Table(name = "dimension_pull_config")
public class DimensionPullConfigEntity {

    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String environment;

    /** 源标识：同游戏同环境下区分 item / level 等维度源 */
    @Column(name = "source_key", nullable = false, length = 100)
    public String sourceKey;

    /** 维度类型：item / level（其余值原样透传，由下游归一化） */
    @Column(name = "dim_type", nullable = false, length = 20)
    public String dimType = "item";

    /** 游戏方查询接口（必须 http/https） */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String endpoint;

    /** Bearer 凭证 AES-GCM 密文（base64(iv+ciphertext)）；任何 API 响应不回显 */
    @Column(name = "credential_encrypted", nullable = false, columnDefinition = "TEXT")
    public String credentialEncrypted;

    @Column(nullable = false)
    public Boolean enabled = true;

    /** 拉取间隔秒（scheduler tick 15s，单页有界延迟，续页下一 tick 继续） */
    @Column(name = "interval_seconds", nullable = false)
    public Integer intervalSeconds = 300;

    /** 单页条数上限 */
    @Column(name = "page_limit", nullable = false)
    public Integer pageLimit = 1000;

    /** 断点：服务端 next_cursor（缺省回落本页最大 version_ts） */
    @Column(name = "last_cursor", columnDefinition = "TEXT")
    public String lastCursor;

    @Column(name = "last_pull_at")
    public LocalDateTime lastPullAt;

    /** 源头最新一条变更的版本时间（version_ts，用于判断源头新鲜度） */
    @Column(name = "last_event_ts")
    public LocalDateTime lastEventTs;

    @Column(name = "pushed_count", nullable = false)
    public Long pushedCount = 0L;

    @Column(name = "error_count", nullable = false)
    public Long errorCount = 0L;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(nullable = false)
    public LocalDateTime updatedAt;
}
