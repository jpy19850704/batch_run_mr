package com.zcyh.mr.springboot.prepare.filter;

import com.zcyh.mr.springboot.model.AggregationRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * 汇总规则过滤条件 SQL 构建器。
 */
public class AggregationFilterSqlBuilder {
    private static final int MAX_IN_VALUES = 1000;

    public interface ColumnResolver {
        String resolve(String field);
    }

    private AggregationFilterSqlBuilder() {
    }

    public static void appendWhereClause(StringBuilder sql,
                                         List<Object> params,
                                         AggregationRule rule,
                                         ColumnResolver columnResolver) {
        if (rule == null) {
            return;
        }
        AggregationRule.FilterExpression filterTree = rule.getFilterTree();
        if (filterTree != null) {
            sql.append(" AND ").append(buildExpression(filterTree, params, columnResolver));
        }
    }

    private static String buildExpression(AggregationRule.FilterExpression node,
                                          List<Object> params,
                                          ColumnResolver columnResolver) {
        String logic = trimToNull(node.getLogic());
        if (logic != null) {
            String normalizedLogic = logic.trim();
            if (!"AND".equals(normalizedLogic) && !"OR".equals(normalizedLogic)) {
                throw new IllegalArgumentException("filterTree.logic 仅支持 AND/OR: " + logic);
            }
            List<AggregationRule.FilterExpression> children = node.getChildren();
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("filterTree.children 不能为空");
            }
            List<String> parts = new ArrayList<String>();
            for (AggregationRule.FilterExpression child : children) {
                if (child == null) {
                    throw new IllegalArgumentException("filterTree.children 不能包含空节点");
                }
                parts.add(buildExpression(child, params, columnResolver));
            }
            String joiner = " " + normalizedLogic + " ";
            return "(" + String.join(joiner, parts) + ")";
        }
        return buildCondition(node.getField(), node.getOperator(), node.getValue(), params, columnResolver);
    }

    private static String buildCondition(String field,
                                         String operator,
                                         Object value,
                                         List<Object> params,
                                         ColumnResolver columnResolver) {
        String safeField = trimToNull(field);
        String safeOperator = trimToNull(operator);
        String column = columnResolver == null ? null : columnResolver.resolve(safeField);
        if (column == null || safeOperator == null) {
            throw new IllegalArgumentException("不支持的过滤字段或操作符: " + safeField + " / " + safeOperator);
        }

        String normalizedOperator = safeOperator;
        if ("EQ".equals(normalizedOperator)) {
            params.add(value);
            return column + " = ?";
        }
        if ("NE".equals(normalizedOperator)) {
            params.add(value);
            return column + " <> ?";
        }
        if ("GT".equals(normalizedOperator) || "GE".equals(normalizedOperator)
                || "LT".equals(normalizedOperator) || "LE".equals(normalizedOperator)) {
            params.add(value);
            return column + " " + compareSqlOperator(normalizedOperator) + " ?";
        }
        if ("CONTAINS".equals(normalizedOperator) || "NOT_CONTAINS".equals(normalizedOperator)) {
            params.add("%" + String.valueOf(value) + "%");
            return column + ("CONTAINS".equals(normalizedOperator) ? " LIKE ?" : " NOT LIKE ?");
        }
        if ("IN".equals(normalizedOperator) || "NOT_IN".equals(normalizedOperator)) {
            List<Object> values = asList(value);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("过滤条件 " + safeField + " 的取值不能为空");
            }
            if (values.size() > MAX_IN_VALUES) {
                throw new IllegalArgumentException("过滤条件 " + safeField + " 的取值数量超过上限: " + MAX_IN_VALUES);
            }
            return buildInCondition(column, "IN".equals(normalizedOperator), values, params);
        }
        if ("IS_NULL".equals(normalizedOperator)) {
            return column + " IS NULL";
        }
        if ("IS_NOT_NULL".equals(normalizedOperator)) {
            return column + " IS NOT NULL";
        }
        throw new IllegalArgumentException("不支持的过滤操作符: " + safeOperator);
    }

    private static String compareSqlOperator(String operator) {
        if ("GT".equals(operator)) return ">";
        if ("GE".equals(operator)) return ">=";
        if ("LT".equals(operator)) return "<";
        if ("LE".equals(operator)) return "<=";
        throw new IllegalArgumentException("非法比较操作符: " + operator);
    }

    private static String buildInCondition(String column, boolean positive, List<Object> values, List<Object> params) {
        StringBuilder sql = new StringBuilder();
        sql.append(column).append(positive ? " IN (" : " NOT IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(values.get(i));
        }
        sql.append(")");
        return sql.toString();
    }

    private static List<Object> asList(Object value) {
        List<Object> values = new ArrayList<Object>();
        if (value == null) {
            return values;
        }
        if (value instanceof Collection) {
            values.addAll((Collection<?>) value);
            return values;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                values.add(java.lang.reflect.Array.get(value, i));
            }
            return values;
        }
        values.add(value);
        return values;
    }

}
