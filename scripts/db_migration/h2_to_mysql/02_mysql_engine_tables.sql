-- =========================================================
-- H2 -> MySQL 迁移：engine 输入/规则/任务/审计表
-- 迁移范围（不含 calendar，不含 output）：
-- MR_TRADE_INPUT
-- MR_MARKET_CURVE_INPUT
-- MR_RISKFACTOR_DATA
-- MR_SCENARIO_RULE
-- MR_AGG_RULE
-- MR_ASYNC_JOB
-- MR_ASYNC_BATCH_JOB
-- MR_ASYNC_BATCH_ITEM
-- MR_AUDIT_LOG
-- =========================================================

USE mr_engine;

CREATE TABLE IF NOT EXISTS MR_TRADE_INPUT (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_date DATE NOT NULL,
    trade_id VARCHAR(128) NOT NULL,
    product_type VARCHAR(64) NOT NULL,
    trade_content_text LONGTEXT NOT NULL,
    content_format VARCHAR(16) NOT NULL DEFAULT 'JSON',
    version_no INT NOT NULL DEFAULT 1,
    source_system VARCHAR(128),
    portfolio VARCHAR(128),
    desk VARCHAR(64),
    trader VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uk_MR_TRADE_INPUT UNIQUE (data_date, trade_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_MR_TRADE_INPUT_date ON MR_TRADE_INPUT (data_date);
CREATE INDEX idx_MR_TRADE_INPUT_product ON MR_TRADE_INPUT (product_type);

CREATE TABLE IF NOT EXISTS MR_MARKET_CURVE_INPUT (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_date DATE NOT NULL,
    market_data_type VARCHAR(64) NOT NULL,
    curve_id VARCHAR(128) NOT NULL,
    curve_content_text LONGTEXT NOT NULL,
    content_format VARCHAR(16) NOT NULL DEFAULT 'JSON',
    version_no INT NOT NULL DEFAULT 1,
    source_system VARCHAR(128),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uk_MR_MARKET_CURVE_INPUT UNIQUE (data_date, market_data_type, curve_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_MR_MARKET_CURVE_INPUT_date ON MR_MARKET_CURVE_INPUT (data_date);
CREATE INDEX idx_MR_MARKET_CURVE_INPUT_type ON MR_MARKET_CURVE_INPUT (market_data_type);
CREATE INDEX idx_MR_MARKET_CURVE_INPUT_curve ON MR_MARKET_CURVE_INPUT (curve_id);

CREATE TABLE IF NOT EXISTS MR_RISKFACTOR_DATA (
    data_date DATE NOT NULL,
    riskfactor_type VARCHAR(64) NOT NULL,
    riskfactor_id VARCHAR(128) NOT NULL,
    term_code VARCHAR(64),
    term_days INT,
    obs_date DATE NOT NULL,
    riskfactor_value DECIMAL(30, 12) NOT NULL,
    currency VARCHAR(32),
    source_system VARCHAR(128),
    version_no INT NOT NULL DEFAULT 1,
    modifier VARCHAR(128),
    updated_at BIGINT NOT NULL,
    CONSTRAINT pk_MR_RISKFACTOR_DATA PRIMARY KEY (
        data_date, riskfactor_type, riskfactor_id, obs_date, term_code
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_MR_RISKFACTOR_DATA_main
    ON MR_RISKFACTOR_DATA (data_date, riskfactor_type, riskfactor_id);

CREATE TABLE IF NOT EXISTS MR_SCENARIO_RULE (
    scenario_id VARCHAR(128) NOT NULL,
    line_no INT NOT NULL,
    scenario_name VARCHAR(256) NOT NULL,
    scenario_type VARCHAR(32) NOT NULL,
    curve_type VARCHAR(64),
    curve_code VARCHAR(128),
    term_code VARCHAR(64),
    term_days INT,
    scenario_no INT,
    increase_days INT,
    junp_day_no INT,
    shock_type VARCHAR(32),
    cal_start_date DATE,
    cal_end_date DATE,
    start_date DATE,
    holiday_calendar VARCHAR(64),
    scenario_shift_value DECIMAL(30, 12),
    scenario_shift_rule VARCHAR(32),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    remark VARCHAR(512),
    modifier VARCHAR(128),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT pk_MR_SCENARIO_RULE PRIMARY KEY (scenario_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_MR_SCENARIO_RULE_type
    ON MR_SCENARIO_RULE (scenario_type, status);
CREATE INDEX idx_MR_SCENARIO_RULE_curve
    ON MR_SCENARIO_RULE (scenario_id, curve_type, curve_code);

CREATE TABLE IF NOT EXISTS MR_AGG_RULE (
    rule_id VARCHAR(128) PRIMARY KEY,
    rule_type VARCHAR(64) NOT NULL,
    rule_name VARCHAR(256),
    rule_json LONGTEXT NOT NULL,
    modifier VARCHAR(128),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_MR_AGG_RULE_type ON MR_AGG_RULE (rule_type);

CREATE TABLE IF NOT EXISTS MR_ASYNC_JOB (
    job_id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    engine_code VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    started_at BIGINT,
    finished_at BIGINT,
    elapsed_ms BIGINT,
    success_flag SMALLINT,
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    result_json LONGTEXT,
    idempotency_key VARCHAR(128),
    trace_id VARCHAR(128),
    client_id VARCHAR(128),
    user_id VARCHAR(128),
    user_name VARCHAR(128),
    source_system VARCHAR(128),
    cancel_requested SMALLINT NOT NULL DEFAULT 0,
    owner_node VARCHAR(128),
    updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE UNIQUE INDEX uk_MR_ASYNC_JOB_idem ON MR_ASYNC_JOB(idempotency_key);
CREATE INDEX idx_MR_ASYNC_JOB_status ON MR_ASYNC_JOB(status);

CREATE TABLE IF NOT EXISTS MR_ASYNC_BATCH_JOB (
    batch_id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    engine_code VARCHAR(64) NOT NULL,
    trace_id VARCHAR(128),
    client_id VARCHAR(128),
    user_id VARCHAR(128),
    user_name VARCHAR(128),
    source_system VARCHAR(128),
    op_code VARCHAR(64) NOT NULL,
    data_date DATE NOT NULL,
    portfolio VARCHAR(128),
    desk VARCHAR(64),
    total_trades INT NOT NULL,
    total_jobs INT NOT NULL,
    chunk_size INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    pending_jobs INT NOT NULL DEFAULT 0,
    running_jobs INT NOT NULL DEFAULT 0,
    success_jobs INT NOT NULL DEFAULT 0,
    failed_jobs INT NOT NULL DEFAULT 0,
    cancelled_jobs INT NOT NULL DEFAULT 0,
    message VARCHAR(1024),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS MR_ASYNC_BATCH_ITEM (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    seq_no INT NOT NULL,
    job_id VARCHAR(64) NOT NULL,
    trade_count INT NOT NULL,
    product_mix_json LONGTEXT,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE UNIQUE INDEX uk_MR_ASYNC_BATCH_ITEM_seq ON MR_ASYNC_BATCH_ITEM(batch_id, seq_no);
CREATE UNIQUE INDEX uk_MR_ASYNC_BATCH_ITEM_job ON MR_ASYNC_BATCH_ITEM(job_id);
CREATE INDEX idx_MR_ASYNC_BATCH_ITEM_batch ON MR_ASYNC_BATCH_ITEM(batch_id);
CREATE INDEX idx_MR_ASYNC_BATCH_JOB_status ON MR_ASYNC_BATCH_JOB(status);

CREATE TABLE IF NOT EXISTS MR_AUDIT_LOG (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(128),
    request_id VARCHAR(128),
    client_id VARCHAR(128),
    user_id VARCHAR(128),
    user_name VARCHAR(128),
    source_system VARCHAR(128),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(128),
    engine_code VARCHAR(64),
    success_flag SMALLINT NOT NULL,
    error_code VARCHAR(64),
    message VARCHAR(2048),
    remote_ip VARCHAR(64),
    request_uri VARCHAR(512),
    http_method VARCHAR(16),
    elapsed_ms BIGINT,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_MR_AUDIT_LOG_created_at ON MR_AUDIT_LOG(created_at);
CREATE INDEX idx_MR_AUDIT_LOG_action ON MR_AUDIT_LOG(action);



