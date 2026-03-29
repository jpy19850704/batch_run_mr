-- =========================================================
-- H2 -> MySQL 迁移：初始化默认聚合规则
-- 说明：替代 H2 中的 MERGE INTO 逻辑
-- =========================================================

USE mr_engine;

INSERT INTO MR_AGG_RULE (
    rule_id, rule_type, rule_name, rule_json, modifier, created_at, updated_at
) VALUES (
    'BATCH_FRTB_DEFAULT',
    'FRTB',
    '默认 FRTB 批次汇总规则',
    '{
  "buildOrder": ["TRADER", "DESK", "PORTFOLIO", "TOTAL"],
  "dimensions": {
    "TRADER": "TRADER",
    "DESK": "DESK",
    "PORTFOLIO": "PORTFOLIO"
  },
  "groupByFields": ["PORTFOLIO", "DESK", "TRADER"],
  "sumFields": ["SENSITIVITY_VAL_INST_CURR_CNY"],
  "filters": []
}',
    'system',
    0,
    0
)
ON DUPLICATE KEY UPDATE
    rule_type = VALUES(rule_type),
    rule_name = VALUES(rule_name),
    rule_json = VALUES(rule_json),
    modifier = VALUES(modifier),
    updated_at = VALUES(updated_at);



