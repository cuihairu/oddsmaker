-- 业务告警邮件通道：webhook（notifyWebhook → webhook_logs）之外的第二通知通道
-- 每游戏一条配置（environment_id 为空 = 全部环境）；recipients 逗号/分号/空白分隔
-- SMTP 未配置（spring.mail.host 缺省）时投递跳过，告警链路不因邮件失败受阻

CREATE TABLE alert_email_configs (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    recipients TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_alert_email_configs_game UNIQUE (game_id)
);

CREATE INDEX idx_alert_email_configs_game ON alert_email_configs(game_id);
