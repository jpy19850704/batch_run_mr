-- =====================================================================
-- SA-CCR 输出表结构增量迁移
-- 适用场景：旧 SACCR 输出表结构需要整体替换。
-- 注意：本脚本会删除并重建 TB_OUT_SACCR_* 三张输出表。
-- =====================================================================

DROP TABLE IF EXISTS TB_OUT_SACCR_TRADE_DETAIL;
DROP TABLE IF EXISTS TB_OUT_SACCR_COLLATERAL_DETAIL;
DROP TABLE IF EXISTS TB_OUT_SACCR_RESULT;

-- SA-CCR 交易对手信用风险 EAD 结果表（BCBS 279）
CREATE TABLE TB_OUT_SACCR_RESULT (
    ID                  BIGINT          NOT NULL AUTO_INCREMENT,
    BATCH_ID            VARCHAR(64)     COMMENT '批次ID',
    DATA_DATE           DATE            COMMENT '计算基准日期',
    NETTING_MODE        VARCHAR(32)     COMMENT '净额模式：NETTING_SET/TRADE',
    NETTING_SET_ID      VARCHAR(128)    COMMENT '净额结算集合ID',
    COUNTERPARTY_ID     VARCHAR(128)    COMMENT '交易对手ID',
    TRADE_COUNT         INT             COMMENT '交易笔数',
    MARGIN_TYPE         VARCHAR(32)     COMMENT '保证金协议类型',
    SUM_MTM             DECIMAL(38,10)  COMMENT 'ΣV_i：净额结算集合内所有交易MTM之和',
    COLLATERAL_C        DECIMAL(38,10)  COMMENT '净收取抵押品C（银行收取为正）',
    THRESHOLD_CNY       DECIMAL(38,10)  COMMENT 'TH折人民币金额',
    MTA_CNY             DECIMAL(38,10)  COMMENT 'MTA折人民币金额',
    NICA_CNY            DECIMAL(38,10)  COMMENT 'NICA折人民币金额',
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
    CREATE_TIME         DATETIME(3)     COMMENT '落库时间'
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 4
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- SA-CCR 交易级明细结果表
CREATE TABLE TB_OUT_SACCR_TRADE_DETAIL (
    ID                      BIGINT          NOT NULL AUTO_INCREMENT,
    BATCH_ID                VARCHAR(64)     COMMENT '批次ID',
    DATA_DATE               DATE            COMMENT '计算基准日期',
    INSTRUMENT_ID           VARCHAR(128)    COMMENT '交易唯一标识',
    COUNTERPARTY_ID         VARCHAR(128)    COMMENT '交易对手ID',
    NETTING_MODE            VARCHAR(32)     COMMENT '净额模式：NETTING_SET/TRADE',
    NETTING_SET_ID          VARCHAR(128)    COMMENT '净额集合ID',
    PRODUCT_CODE            VARCHAR(64)     COMMENT '产品代码',
    ASSET_CLASS             VARCHAR(32)     COMMENT '资产类别',
    DIRECTION               INT             COMMENT '交易方向',
    MTM_CNY                 DECIMAL(38,10)  COMMENT '交易MTM人民币金额',
    NOTIONAL                DECIMAL(38,10)  COMMENT '名义本金',
    CURRENCY                VARCHAR(8)      COMMENT '币种',
    START_DATE              DATE            COMMENT '起始日期',
    END_DATE                DATE            COMMENT '到期日期',
    REFERENCE_ENTITY        VARCHAR(256)    COMMENT '参考主体',
    CREDIT_RATING           VARCHAR(64)     COMMENT '信用评级',
    IS_INDEX                TINYINT         COMMENT '是否指数',
    CURRENCY_PAIR           VARCHAR(32)     COMMENT '货币对',
    COMMODITY_BUCKET        VARCHAR(64)     COMMENT '商品桶',
    COMMODITY_TYPE          VARCHAR(128)    COMMENT '商品品种',
    IS_OPTION               TINYINT         COMMENT '是否期权',
    OPTION_TYPE             VARCHAR(16)     COMMENT 'CALL/PUT',
    OPTION_EXPIRY           DATE            COMMENT '期权到期日',
    STRIKE_PRICE            DECIMAL(38,10)  COMMENT '行权价',
    UNDERLYING_PRICE        DECIMAL(38,10)  COMMENT '标的价格',
    QUANTITY                DECIMAL(38,10)  COMMENT '数量',
    MEASURE_FACTOR_JSON     TEXT            COMMENT '交易级中间计量要素JSON',
    CREATE_TIME             DATETIME(3)     COMMENT '落库时间'
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

-- SA-CCR 押品计量审计结果表
CREATE TABLE TB_OUT_SACCR_COLLATERAL_DETAIL (
    ID                      BIGINT          NOT NULL AUTO_INCREMENT,
    BATCH_ID                VARCHAR(64)     COMMENT '批次ID',
    DATA_DATE               DATE            COMMENT '计算基准日期',
    COLLATERAL_ID           VARCHAR(128)    COMMENT '押品唯一标识',
    COLLATERAL_SCOPE        VARCHAR(32)     COMMENT 'NETTING_SET/TRADE',
    NETTING_SET_ID          VARCHAR(128)    COMMENT '净额集合ID',
    INSTRUMENT_ID           VARCHAR(128)    COMMENT '交易唯一标识',
    COLLATERAL_TYPE         VARCHAR(32)     COMMENT '押品类型',
    DIRECTION               VARCHAR(16)     COMMENT 'RECEIVED/POSTED',
    COLLATERAL_CCY          VARCHAR(8)      COMMENT '押品币种',
    MARKET_VALUE            DECIMAL(38,10)  COMMENT '押品市值',
    FX_RATE_TO_CNY          DECIMAL(38,10)  COMMENT '押品币种兑人民币汇率',
    HAIRCUT_RATE            DECIMAL(18,10)  COMMENT '折扣率',
    ADJUSTED_VALUE_CNY      DECIMAL(38,10)  COMMENT '计入COLLATERAL_C的折后人民币金额',
    CREATE_TIME             DATETIME(3)     COMMENT '落库时间'
)
UNIQUE KEY(ID)
DISTRIBUTED BY HASH(ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);
