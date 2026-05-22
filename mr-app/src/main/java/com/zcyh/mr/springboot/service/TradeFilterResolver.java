package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.model.BatchTradeFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 批次交易过滤规则解析器。
 */
@Service
public class TradeFilterResolver {
    private static final String SOURCE_TYPE_INLINE = "INLINE";
    private static final String SOURCE_TYPE_RULE = "RULE";
    private static final String RULE_TYPE_TRADE = "TRADE";
    private static final int MAX_FILTER_TREE_DEPTH = 5;
    private static final int MAX_FILTER_TREE_NODES = 100;
    private static final int MAX_IN_VALUES = 1000;

    private static final Set<String> SUPPORTED_FIELDS = new HashSet<String>(Arrays.asList(
            "INSTRUMENT_ID",
            "PRODUCT_CODE",
            "PORTFOLIO",
            "DESK",
            "TRADER",
            "PORTFOLIO_CODE_1",
            "PORTFOLIO_CODE_2",
            "PORTFOLIO_CODE_3",
            "PORTFOLIO_CODE_4",
            "PORTFOLIO_CODE_5",
            "PORTFOLIO_CODE_6",
            "PORTFOLIO_CODE_7"
    ));
    private static final Set<String> SUPPORTED_OPERATORS = new HashSet<String>(Arrays.asList(
            "=",
            "!=",
            "in",
            "not_in",
            "contains",
            "not_contains",
            "is_null",
            "is_not_null"
    ));

    private final JdbcTemplate engineDbJdbcTemplate;

