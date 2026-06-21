-- Symbol Mapping Management (P4.3 符号化服务元数据)
-- 管理 dSYM / Proguard / source map 文件元数据

CREATE TABLE symbol_mappings (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    app_version VARCHAR(50) NOT NULL,
    build_version VARCHAR(50),
    file_type VARCHAR(20) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT,
    file_checksum VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    uploaded_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_symbol_mappings_game_id ON symbol_mappings(game_id);
CREATE INDEX idx_symbol_mappings_lookup ON symbol_mappings(game_id, platform, app_version, status);
