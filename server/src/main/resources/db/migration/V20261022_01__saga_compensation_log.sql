-- Manual migration: this project does not currently run Flyway migrations automatically.
CREATE TABLE IF NOT EXISTS saga_compensation_log (
    id CHAR(64) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(160) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    task_id VARCHAR(160) NOT NULL,
    checkpoint_id VARCHAR(80) NOT NULL,
    step_id VARCHAR(80) NOT NULL,
    tool_id VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    attempt INT NOT NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(80) NULL,
    original_params LONGTEXT NOT NULL,
    forward_result LONGTEXT NOT NULL,
    compensation_result LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_saga_execution (tenant_id, checkpoint_id),
    KEY idx_saga_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
