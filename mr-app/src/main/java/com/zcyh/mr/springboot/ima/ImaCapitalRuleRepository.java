package com.zcyh.mr.springboot.ima;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.model.AggregationRule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * IMA 资本计算规则读取仓储。
 */
@Repository
public class ImaCapitalRuleRepository {
    private static final String RULE_TYPE_IMA = "IMA";
    private static final String RULE_TYPE_TRADE = "TRADE";

    private final JdbcTemplate engineDbJdbcTemplate;

    public ImaCapitalRuleRepository(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public LoadedRule loadImaRule(String ruleId) {
        String safeRuleId = requireText(ruleId, "IMA ruleId 不能为空");
        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(
                    "SELECT RULE_ID, RULE_TYPE, RULE_NAME, RULE_JSON FROM MR_AGG_RULE WHERE RULE_TYPE=? AND RULE_ID=?",
                    RULE_TYPE_IMA, safeRuleId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("未找到 IMA 汇总规则: " + safeRuleId);
            }
            Map<String, Object> row = rows.get(0);
            String ruleJson = trimToNull(stringValue(row.get("RULE_JSON")));
            if (ruleJson == null) {
                throw new IllegalArgumentException("IMA 汇总规则内容为空: " + safeRuleId);
            }
            AggregationRule rule = JSON.parseObject(ruleJson, AggregationRule.class);
            if (rule == null) {
                throw new IllegalArgumentException("IMA 汇总规则解析失败: " + safeRuleId);
            }
            rule.setRuleId(safeRuleId);
            rule.setRuleType(trimToNull(stringValue(row.get("RULE_TYPE"))));
            rule.setRuleName(trimToNull(stringValue(row.get("RULE_NAME"))));
            return new LoadedRule(rule, ruleJson);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 MR_AGG_RULE 中 IMA 规则失败，请确认规则表已创建且可访问: "
                    + ex.getMessage(), ex);
        }
    }

    public AggregationRule.FilterExpression loadTradeFilter(String ruleId) {
        String safeRuleId = requireText(ruleId, "filter_rule_id 不能为空");
        List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(
                "SELECT RULE_JSON FROM MR_AGG_RULE WHERE RULE_TYPE=? AND RULE_ID=?",
                RULE_TYPE_TRADE,
                safeRuleId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到TRADE过滤规则: " + safeRuleId);
        }
        String ruleJson = trimToNull(stringValue(rows.get(0).get("RULE_JSON")));
        if (ruleJson == null) {
            throw new IllegalArgumentException("TRADE过滤规则内容为空: " + safeRuleId);
        }
        JSONObject root = JSON.parseObject(ruleJson);
        Object filterTree = root == null ? null : root.get("filterTree");
        if (filterTree == null) {
            throw new IllegalArgumentException("TRADE过滤规则缺少filterTree: " + safeRuleId);
        }
        AggregationRule.FilterExpression expression = JSON.parseObject(
                JSON.toJSONString(filterTree),
                AggregationRule.FilterExpression.class);
        if (expression == null) {
            throw new IllegalArgumentException("TRADE过滤规则filterTree解析失败: " + safeRuleId);
        }
        return expression;
    }

    private static String requireText(String value, String message) {
        String safe = trimToNull(value);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class LoadedRule {
        private final AggregationRule rule;
        private final String ruleJson;

        private LoadedRule(AggregationRule rule, String ruleJson) {
            this.rule = rule;
            this.ruleJson = ruleJson;
        }

        public AggregationRule getRule() {
            return rule;
        }

        public String getRuleJson() {
            return ruleJson;
        }
    }
}
