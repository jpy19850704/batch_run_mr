package com.zcyh.mr.springboot.measurement.aggregation;

import com.zcyh.mr.springboot.input.db.InputFilterExpression;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.db.RuleDefinitionRow;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * 汇总规则解析与字段归一化辅助方法。
 */
public final class AggregationRuleSupport {

    private AggregationRuleSupport() {
    }

    public static AggregationRule parseRule(RuleDefinitionRow row, String ruleLabel) {
        AggregationRule rule = JSON.parseObject(row.getRuleJson(), AggregationRule.class);
        if (rule == null) {
            throw new IllegalArgumentException(ruleLabel + "解析失败: " + row.getRuleId());
        }
        rule.setRuleId(row.getRuleId());
        rule.setRuleType(row.getRuleType());
        rule.setRuleName(row.getRuleName());
        return rule;
    }

    public static JSONObject parseRuleJson(RuleDefinitionRow row, String ruleLabel) {
        JSONObject json = JSON.parseObject(row.getRuleJson());
        if (json == null) {
            throw new IllegalArgumentException(ruleLabel + "解析失败: " + row.getRuleId());
        }
        json.put("rule_id", row.getRuleId());
        json.put("rule_type", row.getRuleType());
        if (row.getRuleName() != null) {
            json.put("rule_name", row.getRuleName());
        }
        return json;
    }

    public static InputFilterExpression toFilterExpression(Object rawExpression) {
        if (!(rawExpression instanceof Map)) {
            return null;
        }
        Map<?, ?> row = (Map<?, ?>) rawExpression;
        InputFilterExpression expression = new InputFilterExpression();
        expression.setLogic(asTrimmedString(row.get("logic")));
        expression.setField(asTrimmedString(row.get("field")));
        String operator = asTrimmedString(row.get("operator"));
        expression.setOperator(operator);
        expression.setValue(normalizeFilterValue(operator, row.get("value")));

        Object rawChildren = row.get("children");
        if (rawChildren instanceof List) {
            List<InputFilterExpression> children = new ArrayList<InputFilterExpression>();
            for (Object child : (List<?>) rawChildren) {
                InputFilterExpression childExpression = toFilterExpression(child);
                if (childExpression != null) {
                    children.add(childExpression);
                }
            }
            expression.setChildren(children);
        }
        return expression;
    }

    public static Set<String> collectFilterFields(AggregationRule rule) {
        Set<String> fields = new LinkedHashSet<String>();
        if (rule == null) {
            return fields;
        }
        collectFilterFields(rule.getFilterTree(), fields);
        return fields;
    }

    public static Set<String> collectFilterFields(InputFilterExpression filterTree) {
        Set<String> fields = new LinkedHashSet<String>();
        collectFilterFields(filterTree, fields);
        return fields;
    }

    public static List<String> normalizeUpperFieldList(List<String> rawList) {
        List<String> result = new ArrayList<String>();
        if (rawList == null || rawList.isEmpty()) {
            return result;
        }
        for (String item : rawList) {
            addUniqueIgnoreCase(result, normalizeUpperFieldName(item));
        }
        return result;
    }

    public static String normalizeUpperFieldName(String value) {
        String safe = trimToNull(value);
        if (safe == null) {
            return null;
        }
        return safe.toUpperCase(Locale.ROOT);
    }

    public static void addUniqueIgnoreCase(List<String> values, String value) {
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            return;
        }
        for (String item : values) {
            if (safeValue.equalsIgnoreCase(item)) {
                return;
            }
        }
        values.add(safeValue);
    }

    private static void collectFilterFields(InputFilterExpression node, Set<String> fields) {
        if (node == null || fields == null) {
            return;
        }
        String field = normalizeUpperFieldName(node.getField());
        if (field != null) {
            fields.add(field);
        }
        if (node.getChildren() == null) {
            return;
        }
        for (InputFilterExpression child : node.getChildren()) {
            collectFilterFields(child, fields);
        }
    }

    private static Object normalizeFilterValue(String operator, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof List) {
            List<?> values = (List<?>) rawValue;
            if ("IN".equals(operator) || "NOT_IN".equals(operator)) {
                return new ArrayList<Object>(values);
            }
            for (Object item : values) {
                if (item != null) {
                    return item;
                }
            }
            return null;
        }
        return rawValue;
    }

    private static String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        return trimToNull(String.valueOf(value));
    }
}
