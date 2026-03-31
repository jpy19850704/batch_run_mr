-- =====================================================================
-- Doris 输出表 DDL（Unique Key 模型）
-- 对应 Engine 结果表结构，定义与 PricingResultPersistService 对齐。
-- 切换步骤：在 Doris FE 执行本文件后，修改环境变量指向 Doris 即可。
-- =====================================================================

-- 情景文件结果表
CREATE TABLE IF NOT EXISTS TB_OUT_SCENARIO_FILE_DETAIL (
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
    SCENARIO_TYPE       VARCHAR(64),
    RISKFACTOR_TYPE     VARCHAR(64),
    RISKFACTOR_ID       VARCHAR(256),
    RISKFACTOR_VERTEX1  VARCHAR(128),
    RISKFACTOR_VERTEX2  VARCHAR(128),
    CHANGE_VALUE        DECIMAL(38, 10),
    RISKFACTOR_TERM     VARCHAR(64),
    ORI_VALUE           DECIMAL(38, 10),
    SCENARIO_RESULT     DECIMAL(38, 10),
    MODIFIER            VARCHAR(128),
    CREATED_AT          BIGINT,
    UPDATED_AT          BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

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
    POSITION        DECIMAL(38, 10),
    VALUATION_UNIT  DECIMAL(38, 10),
    VALUATION       DECIMAL(38, 10),
    VALUATION_CCY   VARCHAR(8),
    VALUATION_CNY   DECIMAL(38, 10),
    PV01            DECIMAL(38, 10),
    DELTA           DECIMAL(38, 10),
    GAMMA           DECIMAL(38, 10),
    VEGA            DECIMAL(38, 10),
    THETA           DECIMAL(38, 10),
    RHO             DECIMAL(38, 10),
    STATUS          VARCHAR(16),
    ERROR           TEXT,
    DETAIL          TEXT,
    ERRORS_JSON     TEXT,
    CASHFLOW_JSON   TEXT,
    RESULT_JSON             TEXT,
    TRADE_INPUT_JSON        TEXT            COMMENT '原始交易输入 JSON',
    MARKET_DATA_KEYS_JSON   TEXT            COMMENT '交易引用的市场数据标识 JSON 数组',
    CREATED_AT              BIGINT,
    UPDATED_AT              BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
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
    BASE_VALUATION_CNY      DECIMAL(38, 10),
    SCENARIO_VALUATION_CNY  DECIMAL(38, 10),
    PNL                     DECIMAL(38, 10),
    ERROR                   TEXT,
    DETAIL                  TEXT,
    RESULT_JSON             TEXT,
    CREATED_AT              BIGINT,
    UPDATED_AT              BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- 情景 PnL VAR 结果表
CREATE TABLE IF NOT EXISTS TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL (
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
    BASE_VALUATION_CNY  DECIMAL(38, 10),
    IR_VALUATION        DECIMAL(38, 10),
    IR_PNL              DECIMAL(38, 10),
    FX_VALUATION        DECIMAL(38, 10),
    FX_PNL              DECIMAL(38, 10),
    EQ_VALUATION        DECIMAL(38, 10),
    EQ_PNL              DECIMAL(38, 10),
    COMM_VALUATION      DECIMAL(38, 10),
    COMM_PNL            DECIMAL(38, 10),
    ALL_VALUATION       DECIMAL(38, 10),
    ALL_PNL             DECIMAL(38, 10),
    RESULT_JSON         TEXT,
    CREATED_AT          BIGINT,
    UPDATED_AT          BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
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
    SENSITIVITY_VAL_INST_CURR       DECIMAL(38, 10),
    INSTRUMENT_CURRENCY             VARCHAR(8),
    SENSITIVITY_VAL_INST_CURR_CNY   DECIMAL(38, 10),
    DETAIL_JSON                     TEXT,
    CREATED_AT                      BIGINT,
    UPDATED_AT                      BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
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
    TERM_TO_MATURITY    DECIMAL(38, 10),
    MODIFIED_REMAIN_TERM DECIMAL(38, 10),
    RISK_WEIGHT         DECIMAL(38, 10),
    JTD                 DECIMAL(38, 10),
    JTD_CNY             DECIMAL(38, 10),
    INSTRUMENT_VALUE    DECIMAL(38, 10),
    FRTB_LGD            DECIMAL(38, 10),
    NOTIONAL            DECIMAL(38, 10),
    DETAIL_JSON         TEXT,
    CREATED_AT          BIGINT,
    UPDATED_AT          BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- DRC 汇总结果表（单表）
CREATE TABLE IF NOT EXISTS TB_OUT_TRADE_DRC_RESULT (
    BATCH_ID            VARCHAR(64),
    DATA_DATE           VARCHAR(16),
    DECOMP_FLAG         VARCHAR(32),
    AGG_LEVEL           VARCHAR(32),
    DRC_TYPE            VARCHAR(64),
    DRC_BUCKET          VARCHAR(64),
    LEGAL_ENTITY        VARCHAR(128),
    REQUEST_ID          VARCHAR(128),
    JOB_ID              VARCHAR(64),
    DRC_VALUE           DECIMAL(38, 10),
    CREATED_AT          BIGINT
)
UNIQUE KEY(BATCH_ID, DATA_DATE, DECOMP_FLAG, AGG_LEVEL, DRC_TYPE, DRC_BUCKET, LEGAL_ENTITY)
DISTRIBUTED BY HASH(BATCH_ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- 市场数据结果表（每条曲线一行，完整曲线 JSON 由前端解析）
CREATE TABLE IF NOT EXISTS TB_OUT_MARKET_DATA_DETAIL (
    ID              BIGINT          NOT NULL AUTO_INCREMENT,
    BATCH_ID        VARCHAR(64),
    DATA_DATE       VARCHAR(16),
    OP_CODE         VARCHAR(64),
    CURVE_TYPE      VARCHAR(64)     COMMENT '曲线类型: IR_SPOT/FX_SPOT/EQ_SPOT/COMM_SPOT/IR_VOL/FX_VOL/EQ_VOL/COMM_VOL/FIXING',
    CURVE_ID        VARCHAR(256)    COMMENT '曲线ID / FIXING_ID',
    CURVE_DATA_JSON TEXT            COMMENT '完整曲线 JSON（含 CURVE_DATA 等全部结构，前端解析）',
    CREATED_AT      BIGINT,
    UPDATED_AT      BIGINT
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- 投组层级快照结果表
CREATE TABLE IF NOT EXISTS TB_OUT_PORTFOLIO_HIERARCHY (
    BATCH_ID                VARCHAR(64),
    DATA_DATE               VARCHAR(16),
    PORTFOLIO_CODE          VARCHAR(128),
    PORTFOLIO_NAME          VARCHAR(256),
    UPPER_LEVEL_PORTFOLIO   VARCHAR(128),
    LEVEL_CODE              VARCHAR(16),
    CREATED_AT              BIGINT,
    UPDATED_AT              BIGINT
)
UNIQUE KEY(BATCH_ID, DATA_DATE, PORTFOLIO_CODE, PORTFOLIO_NAME, UPPER_LEVEL_PORTFOLIO, LEVEL_CODE)
DISTRIBUTED BY HASH(BATCH_ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

