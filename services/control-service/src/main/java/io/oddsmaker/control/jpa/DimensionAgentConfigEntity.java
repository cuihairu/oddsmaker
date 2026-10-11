package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Sync Agent 属性面的 Control 托管档（dimension-sync.md「Agent 侧 agent.properties 的 Control
 * 下发迁移」）。每个 (game, environment, agentKey) 一条：props 存非密钥属性文本（Properties 格式，
 * dot-key 沿用 agent.properties 命名），密钥键整体 AES-GCM 密文托管；下发时注入 game.id /
 * game.environment / status.source-key，agent.checkpoint-path 永远本地保留。运行态观测走
 * dimension_sync_status（source_key=agent_key），本表只管配置。
 */
@Entity
@Table(name = "dimension_agent_config")
public class DimensionAgentConfigEntity {

    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String environment;

    /** Agent 标识：同游戏同环境下区分多套部署；下发时注入 status.source-key（上报行标识） */
    @Column(name = "agent_key", nullable = false, length = 100)
    public String agentKey;

    @Column(nullable = false)
    public Boolean enabled = true;

    /** 非密钥属性文本（java.util.Properties store 规范化输出，键白名单见 AgentConfigSpec） */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String props;

    /** 密钥键 JSON 整体 AES-GCM 密文（base64(iv+ciphertext)）；任何管理面 API 响应不回显 */
    @Column(name = "secrets_encrypted", nullable = false, columnDefinition = "TEXT")
    public String secretsEncrypted;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(nullable = false)
    public LocalDateTime updatedAt;
}
