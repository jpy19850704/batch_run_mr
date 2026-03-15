-- =====================================================================
-- Doris 输出表 DDL（Unique Key 模型）
-- 对应 H2 中的 TB_OUT_* 表，结构与 PricingResultPersistService 对齐。
-- 切换步骤：在 Doris FE 执行本文件后，修改环境变量指向 Doris 即可。
-- =====================================================================

-- 基准估值结果表
CREATE TABLE IF NOT EXISTS TB_OUT_TRADE_RESULT_DETAIL (
    ID              BIGINT          NOT NULL AUTO_INCREMENT,
    REQUEST_ID      VARCHAR(128),
    JOB_ID          VARCHAR(64),
    BATCH_ID        VARCHAR(64),
    SEQ_NO          BIGINT,
    DATA_DATE       VARCHAR(16),
    OP_CODE         VARCHAR(64),
    INSTRUMENT_ID   VARCHAR(128),
    PRODUCT_CODE    VARCHAR(64),
    PORTFOLIO       VARCHAR(128),
    DESK            VARCHAR(64),
    TRADER          VARCHAR(64),
    POSITION        DECIMAL(20, 8),
    VALUATION_UNIT  DECIMAL(20, 8),
    VALUATION       DECIMAL(20, 8),
    VALUATION_CCY   VARCHAR(8),
    VALUATION_CNY   DECIMAL(20, 8),
    PV01            DECIMAL(20, 8),
    DELTA           DECIMAL(20, 8),
    GAMMA           DECIMAL(20, 8),
    VEGA            DECIMAL(20, 8),
    THETA           DECIMAL(20, 8),
    RHO             DECIMAL(20, 8),
    STATUS          VARCHAR(16),
    ERROR           TEXT,
    DETAIL          TEXT,
    ERRORS_JSON     TEXT,
    CASHFLOW_JSON   TEXT,
    RESULT_JSON     TEXT,
    CREATED_AT      BIGINT,
    UPDATED_AT      BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(INSTRUMENT_ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- 情景 PnL 结果表
CREATE TABLE IF NOT EXISTS TB_OUT_TRADE_SCENARIO_RESULT_DETAIL (
    ID                      BIGINT          NOT NULL AUTO_INCREMENT,
    REQUEST_ID              VARCHAR(128),
    JOB_ID                  VARCHAR(64),
    BATCH_ID                VARCHAR(64),
    SEQ_NO                  BIGINT,
    DATA_DATE               VARCHAR(16),
    OP_CODE                 VARCHAR(64),
    SCENARIO_ID             VARCHAR(128),
    SUBSCENARIO_ID          VARCHAR(128),
    SCENARIO_NAME           VARCHAR(256),
    INSTRUMENT_ID           VARCHAR(128),
    PRODUCT_CODE            VARCHAR(64),
    BASE_VALUATION_CNY      DECIMAL(20, 8),
    SCENARIO_VALUATION_CNY  DECIMAL(20, 8),
    PNL                     DECIMAL(20, 8),
    ERROR                   TEXT,
    DETAIL                  TEXT,
    RESULT_JSON             TEXT,
    CREATED_AT              BIGINT,
    UPDATED_AT              BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(INSTRUMENT_ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- 情景 PnL 分解结果表
CREATE TABLE IF NOT EXISTS TB_OUT_TRADE_SCENARIO_DECOMP_DETAIL (
    ID                  BIGINT          NOT NULL AUTO_INCREMENT,
    REQUEST_ID          VARCHAR(128),
    JOB_ID              VARCHAR(64),
    BATCH_ID            VARCHAR(64),
    SEQ_NO              BIGINT,
    DATA_DATE           VARCHAR(16),
    OP_CODE             VARCHAR(64),
    SCENARIO_ID         VARCHAR(128),
    SUBSCENARIO_ID      VARCHAR(128),
    SCENARIO_NAME       VARCHAR(256),
    INSTRUMENT_ID       VARCHAR(128),
    PRODUCT_CODE        VARCHAR(64),
    BASE_VALUATION_CNY  DECIMAL(20, 8),
    IR_VALUATION        DECIMAL(20, 8),
    IR_PNL              DECIMAL(20, 8),
    FX_VALUATION        DECIMAL(20, 8),
    FX_PNL              DECIMAL(20, 8),
    EQ_VALUATION        DECIMAL(20, 8),
    EQ_PNL              DECIMAL(20, 8),
    COMM_VALUATION      DECIMAL(20, 8),
    COMM_PNL            DECIMAL(20, 8),
    ALL_VALUATION       DECIMAL(20, 8),
    ALL_PNL             DECIMAL(20, 8),
    RESULT_JSON         TEXT,
    CREATED_AT          BIGINT,
    UPDATED_AT          BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(INSTRUMENT_ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- FRTB 敏感性明细表
CREATE TABLE IF NOT EXISTS TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL (
    ID                              BIGINT          NOT NULL AUTO_INCREMENT,
    REQUEST_ID                      VARCHAR(128),
    JOB_ID                          VARCHAR(64),
    BATCH_ID                        VARCHAR(64),
    SEQ_NO                          BIGINT,
    DATA_DATE                       VARCHAR(16),
    OP_CODE                         VARCHAR(64),
    INSTRUMENT_ID                   VARCHAR(128),
    PRODUCT_CODE                    VARCHAR(64),
    RISK_FACTOR_ID                  VARCHAR(256),
    RISK_FACTOR_VERTEX_1            VARCHAR(128),
    RISK_FACTOR_VERTEX_2            VARCHAR(128),
    RISK_FACTOR_CLASS               VARCHAR(64),
    RISK_FACTOR_BUCKET              VARCHAR(64),
    RISK_FACTOR_TYPE                VARCHAR(64),
    SENSITIVITY_TYPE                VARCHAR(64),
    SENSITIVITY_VAL_INST_CURR       DECIMAL(20, 8),
    INSTRUMENT_CURRENCY             VARCHAR(8),
    SENSITIVITY_VAL_INST_CURR_CNY   DECIMAL(20, 8),
    DETAIL_JSON                     TEXT,
    CREATED_AT                      BIGINT,
    UPDATED_AT                      BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(INSTRUMENT_ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- DRC 违约风险明细表
CREATE TABLE IF NOT EXISTS TB_OUT_TRADE_DRC_DETAIL (
    ID                  BIGINT          NOT NULL AUTO_INCREMENT,
    REQUEST_ID          VARCHAR(128),
    JOB_ID              VARCHAR(64),
    BATCH_ID            VARCHAR(64),
    SEQ_NO              BIGINT,
    DATA_DATE           VARCHAR(16),
    OP_CODE             VARCHAR(64),
    INSTRUMENT_ID       VARCHAR(128),
    PRODUCT_CODE        VARCHAR(64),
    PORTFOLIO_CODE      VARCHAR(128),
    SECURITY_ID         VARCHAR(128),
    SECURITY_TYPE       VARCHAR(64),
    LEGAL_ENTITY        VARCHAR(128),
    DRC_BUCKET          VARCHAR(64),
    JTD_TYPE            VARCHAR(64),
    SENIORITY           INT,
    TERM_TO_MATURITY    DECIMAL(20, 8),
    MODIFIED_REMAIN_TERM DECIMAL(20, 8),
    RISK_WEIGHT         DECIMAL(20, 8),
    JTD                 DECIMAL(20, 8),
    INSTRUMENT_VALUE    DECIMAL(20, 8),
    FRTB_LGD            DECIMAL(20, 8),
    NOTIONAL            DECIMAL(20, 8),
    DETAIL_JSON         TEXT,
    CREATED_AT          BIGINT,
    UPDATED_AT          BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(INSTRUMENT_ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);
