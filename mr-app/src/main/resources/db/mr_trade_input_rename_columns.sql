-- MR_TRADE_INPUT 字段命名统一迁移脚本。
-- 执行前确认当前库仍为旧字段 trade_id/product_type。
-- 当前标准字段名已统一为 instrument_id/product_code，本脚本仅用于旧库一次性升级。

ALTER TABLE MR_TRADE_INPUT
    DROP INDEX uk_mr_trade_input;

ALTER TABLE MR_TRADE_INPUT
    DROP INDEX idx_mr_trade_input_product;

ALTER TABLE MR_TRADE_INPUT
    CHANGE COLUMN trade_id instrument_id VARCHAR(128) NOT NULL;

ALTER TABLE MR_TRADE_INPUT
    CHANGE COLUMN product_type product_code VARCHAR(64) NOT NULL;

ALTER TABLE MR_TRADE_INPUT
    ADD CONSTRAINT uk_mr_trade_input UNIQUE (data_date, instrument_id, version_no);

CREATE INDEX idx_mr_trade_input_product
    ON MR_TRADE_INPUT (product_code);