    public TradeFilterResolver(@Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public AggregationRule.FilterExpression resolve(BatchTradeFilter filter) {
        if (filter == null) {
            return null;
        }
        String sourceType = trimToNull(filter.getSourceType());
        if (sourceType == null) {
            throw new IllegalArgumentException("trade_filter.source_type 不能为空");
        }
        sourceType = sourceType.toUpperCase(Locale.ROOT);
        AggregationRule.FilterExpression filterTree;
        if (SOURCE_TYPE_INLINE.equals(sourceType)) {
            if (trimToNull(filter.getRuleId()) != null) {
                throw new IllegalArgumentException("trade_filter.source_type=INLINE 时不能传 rule_id");
            }
            filterTree = filter.getFilterTree();
            if (filterTree == null) {
                throw new IllegalArgumentException("trade_filter.source_type=INLINE 时 filter_tree 不能为空");
            }
        } else if (SOURCE_TYPE_RULE.equals(sourceType)) {
            if (filter.getFilterTree() != null) {
                throw new IllegalArgumentException("trade_filter.source_type=RULE 时不能传 filter_tree");
            }
            String ruleId = trimToNull(filter.getRuleId());
            if (ruleId == null) {
                throw new IllegalArgumentException("trade_filter.source_type=RULE 时 rule_id 不能为空");
            }
            filterTree = loadRuleFilterTree(ruleId);
        } else {
            throw new IllegalArgumentException("trade_filter.source_type 仅支持 INLINE/RULE: " + filter.getSourceType());
        }
        validateFilterExpression(filterTree, "trade_filter.filter_tree", 1, new int[]{0});
        return filterTree;
    }

    private AggregationRule.FilterExpression loadRuleFilterTree(String ruleId) {
        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(
                    "SELECT RULE_JSON FROM MR_AGG_RULE WHERE RULE_TYPE=? AND RULE_ID=?",
                    RULE_TYPE_TRADE,
                    ruleId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("未找到 TRADE 交易过滤规则: " + ruleId);
            }
            String ruleJson = trimToNull(stringValue(rows.get(0).get("RULE_JSON")));
            if (ruleJson == null) {
                throw new IllegalArgumentException("TRADE 交易过滤规则内容为空: " + ruleId);
            }
            JSONObject ruleObject = JSON.parseObject(ruleJson);
            Object filterTreeValue = ruleObject == null ? null : ruleObject.get("filter_tree");
            if (filterTreeValue == null) {
                throw new IllegalArgumentException("TRADE 交易过滤规则缺少 filter_tree: " + ruleId);
            }
            AggregationRule.FilterExpression filterTree = JSON.parseObject(
                    JSON.toJSONString(filterTreeValue),
                    AggregationRule.FilterExpression.class);
            if (filterTree == null) {
                throw new IllegalArgumentException("TRADE 交易过滤规则 filter_tree 解析失败: " + ruleId);
            }
            return filterTree;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 MR_AGG_RULE 中 TRADE 规则失败，请确认规则表已创建且可访问: "
                    + ex.getMessage(), ex);
        }
    }

    private static void validateFilterExpression(AggregationRule.FilterExpression node,
                                                 String path,
                                                 int depth,
                                                 int[] nodeCount) {
        if (node == null) {
            throw new IllegalArgumentException(path + " 不能为空");
        }
        if (depth > MAX_FILTER_TREE_DEPTH) {
            throw new IllegalArgumentException(path + " 嵌套层级超过上限: " + MAX_FILTER_TREE_DEPTH);
        }
        nodeCount[0]++;
        if (nodeCount[0] > MAX_FILTER_TREE_NODES) {
            throw new IllegalArgumentException("trade_filter.filter_tree 节点数量超过上限: " + MAX_FILTER_TREE_NODES);
        }

        String op = trimToNull(node.getOp());
        String field = trimToNull(node.getField());
        if (op != null) {
            if (field != null) {
                throw new IllegalArgumentException(path + " 不能同时包含 op 和 field");
            }
            String normalizedOp = op.toLowerCase(Locale.ROOT);
            if (!"and".equals(normalizedOp) && !"or".equals(normalizedOp)) {
                throw new IllegalArgumentException(path + ".op 仅支持 and/or: " + op);
            }
            node.setOp(normalizedOp);
            List<AggregationRule.FilterExpression> children = node.getChildren();
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException(path + ".children 不能为空");
            }
            for (int i = 0; i < children.size(); i++) {
                validateFilterExpression(children.get(i), path + ".children[" + i + "]", depth + 1, nodeCount);
            }
            return;
        }

        if (field == null) {
            throw new IllegalArgumentException(path + ".field 不能为空");
        }
        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            throw new IllegalArgumentException(path + " 条件节点不能包含 children");
        }
        String normalizedField = field.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_FIELDS.contains(normalizedField)) {
            throw new IllegalArgumentException(path + ".field 不支持: " + field);
        }
        node.setField(normalizedField);

        String operator = normalizeOperator(node.getOperator());
        if (operator == null) {
            throw new IllegalArgumentException(path + ".operator 不支持: " + node.getOperator());
        }
        node.setOperator(operator);
        validateFilterValue(node, path);
    }

    private static void validateFilterValue(AggregationRule.FilterExpression node, String path) {
        String operator = node.getOperator();
        Object value = node.getValue();
        if ("is_null".equals(operator) || "is_not_null".equals(operator)) {
            if (!isEmptyValue(value)) {
                throw new IllegalArgumentException(path + "." + operator + " 不需要 value");
            }
            return;
        }
        List<Object> values = asList(value);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(path + ".value 不能为空");
        }
        if (("in".equals(operator) || "not_in".equals(operator)) && values.size() > MAX_IN_VALUES) {
            throw new IllegalArgumentException(path + ".value 数量超过上限: " + MAX_IN_VALUES);
        }
        if (!"in".equals(operator) && !"not_in".equals(operator) && values.size() != 1) {
            throw new IllegalArgumentException(path + ".value 只能包含一个值");
        }
        for (Object item : values) {
            if (trimToNull(stringValue(item)) == null) {
                throw new IllegalArgumentException(path + ".value 不能包含空值");
            }
        }
    }

    private static String normalizeOperator(String operator) {
        String safe = trimToNull(operator);
        if (safe == null) {
            return null;
        }
        if ("=".equals(safe) || "!=".equals(safe)) {
            return safe;
        }
        String lower = safe.toLowerCase(Locale.ROOT);
        return SUPPORTED_OPERATORS.contains(lower) ? lower : null;
    }

    private static boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).isEmpty();
        }
        if (value.getClass().isArray()) {
            return Array.getLength(value) == 0;
        }
        return trimToNull(String.valueOf(value)) == null;
    }

    private static List<Object> asList(Object value) {
        java.util.ArrayList<Object> values = new java.util.ArrayList<Object>();
        if (value == null) {
            return values;
        }
        if (value instanceof Collection) {
            values.addAll((Collection<?>) value);
            return values;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
            return values;
        }
        values.add(value);
        return values;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
