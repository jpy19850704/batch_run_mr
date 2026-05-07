-- DRC 汇总结果表规则维度迁移脚本
--
-- 适用场景：
-- 已存在旧版 TB_OUT_TRADE_DRC_RESULT 表，需要支持 RULE_ID / GROUP_TYPE / GROUP_VALUE
-- 且唯一键需要纳入规则与维度分组。
--
-- 执行前确认：
-- 1. 当前表为旧结构，尚未包含 RULE_ID / GROUP_TYPE / GROUP_VALUE。
-- 2. 当前没有正在执行的 DRC 汇总写入任务。
-- 3. 已备份 engine_result_db。
--
-- 说明：
-- Doris 不支持直接修改 UNIQUE KEY，因此这里采用重命名旧表、创建新表、迁移旧数据的方式。
-- 旧数据没有规则与维度分组信息，迁移时统一标记为历史迁移数据。

ALTER TABLE TB_OUT_TRADE_DRC_RESULT RENAME TB_OUT_TRADE_DRC_RESULT_BAK_20260507;

CREATE TABLE TB_OUT_TRADE_DRC_RESULT (
    BATCH_ID            VARCHAR(64),
    DATA_DATE           VARCHAR(16),
    RULE_ID             VARCHAR(128),
    GROUP_TYPE          VARCHAR(64),
    GROUP_VALUE         VARCHAR(512),
    DECOMP_FLAG         VARCHAR(32),
    AGG_LEVEL           VARCHAR(32),
    DRC_TYPE            VARCHAR(64),
    DRC_BUCKET          VARCHAR(64),
    LEGAL_ENTITY        VARCHAR(128),
    REQUEST_ID          VARCHAR(128),
    JOB_ID              VARCHAR(64),
    DRC_VALUE           DECIMAL(38, 10),
    CREATED_AT          VARCHAR(32)
)
UNIQUE KEY(BATCH_ID, DATA_DATE, RULE_ID, GROUP_TYPE, GROUP_VALUE, DECOMP_FLAG, AGG_LEVEL, DRC_TYPE, DRC_BUCKET, LEGAL_ENTITY)
DISTRIBUTED BY HASH(BATCH_ID) BUCKETS 8
PROPERTIES (
    "replication_allocation" = "tag.location.default: 1",
    "enable_unique_key_merge_on_write" = "true"
);

INSERT INTO TB_OUT_TRADE_DRC_RESULT (
    BATCH_ID,
    DATA_DATE,
    RULE_ID,
    GROUP_TYPE,
    GROUP_VALUE,
    DECOMP_FLAG,
    AGG_LEVEL,
    DRC_TYPE,
    DRC_BUCKET,
    LEGAL_ENTITY,
    REQUEST_ID,
    JOB_ID,
    DRC_VALUE,
    CREATED_AT
)
SELECT
    BATCH_ID,
    DATA_DATE,
    'LEGACY_MIGRATED',
    'TOTAL',
    'TOTAL',
    DECOMP_FLAG,
    AGG_LEVEL,
    DRC_TYPE,
    DRC_BUCKET,
    LEGAL_ENTITY,
    REQUEST_ID,
    JOB_ID,
    DRC_VALUE,
    CREATED_AT
FROM TB_OUT_TRADE_DRC_RESULT_BAK_20260507;

