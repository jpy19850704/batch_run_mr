package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.input.rule.AggregationRuleProvider;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.model.BatchTradeFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

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
            "EQ",
            "NE",
            "IN",
            "NOT_IN",
            "CONTAINS",
            "NOT_CONTAINS",
            "IS_NULL",
            "IS_NOT_NULL"
    ));

    private final AggregationRuleProvider aggregationRuleProvider;

    @Autowired
    public TradeFilterResolver(AggregationRuleProvider aggregationRuleProvider) {
        this.aggregationRuleProvider = aggregationRuleProvider;
    }

    public AggregationRule.FilterExpression resolve(BatchTradeFilter filter) {
        if (filter == null) {
            return null;
        }
        String sourceType = trimToNull(filter.getSourceType());
        if (sourceType == null) {
            throw new IllegalArgumentException("tradeFilter.sourceType 不能为空");
        }
        AggregationRule.FilterExpression filterTree;
        if (SOURCE_TYPE_INLINE.equals(sourceType)) {
            if (trimToNull(filter.getRuleId()) != null) {
                throw new IllegalArgumentException("tradeFilter.sourceType=INLINE 时不能传 ruleId");
            }
            filterTree = filter.getFilterTree();
            if (filterTree == null) {
                throw new IllegalArgumentException("tradeFilter.sourceType=INLINE 时 filterTree 不能为空");
            }
        } else if (SOURCE_TYPE_RULE.equals(sourceType)) {
            if (filter.getFilterTree() != null) {
                throw new IllegalArgumentException("tradeFilter.sourceType=RULE 时不能传 filterTree");
            }
            String ruleId = trimToNull(filter.getRuleId());
            if (ruleId == null) {
                throw new IllegalArgumentException("tradeFilter.sourceType=RULE 时 ruleId 不能为空");
            }
            filterTree = loadRuleFilterTree(ruleId);
        } else {
            throw new IllegalArgumentException("tradeFilter.sourceType 仅支持 INLINE/RULE: " + filter.getSourceType());
        }
        validateFilterExpression(filterTree, "tradeFilter.filterTree", 1, new int[]{0});
        return filterTree;
    }

    private AggregationRule.FilterExpression loadRuleFilterTree(String ruleId) {
        return aggregationRuleProvider.loadFilterTree(RULE_TYPE_TRADE, ruleId, "TRADE 交易过滤规则");
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
            throw new IllegalArgumentException("tradeFilter.filterTree 节点数量超过上限: " + MAX_FILTER_TREE_NODES);
        }

        String logic = trimToNull(node.getLogic());
        String field = trimToNull(node.getField());
        if (logic != null) {
            if (field != null) {
                throw new IllegalArgumentException(path + " 不能同时包含 logic 和 field");
            }
            String normalizedLogic = logic.trim();
            if (!"AND".equals(normalizedLogic) && !"OR".equals(normalizedLogic)) {
                throw new IllegalArgumentException(path + ".logic 仅支持 AND/OR: " + logic);
            }
            node.setLogic(normalizedLogic);
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
        if ("IS_NULL".equals(operator) || "IS_NOT_NULL".equals(operator)) {
            if (!isEmptyValue(value)) {
                throw new IllegalArgumentException(path + "." + operator + " 不需要 value");
            }
            return;
        }
        List<Object> values = asList(value);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(path + ".value 不能为空");
        }
        if (("IN".equals(operator) || "NOT_IN".equals(operator)) && values.size() > MAX_IN_VALUES) {
            throw new IllegalArgumentException(path + ".value 数量超过上限: " + MAX_IN_VALUES);
        }
        if (!"IN".equals(operator) && !"NOT_IN".equals(operator) && values.size() != 1) {
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
        return SUPPORTED_OPERATORS.contains(safe) ? safe : null;
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
}
