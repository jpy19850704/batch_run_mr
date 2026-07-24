ALTER TABLE MR_TRADE_INPUT
    MODIFY COLUMN created_at DATETIME(3) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL;

ALTER TABLE MR_MARKET_CURVE_INPUT
    MODIFY COLUMN created_at DATETIME(3) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL;

ALTER TABLE MR_MARKET_CURVE_RAW_INPUT
    MODIFY COLUMN created_at DATETIME(3) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(3) NOT NULL;

ALTER TABLE MR_RISKFACTOR_DATA
    ADD COLUMN updated_at_datetime DATETIME(3) NULL;
UPDATE MR_RISKFACTOR_DATA
SET updated_at_datetime = FROM_UNIXTIME(updated_at / 1000.0);
ALTER TABLE MR_RISKFACTOR_DATA
    DROP COLUMN updated_at,
    CHANGE COLUMN updated_at_datetime updated_at DATETIME(3) NOT NULL;

ALTER TABLE MR_RISKGROUP_DATA
    ADD COLUMN created_at_datetime DATETIME(3) NULL,
    ADD COLUMN updated_at_datetime DATETIME(3) NULL;
UPDATE MR_RISKGROUP_DATA
SET created_at_datetime = FROM_UNIXTIME(created_at / 1000.0),
    updated_at_datetime = FROM_UNIXTIME(updated_at / 1000.0);
ALTER TABLE MR_RISKGROUP_DATA
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    CHANGE COLUMN created_at_datetime created_at DATETIME(3) NOT NULL,
    CHANGE COLUMN updated_at_datetime updated_at DATETIME(3) NOT NULL;

ALTER TABLE MR_SCENARIO_RULE
    ADD COLUMN created_at_datetime DATETIME(3) NULL,
    ADD COLUMN updated_at_datetime DATETIME(3) NULL;
UPDATE MR_SCENARIO_RULE
SET created_at_datetime = FROM_UNIXTIME(created_at / 1000.0),
    updated_at_datetime = FROM_UNIXTIME(updated_at / 1000.0);
ALTER TABLE MR_SCENARIO_RULE
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    CHANGE COLUMN created_at_datetime created_at DATETIME(3) NOT NULL,
    CHANGE COLUMN updated_at_datetime updated_at DATETIME(3) NOT NULL;

ALTER TABLE MR_AGG_RULE
    ADD COLUMN created_at_datetime DATETIME(3) NULL,
    ADD COLUMN updated_at_datetime DATETIME(3) NULL;
UPDATE MR_AGG_RULE
SET created_at_datetime = FROM_UNIXTIME(created_at / 1000.0),
    updated_at_datetime = FROM_UNIXTIME(updated_at / 1000.0);
ALTER TABLE MR_AGG_RULE
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    CHANGE COLUMN created_at_datetime created_at DATETIME(3) NOT NULL,
    CHANGE COLUMN updated_at_datetime updated_at DATETIME(3) NOT NULL;

ALTER TABLE MR_AUDIT_LOG
    DROP INDEX idx_MR_AUDIT_LOG_created_at,
    ADD COLUMN created_at_datetime DATETIME(3) NULL;
UPDATE MR_AUDIT_LOG
SET created_at_datetime = FROM_UNIXTIME(created_at / 1000.0);
ALTER TABLE MR_AUDIT_LOG
    DROP COLUMN created_at,
    CHANGE COLUMN created_at_datetime created_at DATETIME(3) NOT NULL,
    ADD INDEX idx_MR_AUDIT_LOG_created_at (created_at);
