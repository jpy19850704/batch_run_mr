package com.zcyh.mr.springboot.input.rule;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.prepare.rule.AggregationRuleSupport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * 汇总规则表读取服务。
 */
@Service
public class AggregationRuleProvider {
    private final JdbcTemplate engineDbJdbcTemplate;

    public AggregationRuleProvider(@Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public JSONObject loadRuleJson(String ruleType, String ruleId, String ruleLabel) {
        RuleRow row = loadRuleRow(ruleType, ruleId, ruleLabel);
        JSONObject json = JSON.parseObject(row.ruleJson);
        if (json == null) {
            throw new IllegalArgumentException(ruleLabel + "解析失败: " + row.ruleId);
        }
        json.put("rule_id", row.ruleId);
        json.put("rule_type", row.ruleType);
        if (row.ruleName != null) {
            json.put("rule_name", row.ruleName);
        }
        return json;
    }

    public AggregationRule loadRule(String ruleType, String ruleId, String ruleLabel) {
        RuleRow row = loadRuleRow(ruleType, ruleId, ruleLabel);
        AggregationRule rule = JSON.parseObject(row.ruleJson, AggregationRule.class);
        if (rule == null) {
            throw new IllegalArgumentException(ruleLabel + "解析失败: " + row.ruleId);
        }
        rule.setRuleId(row.ruleId);
        rule.setRuleType(row.ruleType);
        rule.setRuleName(row.ruleName);
        return rule;
    }

    public AggregationRule.FilterExpression loadFilterTree(String ruleType, String ruleId, String ruleLabel) {
        JSONObject json = loadRuleJson(ruleType, ruleId, ruleLabel);
        Object filterTreeValue = json.get("filterTree");
        if (filterTreeValue == null) {
            throw new IllegalArgumentException(ruleLabel + "缺少 filterTree: " + ruleId);
        }
        AggregationRule.FilterExpression filterTree = AggregationRuleSupport.toFilterExpression(filterTreeValue);
        if (filterTree == null) {
            throw new IllegalArgumentException(ruleLabel + "filterTree 解析失败: " + ruleId);
        }
        return filterTree;
    }

    private RuleRow loadRuleRow(String ruleType, String ruleId, String ruleLabel) {
        String safeRuleType = trimToNull(ruleType);
        String safeRuleId = trimToNull(ruleId);
        if (safeRuleType == null) {
            throw new IllegalArgumentException("ruleType 不能为空");
        }
        if (safeRuleId == null) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(
                    "SELECT RULE_ID, RULE_TYPE, RULE_NAME, RULE_JSON FROM MR_AGG_RULE WHERE RULE_TYPE=? AND RULE_ID=?",
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
            return new RuleRow(
                    safeRuleId,
                    trimToNull(stringValue(row.get("RULE_TYPE"))),
                    trimToNull(stringValue(row.get("RULE_NAME"))),
                    ruleJson);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 MR_AGG_RULE 中 " + ruleLabel + "失败，请确认规则表已创建且可访问: "
                    + ex.getMessage(), ex);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class RuleRow {
        private final String ruleId;
        private final String ruleType;
        private final String ruleName;
        private final String ruleJson;

        private RuleRow(String ruleId, String ruleType, String ruleName, String ruleJson) {
            this.ruleId = ruleId;
            this.ruleType = ruleType;
            this.ruleName = ruleName;
            this.ruleJson = ruleJson;
        }
    }
}
