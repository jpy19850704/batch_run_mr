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

-- VaR 汇总结果表（批次总编排输出）
CREATE TABLE IF NOT EXISTS TB_OUT_VAR_RESULT (
    BATCH_ID            VARCHAR(64),
    DATA_DATE           VARCHAR(16),
    QUANTILE            VARCHAR(32),
    RULE_ID             VARCHAR(128),
    RULE_NAME           VARCHAR(256),
    MODE                VARCHAR(64),
    SCENARIO_ID         VARCHAR(128),
    GROUP_TYPE          VARCHAR(64),
    GROUP_VALUE         VARCHAR(512),
    RISK_CLASS          VARCHAR(64),
    VAR                 DECIMAL(38, 10),
    ES                  DECIMAL(38, 10),
    SELECTED_METHOD     VARCHAR(32),
    CREATED_AT          BIGINT
)
UNIQUE KEY(BATCH_ID, DATA_DATE, QUANTILE, RULE_ID, MODE, SCENARIO_ID, GROUP_TYPE, GROUP_VALUE, RISK_CLASS)
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

-- =====================================================================
-- FRTB IMA 内部模型法结果表
-- Phase1：分批重定价 → 情景 PnL 落库
-- Phase2：从库读取 → ES → IMCC → 资本汇总
-- =====================================================================

