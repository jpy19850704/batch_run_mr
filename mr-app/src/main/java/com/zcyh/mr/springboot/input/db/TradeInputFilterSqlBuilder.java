package com.zcyh.mr.springboot.input.db;


import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * 交易过滤树 SQL 构建器。
 */
public final class TradeInputFilterSqlBuilder {
    private static final int MAX_IN_VALUES = 1000;

    private TradeInputFilterSqlBuilder() {
    }

    public static boolean usesPortfolioFlatView(InputFilterExpression filterTree) {
        if (filterTree == null) {
            return false;
        }
        String field = trimToNull(filterTree.getField());
        if (field != null && field.toUpperCase(Locale.ROOT).startsWith("PORTFOLIO_CODE_")) {
            return true;
        }
        List<InputFilterExpression> children = filterTree.getChildren();
        if (children == null || children.isEmpty()) {
            return false;
        }
        for (InputFilterExpression child : children) {
            if (usesPortfolioFlatView(child)) {
                return true;
            }
        }
        return false;
    }

    public static void appendWhereClause(StringBuilder sql,
                                         List<Object> params,
                                         InputFilterExpression filterTree) {
        if (filterTree == null) {
            return;
        }
        sql.append(" AND ").append(buildExpression(filterTree, params));
    }

    private static String buildExpression(InputFilterExpression node, List<Object> params) {
        String logic = trimToNull(node.getLogic());
        if (logic != null) {
            String normalizedLogic = logic.trim();
            if (!"AND".equals(normalizedLogic) && !"OR".equals(normalizedLogic)) {
                throw new IllegalArgumentException("tradeFilter.filterTree.logic 仅支持 AND/OR: " + logic);
            }
            List<InputFilterExpression> children = node.getChildren();
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("tradeFilter.filterTree.children 不能为空");
            }
            List<String> parts = new ArrayList<String>();
            for (InputFilterExpression child : children) {
                if (child == null) {
                    throw new IllegalArgumentException("tradeFilter.filterTree.children 不能包含空节点");
                }
                parts.add(buildExpression(child, params));
            }
            return "(" + String.join(" " + normalizedLogic + " ", parts) + ")";
        }
        return buildCondition(node.getField(), node.getOperator(), node.getValue(), params);
    }

    private static String buildCondition(String field, String operator, Object value, List<Object> params) {
        String safeField = requireText(field, "tradeFilter.filterTree.field");
        String safeOperator = requireText(operator, "tradeFilter.filterTree.operator");
        String column = resolveColumn(safeField);
        if ("EQ".equals(safeOperator)) {
            params.add(requireSingleValue(value, safeField));
            return column + " = ?";
        }
        if ("NE".equals(safeOperator)) {
            params.add(requireSingleValue(value, safeField));
            return column + " <> ?";
        }
        if ("CONTAINS".equals(safeOperator) || "NOT_CONTAINS".equals(safeOperator)) {
            params.add("%" + escapeLike(requireSingleValue(value, safeField)) + "%");
            return column + ("CONTAINS".equals(safeOperator) ? " LIKE ? ESCAPE '#'" : " NOT LIKE ? ESCAPE '#'");
        }
        if ("IN".equals(safeOperator) || "NOT_IN".equals(safeOperator)) {
            List<String> values = requireValueList(value, safeField);
            if (values.size() > MAX_IN_VALUES) {
                throw new IllegalArgumentException("交易过滤字段 " + safeField + " 的取值数量超过上限: " + MAX_IN_VALUES);
            }
            return buildInCondition(column, "IN".equals(safeOperator), values, params);
        }
        if ("IS_NULL".equals(safeOperator)) {
            return column + " IS NULL";
        }
        if ("IS_NOT_NULL".equals(safeOperator)) {
            return column + " IS NOT NULL";
        }
        throw new IllegalArgumentException("不支持的交易过滤操作符: " + safeOperator);
    }

    private static String buildInCondition(String column, boolean positive, List<String> values, List<Object> params) {
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

    private static String resolveColumn(String field) {
        String safeField = requireText(field, "tradeFilter.filterTree.field").toUpperCase(Locale.ROOT);
        if ("INSTRUMENT_ID".equals(safeField)) {
            return "t.instrument_id";
        }
        if ("PRODUCT_CODE".equals(safeField)) {
            return "t.product_code";
        }
        if ("PORTFOLIO".equals(safeField)) {
            return "t.portfolio";
        }
        if ("DESK".equals(safeField)) {
            return "t.desk";
        }
        if ("TRADER".equals(safeField)) {
            return "t.trader";
        }
        if ("PORTFOLIO_CODE_1".equals(safeField)
                || "PORTFOLIO_CODE_2".equals(safeField)
                || "PORTFOLIO_CODE_3".equals(safeField)
                || "PORTFOLIO_CODE_4".equals(safeField)
                || "PORTFOLIO_CODE_5".equals(safeField)
                || "PORTFOLIO_CODE_6".equals(safeField)
                || "PORTFOLIO_CODE_7".equals(safeField)) {
            return "p." + safeField;
        }
        throw new IllegalArgumentException("不支持的交易过滤字段: " + field);
    }

    private static String requireSingleValue(Object value, String field) {
        List<String> values = requireValueList(value, field);
        if (values.size() != 1) {
            throw new IllegalArgumentException("交易过滤字段 " + field + " 只能传一个取值");
        }
        return values.get(0);
    }

    private static List<String> requireValueList(Object value, String field) {
        List<String> values = new ArrayList<String>();
        for (Object item : asList(value)) {
            String safeValue = trimToNull(item == null ? null : String.valueOf(item));
            if (safeValue == null) {
                throw new IllegalArgumentException("交易过滤字段 " + field + " 的取值不能为空");
            }
            values.add(safeValue);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("交易过滤字段 " + field + " 的取值不能为空");
        }
        return values;
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
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
            return values;
        }
        values.add(value);
        return values;
    }

    private static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' || c == '_' || c == '#') {
                escaped.append('#');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static String requireText(String txt, String fieldName) {
        String value = trimToNull(txt);
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

}
