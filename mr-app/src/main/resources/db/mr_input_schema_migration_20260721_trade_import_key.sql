ALTER TABLE MR_TRADE_INPUT
    DROP INDEX uk_mr_trade_input,
    ADD CONSTRAINT uk_mr_trade_input UNIQUE (data_date, instrument_id, product_code);
