-- Export Jobs Table
-- Phase 5: Data Export - User data export functionality

-- Export Jobs table
CREATE TABLE export_jobs (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    user_id VARCHAR(64),
    export_type VARCHAR(50),
    data_source TEXT,
    filters TEXT,
    columns TEXT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    export_format VARCHAR(20) DEFAULT 'csv',
    file_name VARCHAR(255),
    compression VARCHAR(20),
    export_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    status_message VARCHAR(500),
    error_message TEXT,
    total_rows BIGINT,
    exported_rows BIGINT,
    progress_percent INTEGER,
    file_path VARCHAR(500),
    file_size_bytes BIGINT,
    download_url VARCHAR(500),
    expires_at TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    execution_time_ms BIGINT,
    notify_on_complete BOOLEAN DEFAULT FALSE,
    notification_email VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

-- Indexes for efficient lookups
CREATE INDEX idx_export_jobs_game_id ON export_jobs(game_id);
CREATE INDEX idx_export_jobs_environment_id ON export_jobs(environment_id);
CREATE INDEX idx_export_jobs_user_id ON export_jobs(user_id);
CREATE INDEX idx_export_jobs_export_status ON export_jobs(export_status);
CREATE INDEX idx_export_jobs_export_type ON export_jobs(export_type);
CREATE INDEX idx_export_jobs_created_at ON export_jobs(created_at DESC);
CREATE INDEX idx_export_jobs_completed_at ON export_jobs(completed_at DESC);
CREATE INDEX idx_export_jobs_expires_at ON export_jobs(expires_at);

-- Index for pending jobs
CREATE INDEX idx_export_jobs_pending ON export_jobs(export_status) WHERE export_status = 'PENDING';

-- Index for processing jobs
CREATE INDEX idx_export_jobs_processing ON export_jobs(export_status) WHERE export_status = 'PROCESSING';

-- Index for completed jobs
CREATE INDEX idx_export_jobs_completed ON export_jobs(export_status) WHERE export_status = 'COMPLETED';

-- Insert example export jobs
