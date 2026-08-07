UPDATE MR_MARKET_CURVE_RAW_INPUT
SET curve_content_text = JSON_SET(
        CAST(curve_content_text AS JSON),
        '$.DATA_DATE',
        DATE_FORMAT(data_date, '%Y-%m-%d')
    ),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE JSON_UNQUOTE(JSON_EXTRACT(curve_content_text, '$.DATA_DATE')) REGEXP '^[0-9]{8}$';

UPDATE MR_MARKET_CURVE_RAW_INPUT
SET curve_id = JSON_UNQUOTE(JSON_EXTRACT(curve_content_text, '$.CURVE_ID')),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE curve_id <> JSON_UNQUOTE(JSON_EXTRACT(curve_content_text, '$.CURVE_ID'));

ALTER TABLE MR_AUDIT_LOG
    MODIFY COLUMN message TEXT NULL;
