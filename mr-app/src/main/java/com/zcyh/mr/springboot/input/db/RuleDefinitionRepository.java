package com.zcyh.mr.springboot.input.db;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * 规则定义查询仓储。
 */
@Repository
public class RuleDefinitionRepository {
    private final JdbcTemplate jdbcTemplate;

    public RuleDefinitionRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RuleDefinitionRow findRequired(String ruleType, String ruleId, String ruleLabel) {
        String safeRuleType = requireText(ruleType, "ruleType 不能为空");
        String safeRuleId = requireText(ruleId, "ruleId 不能为空");
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT RULE_ID, RULE_TYPE, RULE_NAME, RULE_JSON "
                            + "FROM MR_AGG_RULE WHERE RULE_TYPE=? AND RULE_ID=?",
                    safeRuleType,
                    safeRuleId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("未找到 " + ruleLabel + ": " + safeRuleId);
            }
            Map<String, Object> row = rows.get(0);
            String ruleJson = trimToNull(stringValue(row.get("RULE_JSON")));
            if (ruleJson == null) {
                throw new IllegalArgumentException(ruleLabel + "内容为空: " + safeRuleId);
            }
            return new RuleDefinitionRow(
                    safeRuleId,
                    trimToNull(stringValue(row.get("RULE_TYPE"))),
                    trimToNull(stringValue(row.get("RULE_NAME"))),
                    ruleJson);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 MR_AGG_RULE 中 " + ruleLabel
                    + "失败，请确认规则表已创建且可访问: " + ex.getMessage(), ex);
        }
    }

    private static String requireText(String value, String message) {
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            throw new IllegalArgumentException(message);
        }
        return safeValue;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