-- IMA 可建模风险因子情景 PnL 明细表（Phase1 输出）
-- 每行对应一笔交易在某一流动性期限子集（LH_SUBSET 1-5）下的情景重定价损益。
-- SCENARIO_TYPE 区分 STRESS（压力期）/ NORMAL（当前期）/ REDUCED_SET（缩减集，MAR33.5）。
-- ES 聚合在 Phase2 中从本表读取，按 SCENARIO_TYPE+SCENARIO_ID+SUBSCENARIO_ID+LH_DAYS 分组。
-- 风险类别损益按列拆分（对齐 TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL），ALL_PNL 为全风险类别合计。
CREATE TABLE IF NOT EXISTS TB_OUT_IMA_MODELLABLE_SCENARIO_PNL (
    ID                      BIGINT          NOT NULL AUTO_INCREMENT   COMMENT '主键',
    REQUEST_ID              VARCHAR(128)                             COMMENT '请求ID',
    JOB_ID                  VARCHAR(64)                              COMMENT '任务ID',
    BATCH_ID                VARCHAR(64)                              COMMENT '批次ID',
    SEQ_NO                  BIGINT                                   COMMENT '序号',
    DATA_DATE               VARCHAR(16)                              COMMENT '计算基准日期',
    OP_CODE                 VARCHAR(64)                              COMMENT '操作码',
    SCENARIO_ID             VARCHAR(128)                             COMMENT '情景集ID',
    SUBSCENARIO_ID          VARCHAR(128)                             COMMENT '子情景ID（单条历史情景序号）',
    SCENARIO_NAME           VARCHAR(256)                             COMMENT '情景名称',
    SCENARIO_TYPE           VARCHAR(32)                              COMMENT '情景类型：STRESS / NORMAL / REDUCED_SET',
    INSTRUMENT_ID           VARCHAR(128)                             COMMENT '交易ID',
    PRODUCT_CODE            VARCHAR(64)                              COMMENT '产品代码',
    LH_DAYS                 SMALLINT                                 COMMENT '流动性期限天数：10/20/40/60/120（MAR33.4 j=1..5）',
    BASE_VALUATION_CNY      DECIMAL(38, 10)                          COMMENT '基准估值（人民币）',
    IR_VALUATION            DECIMAL(38, 10)                          COMMENT '利率风险因子子集重定价估值',
    IR_PNL                  DECIMAL(38, 10)                          COMMENT '利率风险损益',
    FX_VALUATION            DECIMAL(38, 10)                          COMMENT '外汇风险因子子集重定价估值',
    FX_PNL                  DECIMAL(38, 10)                          COMMENT '外汇风险损益',
    EQ_VALUATION            DECIMAL(38, 10)                          COMMENT '权益风险因子子集重定价估值',
    EQ_PNL                  DECIMAL(38, 10)                          COMMENT '权益风险损益',
    COMM_VALUATION          DECIMAL(38, 10)                          COMMENT '大宗商品风险因子子集重定价估值',
    COMM_PNL                DECIMAL(38, 10)                          COMMENT '大宗商品风险损益',
    ALL_VALUATION           DECIMAL(38, 10)                          COMMENT '全风险类别子集重定价估值',
    ALL_PNL                 DECIMAL(38, 10)                          COMMENT '全风险类别损益',
    RESULT_JSON             TEXT                                     COMMENT '扩展结果JSON',
    CREATED_AT              BIGINT                                   COMMENT '创建时间戳',
    UPDATED_AT              BIGINT                                   COMMENT '更新时间戳'
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- IMA 不可建模风险因子情景 PnL 明细表（Phase1 输出）
-- 每行对应一笔交易在某一 NMRF 因子单独压力冲击下的情景损益。
-- NMRF_TYPE 区分 IDIO_CREDIT / IDIO_EQUITY / OTHER，用于 SES 聚合公式（MAR33.17）。
-- SES 聚合在 Phase2 中从本表读取，按 RISK_FACTOR_ID 分组取最大损失作为压力场景损失。
CREATE TABLE IF NOT EXISTS TB_OUT_IMA_NMRF_SCENARIO_PNL (
    ID                      BIGINT          NOT NULL AUTO_INCREMENT   COMMENT '主键',
    REQUEST_ID              VARCHAR(128)                             COMMENT '请求ID',
    JOB_ID                  VARCHAR(64)                              COMMENT '任务ID',
    BATCH_ID                VARCHAR(64)                              COMMENT '批次ID',
    SEQ_NO                  BIGINT                                   COMMENT '序号',
    DATA_DATE               VARCHAR(16)                              COMMENT '计算基准日期',
    OP_CODE                 VARCHAR(64)                              COMMENT '操作码',
    SCENARIO_ID             VARCHAR(128)                             COMMENT '情景集ID（NMRF压力情景集）',
    SUBSCENARIO_ID          VARCHAR(128)                             COMMENT '子情景ID（单条压力情景）',
    SCENARIO_NAME           VARCHAR(256)                             COMMENT '情景名称',
    INSTRUMENT_ID           VARCHAR(128)                             COMMENT '交易ID',
    PRODUCT_CODE            VARCHAR(64)                              COMMENT '产品代码',
    RISK_FACTOR_ID          VARCHAR(256)                             COMMENT '不可建模风险因子ID（MAR31.13 NMRF）',
    NMRF_TYPE               VARCHAR(32)                              COMMENT 'NMRF分类：IDIO_CREDIT / IDIO_EQUITY / OTHER（MAR33.17）',
    BASE_VALUATION_CNY      DECIMAL(38, 10)                          COMMENT '基准估值（人民币）',
    STRESS_VALUATION_CNY    DECIMAL(38, 10)                          COMMENT '压力情景重定价估值（仅冲击该NMRF因子）',
    PNL                     DECIMAL(38, 10)                          COMMENT '压力情景损益 = STRESS_VALUATION - BASE_VALUATION',
    RESULT_JSON             TEXT                                     COMMENT '扩展结果JSON',
    CREATED_AT              BIGINT                                   COMMENT '创建时间戳',
    UPDATED_AT              BIGINT                                   COMMENT '更新时间戳'
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- SA-CCR 交易对手信用风险 EAD 结果表（BCBS 279）
CREATE TABLE IF NOT EXISTS TB_OUT_SACCR_RESULT (
    ID                  BIGINT          NOT NULL AUTO_INCREMENT,
    JOB_ID              VARCHAR(64)     COMMENT '任务ID',
    DATA_DATE           VARCHAR(16)     COMMENT '计算基准日期',
    NETTING_SET_ID      VARCHAR(128)    COMMENT '净额结算集合ID',
    COUNTERPARTY_ID     VARCHAR(128)    COMMENT '交易对手ID',
    IS_MARGINED         TINYINT         COMMENT '是否有保证金协议(1=是,0=否)',
    IS_CLEARED          TINYINT         COMMENT '是否集中清算(1=是,0=否)',
    IS_QCCP             TINYINT         COMMENT '是否QCCP(1=是,0=否)',
    SUM_MTM             DECIMAL(38,10)  COMMENT 'ΣV_i：净额结算集合内所有交易MTM之和',
    COLLATERAL_C        DECIMAL(38,10)  COMMENT '净收取抵押品C（银行收取为正）',
    RC                  DECIMAL(38,10)  COMMENT '替代成本RC',
    ADDON_IR            DECIMAL(38,10)  COMMENT '利率AddOn',
    ADDON_FX            DECIMAL(38,10)  COMMENT '外汇AddOn',
    ADDON_CREDIT        DECIMAL(38,10)  COMMENT '信用AddOn',
    ADDON_EQUITY        DECIMAL(38,10)  COMMENT '权益AddOn',
    ADDON_COMMODITY     DECIMAL(38,10)  COMMENT '大宗商品AddOn',
    ADDON_AGGREGATE     DECIMAL(38,10)  COMMENT 'AddOn合计',
    MULTIPLIER          DECIMAL(38,10)  COMMENT '乘数multiplier（[0.05,1.0]）',
    PFE                 DECIMAL(38,10)  COMMENT '潜在未来风险暴露PFE',
    EAD                 DECIMAL(38,10)  COMMENT '风险敞口EAD=1.4×(RC+PFE)',
    RISK_WEIGHT         DECIMAL(38,10)  COMMENT '适用风险权重RW',
    RWA_CCR             DECIMAL(38,10)  COMMENT 'CCR风险加权资产RWA=EAD×RW',
    CAPITAL_CCR         DECIMAL(38,10)  COMMENT 'CCR资本要求=RWA×8%',
    CREATE_TIME         VARCHAR(32)     COMMENT '落库时间'
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 4
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

-- 投组层级平铺视图（供 FRTB / 明细按层级维度过滤）
DROP VIEW IF EXISTS V_TB_OUT_PORTFOLIO_HIERARCHY_FLAT;
CREATE VIEW V_TB_OUT_PORTFOLIO_HIERARCHY_FLAT AS
SELECT
    h0.BATCH_ID,
    h0.DATA_DATE,
    h0.PORTFOLIO_CODE,
    h0.PORTFOLIO_NAME,
    h0.LEVEL_CODE,
    CASE
        WHEN h0.LEVEL_CODE = '1' THEN h0.PORTFOLIO_CODE
        WHEN h1.LEVEL_CODE = '1' THEN h1.PORTFOLIO_CODE
        WHEN h2.LEVEL_CODE = '1' THEN h2.PORTFOLIO_CODE
        WHEN h3.LEVEL_CODE = '1' THEN h3.PORTFOLIO_CODE
        WHEN h4.LEVEL_CODE = '1' THEN h4.PORTFOLIO_CODE
        WHEN h5.LEVEL_CODE = '1' THEN h5.PORTFOLIO_CODE
        WHEN h6.LEVEL_CODE = '1' THEN h6.PORTFOLIO_CODE
        ELSE NULL
    END AS PORTFOLIO_CODE_1,
    CASE
        WHEN h0.LEVEL_CODE = '1' THEN h0.PORTFOLIO_NAME
        WHEN h1.LEVEL_CODE = '1' THEN h1.PORTFOLIO_NAME
        WHEN h2.LEVEL_CODE = '1' THEN h2.PORTFOLIO_NAME
        WHEN h3.LEVEL_CODE = '1' THEN h3.PORTFOLIO_NAME
        WHEN h4.LEVEL_CODE = '1' THEN h4.PORTFOLIO_NAME
        WHEN h5.LEVEL_CODE = '1' THEN h5.PORTFOLIO_NAME
        WHEN h6.LEVEL_CODE = '1' THEN h6.PORTFOLIO_NAME
        ELSE NULL
    END AS PORTFOLIO_NAME_1,
    CASE
        WHEN h0.LEVEL_CODE = '2' THEN h0.PORTFOLIO_CODE
        WHEN h1.LEVEL_CODE = '2' THEN h1.PORTFOLIO_CODE
        WHEN h2.LEVEL_CODE = '2' THEN h2.PORTFOLIO_CODE
        WHEN h3.LEVEL_CODE = '2' THEN h3.PORTFOLIO_CODE
        WHEN h4.LEVEL_CODE = '2' THEN h4.PORTFOLIO_CODE
        WHEN h5.LEVEL_CODE = '2' THEN h5.PORTFOLIO_CODE
        WHEN h6.LEVEL_CODE = '2' THEN h6.PORTFOLIO_CODE
        ELSE NULL
    END AS PORTFOLIO_CODE_2,
    CASE
        WHEN h0.LEVEL_CODE = '2' THEN h0.PORTFOLIO_NAME
        WHEN h1.LEVEL_CODE = '2' THEN h1.PORTFOLIO_NAME
        WHEN h2.LEVEL_CODE = '2' THEN h2.PORTFOLIO_NAME
        WHEN h3.LEVEL_CODE = '2' THEN h3.PORTFOLIO_NAME
        WHEN h4.LEVEL_CODE = '2' THEN h4.PORTFOLIO_NAME
        WHEN h5.LEVEL_CODE = '2' THEN h5.PORTFOLIO_NAME
        WHEN h6.LEVEL_CODE = '2' THEN h6.PORTFOLIO_NAME
        ELSE NULL
    END AS PORTFOLIO_NAME_2,
    CASE
        WHEN h0.LEVEL_CODE = '3' THEN h0.PORTFOLIO_CODE
        WHEN h1.LEVEL_CODE = '3' THEN h1.PORTFOLIO_CODE
        WHEN h2.LEVEL_CODE = '3' THEN h2.PORTFOLIO_CODE
        WHEN h3.LEVEL_CODE = '3' THEN h3.PORTFOLIO_CODE
        WHEN h4.LEVEL_CODE = '3' THEN h4.PORTFOLIO_CODE
        WHEN h5.LEVEL_CODE = '3' THEN h5.PORTFOLIO_CODE
        WHEN h6.LEVEL_CODE = '3' THEN h6.PORTFOLIO_CODE
        ELSE NULL
    END AS PORTFOLIO_CODE_3,
    CASE
        WHEN h0.LEVEL_CODE = '3' THEN h0.PORTFOLIO_NAME
        WHEN h1.LEVEL_CODE = '3' THEN h1.PORTFOLIO_NAME
        WHEN h2.LEVEL_CODE = '3' THEN h2.PORTFOLIO_NAME
        WHEN h3.LEVEL_CODE = '3' THEN h3.PORTFOLIO_NAME
        WHEN h4.LEVEL_CODE = '3' THEN h4.PORTFOLIO_NAME
        WHEN h5.LEVEL_CODE = '3' THEN h5.PORTFOLIO_NAME
        WHEN h6.LEVEL_CODE = '3' THEN h6.PORTFOLIO_NAME
        ELSE NULL
    END AS PORTFOLIO_NAME_3,
    CASE
        WHEN h0.LEVEL_CODE = '4' THEN h0.PORTFOLIO_CODE
        WHEN h1.LEVEL_CODE = '4' THEN h1.PORTFOLIO_CODE
        WHEN h2.LEVEL_CODE = '4' THEN h2.PORTFOLIO_CODE
        WHEN h3.LEVEL_CODE = '4' THEN h3.PORTFOLIO_CODE
        WHEN h4.LEVEL_CODE = '4' THEN h4.PORTFOLIO_CODE
        WHEN h5.LEVEL_CODE = '4' THEN h5.PORTFOLIO_CODE
        WHEN h6.LEVEL_CODE = '4' THEN h6.PORTFOLIO_CODE
        ELSE NULL
    END AS PORTFOLIO_CODE_4,
    CASE
        WHEN h0.LEVEL_CODE = '4' THEN h0.PORTFOLIO_NAME
        WHEN h1.LEVEL_CODE = '4' THEN h1.PORTFOLIO_NAME
        WHEN h2.LEVEL_CODE = '4' THEN h2.PORTFOLIO_NAME
        WHEN h3.LEVEL_CODE = '4' THEN h3.PORTFOLIO_NAME
        WHEN h4.LEVEL_CODE = '4' THEN h4.PORTFOLIO_NAME
        WHEN h5.LEVEL_CODE = '4' THEN h5.PORTFOLIO_NAME
        WHEN h6.LEVEL_CODE = '4' THEN h6.PORTFOLIO_NAME
        ELSE NULL
    END AS PORTFOLIO_NAME_4,
    CASE
        WHEN h0.LEVEL_CODE = '5' THEN h0.PORTFOLIO_CODE
        WHEN h1.LEVEL_CODE = '5' THEN h1.PORTFOLIO_CODE
        WHEN h2.LEVEL_CODE = '5' THEN h2.PORTFOLIO_CODE
        WHEN h3.LEVEL_CODE = '5' THEN h3.PORTFOLIO_CODE
        WHEN h4.LEVEL_CODE = '5' THEN h4.PORTFOLIO_CODE
        WHEN h5.LEVEL_CODE = '5' THEN h5.PORTFOLIO_CODE
        WHEN h6.LEVEL_CODE = '5' THEN h6.PORTFOLIO_CODE
        ELSE NULL
    END AS PORTFOLIO_CODE_5,
    CASE
        WHEN h0.LEVEL_CODE = '5' THEN h0.PORTFOLIO_NAME
        WHEN h1.LEVEL_CODE = '5' THEN h1.PORTFOLIO_NAME
        WHEN h2.LEVEL_CODE = '5' THEN h2.PORTFOLIO_NAME
        WHEN h3.LEVEL_CODE = '5' THEN h3.PORTFOLIO_NAME
        WHEN h4.LEVEL_CODE = '5' THEN h4.PORTFOLIO_NAME
        WHEN h5.LEVEL_CODE = '5' THEN h5.PORTFOLIO_NAME
        WHEN h6.LEVEL_CODE = '5' THEN h6.PORTFOLIO_NAME
        ELSE NULL
    END AS PORTFOLIO_NAME_5,
    CASE
        WHEN h0.LEVEL_CODE = '6' THEN h0.PORTFOLIO_CODE
        WHEN h1.LEVEL_CODE = '6' THEN h1.PORTFOLIO_CODE
        WHEN h2.LEVEL_CODE = '6' THEN h2.PORTFOLIO_CODE
        WHEN h3.LEVEL_CODE = '6' THEN h3.PORTFOLIO_CODE
        WHEN h4.LEVEL_CODE = '6' THEN h4.PORTFOLIO_CODE
        WHEN h5.LEVEL_CODE = '6' THEN h5.PORTFOLIO_CODE
        WHEN h6.LEVEL_CODE = '6' THEN h6.PORTFOLIO_CODE
        ELSE NULL
    END AS PORTFOLIO_CODE_6,
    CASE
        WHEN h0.LEVEL_CODE = '6' THEN h0.PORTFOLIO_NAME
        WHEN h1.LEVEL_CODE = '6' THEN h1.PORTFOLIO_NAME
        WHEN h2.LEVEL_CODE = '6' THEN h2.PORTFOLIO_NAME
        WHEN h3.LEVEL_CODE = '6' THEN h3.PORTFOLIO_NAME
        WHEN h4.LEVEL_CODE = '6' THEN h4.PORTFOLIO_NAME
        WHEN h5.LEVEL_CODE = '6' THEN h5.PORTFOLIO_NAME
        WHEN h6.LEVEL_CODE = '6' THEN h6.PORTFOLIO_NAME
        ELSE NULL
    END AS PORTFOLIO_NAME_6,
    CASE
        WHEN h0.LEVEL_CODE = '7' THEN h0.PORTFOLIO_CODE
        WHEN h1.LEVEL_CODE = '7' THEN h1.PORTFOLIO_CODE
        WHEN h2.LEVEL_CODE = '7' THEN h2.PORTFOLIO_CODE
        WHEN h3.LEVEL_CODE = '7' THEN h3.PORTFOLIO_CODE
        WHEN h4.LEVEL_CODE = '7' THEN h4.PORTFOLIO_CODE
        WHEN h5.LEVEL_CODE = '7' THEN h5.PORTFOLIO_CODE
        WHEN h6.LEVEL_CODE = '7' THEN h6.PORTFOLIO_CODE
        ELSE NULL
    END AS PORTFOLIO_CODE_7,
    CASE
        WHEN h0.LEVEL_CODE = '7' THEN h0.PORTFOLIO_NAME
        WHEN h1.LEVEL_CODE = '7' THEN h1.PORTFOLIO_NAME
        WHEN h2.LEVEL_CODE = '7' THEN h2.PORTFOLIO_NAME
        WHEN h3.LEVEL_CODE = '7' THEN h3.PORTFOLIO_NAME
        WHEN h4.LEVEL_CODE = '7' THEN h4.PORTFOLIO_NAME
        WHEN h5.LEVEL_CODE = '7' THEN h5.PORTFOLIO_NAME
        WHEN h6.LEVEL_CODE = '7' THEN h6.PORTFOLIO_NAME
        ELSE NULL
    END AS PORTFOLIO_NAME_7
FROM TB_OUT_PORTFOLIO_HIERARCHY h0
LEFT JOIN TB_OUT_PORTFOLIO_HIERARCHY h1
    ON h1.BATCH_ID = h0.BATCH_ID
    AND h1.DATA_DATE = h0.DATA_DATE
    AND h1.PORTFOLIO_CODE = h0.UPPER_LEVEL_PORTFOLIO
LEFT JOIN TB_OUT_PORTFOLIO_HIERARCHY h2
    ON h2.BATCH_ID = h0.BATCH_ID
    AND h2.DATA_DATE = h0.DATA_DATE
    AND h2.PORTFOLIO_CODE = h1.UPPER_LEVEL_PORTFOLIO
LEFT JOIN TB_OUT_PORTFOLIO_HIERARCHY h3
    ON h3.BATCH_ID = h0.BATCH_ID
    AND h3.DATA_DATE = h0.DATA_DATE
    AND h3.PORTFOLIO_CODE = h2.UPPER_LEVEL_PORTFOLIO
LEFT JOIN TB_OUT_PORTFOLIO_HIERARCHY h4
    ON h4.BATCH_ID = h0.BATCH_ID
    AND h4.DATA_DATE = h0.DATA_DATE
    AND h4.PORTFOLIO_CODE = h3.UPPER_LEVEL_PORTFOLIO
LEFT JOIN TB_OUT_PORTFOLIO_HIERARCHY h5
    ON h5.BATCH_ID = h0.BATCH_ID
    AND h5.DATA_DATE = h0.DATA_DATE
    AND h5.PORTFOLIO_CODE = h4.UPPER_LEVEL_PORTFOLIO
LEFT JOIN TB_OUT_PORTFOLIO_HIERARCHY h6
    ON h6.BATCH_ID = h0.BATCH_ID
    AND h6.DATA_DATE = h0.DATA_DATE
    AND h6.PORTFOLIO_CODE = h5.UPPER_LEVEL_PORTFOLIO;

