-- 异步任务表（支持多实例共享任务状态）
CREATE TABLE IF NOT EXISTS mr_async_job (
    job_id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    engine_code VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    started_at BIGINT,
    finished_at BIGINT,
    elapsed_ms BIGINT,
    success_flag SMALLINT,
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    result_json TEXT,
    idempotency_key VARCHAR(128),
    trace_id VARCHAR(128),
    client_id VARCHAR(128),
    user_id VARCHAR(128),
    user_name VARCHAR(128),
    source_system VARCHAR(128),
    cancel_requested SMALLINT NOT NULL DEFAULT 0,
    owner_node VARCHAR(128),
    updated_at BIGINT NOT NULL
);

-- 幂等索引（同一个幂等键只允许一个任务）
CREATE UNIQUE INDEX uk_mr_async_job_idem ON mr_async_job(idempotency_key);

-- 状态索引（便于状态查询和历史清理）
CREATE INDEX idx_mr_async_job_status ON mr_async_job(status);
