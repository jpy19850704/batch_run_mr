package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FRTB SBA 输入查询服务。
 */
@Service
public class FrtbSbaInputQueryService {
    private static final String PORTFOLIO_FLAT_VIEW = "V_TB_OUT_PORTFOLIO_HIERARCHY_FLAT";
    private static final int PORTFOLIO_LEVEL_MAX = 7;
    private static final Map<String, String> TRADE_FIELD_SQL = buildTradeFieldSqlMap();
    private static final Map<String, String> PORTFOLIO_FIELD_SQL = buildPortfolioFieldSqlMap();
    private static final Map<String, String> FRTB_RESULT_FIELD_SQL = buildFrtbResultFieldSqlMap();
    private static final String[] REQUIRED_SELECT_FIELDS = {
            "INSTRUMENT_ID",
            "PRODUCT_CODE",
            "RISK_FACTOR_ID",
            "RISK_FACTOR_VERTEX_1",
            "RISK_FACTOR_VERTEX_2",
            "RISK_FACTOR_CLASS",
            "RISK_FACTOR_BUCKET",
            "RISK_FACTOR_TYPE",
            "SENSITIVITY_TYPE",
            "SENSITIVITY_VAL_INST_CURR_CNY"
    };

    private final JdbcTemplate engineDbJdbcTemplate;
    private final JdbcTemplate engineResultDbJdbcTemplate;

    public FrtbSbaInputQueryService(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate,
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
    }

    public AggregationRule loadAggregationRule(String ruleId) {
        String safeRuleId = trimToNull(ruleId);
        if (safeRuleId == null) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(
                    "SELECT RULE_ID, RULE_TYPE, RULE_NAME, RULE_JSON "
                            + "FROM MR_AGG_RULE WHERE RULE_TYPE=? AND RULE_ID=?",
                    "FRTB", safeRuleId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("未找到 FRTB 汇总规则: " + safeRuleId);
            }
            Map<String, Object> row = rows.get(0);
            String ruleJson = trimToNull(stringValue(row.get("RULE_JSON")));
            if (ruleJson == null) {
                throw new IllegalArgumentException("FRTB 汇总规则内容为空: " + safeRuleId);
            }
            AggregationRule rule = JSON.parseObject(ruleJson, AggregationRule.class);
            if (rule == null) {
                throw new IllegalArgumentException("FRTB 汇总规则解析失败: " + safeRuleId);
            }
            rule.setRuleId(safeRuleId);
            rule.setRuleType(trimToNull(stringValue(row.get("RULE_TYPE"))));
            rule.setRuleName(trimToNull(stringValue(row.get("RULE_NAME"))));
            return rule;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 MR_AGG_RULE 失败，请确认规则表已创建且可访问: " + ex.getMessage(), ex);
        }
    }

    /**
     * 读取规则驱动汇总所需的底层 FRTB 敏感性明细，并在数据库侧下推过滤条件。
     */
    public List<Map<String, Object>> queryRuleDetailRows(String batchId, String dataDate, AggregationRule rule) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
        if (rule == null) {
            throw new IllegalArgumentException("AggregationRule 不能为空");
        }

        List<Object> params = new ArrayList<Object>();
        Set<String> selectedFields = new LinkedHashSet<String>();
        selectedFields.addAll(rule.getGroupByFields());
        for (String mappedField : rule.getDimensions().values()) {
            String safeField = trimToNull(mappedField);
            if (safeField != null) {
                selectedFields.add(safeField);
            }
        }
        selectedFields.addAll(rule.getSumFields());
        collectFilterTreeFields(rule.getFilterTree(), selectedFields);
        boolean usePortfolioFlatView = requiresPortfolioFlatView(selectedFields);

        StringBuilder sql = new StringBuilder()
                .append("SELECT ");
        appendSelectFields(sql, REQUIRED_SELECT_FIELDS, false);

        for (String field : selectedFields) {
            String safeField = normalizeField(field);
            if (safeField == null || isRequiredSelectedField(safeField)) {
                continue;
            }
            String columnExpr = resolveRuleColumn(safeField);
            if (columnExpr == null) {
                continue;
            }
            sql.append(", ").append(columnExpr).append(" AS ").append(safeField);
        }

        sql.append(" FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL d ")
                .append("INNER JOIN TB_OUT_TRADE_RESULT_DETAIL r ")
                .append("ON r.BATCH_ID = d.BATCH_ID ")
                .append("AND r.DATA_DATE = d.DATA_DATE ")
                .append("AND r.INSTRUMENT_ID = d.INSTRUMENT_ID ")
                .append(usePortfolioFlatView
                        ? "LEFT JOIN " + PORTFOLIO_FLAT_VIEW + " p ON p.BATCH_ID = d.BATCH_ID AND p.DATA_DATE = d.DATA_DATE AND p.PORTFOLIO_CODE = r.PORTFOLIO "
                        : "")
                .append("WHERE d.BATCH_ID = ? AND d.DATA_DATE = ?");
        params.add(safeBatchId);
        params.add(safeDataDate);

        AggregationFilterSqlBuilder.appendWhereClause(sql, params, rule, new AggregationFilterSqlBuilder.ColumnResolver() {
            @Override
            public String resolve(String field) {
                return resolveRuleColumn(field);
            }
        });
        sql.append(" ORDER BY r.INSTRUMENT_ID, d.RISK_FACTOR_CLASS, d.RISK_FACTOR_BUCKET, d.RISK_FACTOR_ID, d.SENSITIVITY_TYPE");

        try {
            return engineResultDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 FRTB 汇总底层明细失败，请确认明细表已生成且可访问: " + ex.getMessage(), ex);
        }
    }

    public Map<String, List<FrtbInput>> groupByTreeIdAndGroupValue(List<FrtbInput> inputList) {
        Map<String, List<FrtbInput>> grouped = new LinkedHashMap<String, List<FrtbInput>>();
        if (inputList == null || inputList.isEmpty()) {
            return grouped;
        }

        for (FrtbInput input : inputList) {
            // 这里直接沿用上游给定的 tree_id / group_value 分组。
            // 上游约束：
            // 1. 每个 tree_id 下已经提供一条 GROUP_TYPE=TOTAL、GROUP_VALUE=TOTAL 的总维度输入；
            // 2. group_type / group_value 表示上游定义好的维度标签，不在此处重新构造 TOTAL 或重切分资本口径。
            String taskKey = buildTaskKey(input.getTreeId(), input.getGroupValue());
            List<FrtbInput> items = grouped.get(taskKey);
            if (items == null) {
                items = new ArrayList<FrtbInput>();
                grouped.put(taskKey, items);
            }
            items.add(input);
        }
        return grouped;
    }

    private static String buildTaskKey(String treeId, String groupValue) {
        String safeTreeId = trimToNull(treeId);
        String safeGroupValue = trimToNull(groupValue);
        // taskKey 仅用于承接上游已组织好的 tree 维度标签。
        // TOTAL 维度由上游提供，因此这里不额外拼接或派生新的 TOTAL 任务键。
        return (safeTreeId == null ? "__EMPTY_TREE__" : safeTreeId)
                + "|"
                + (safeGroupValue == null ? "__EMPTY_GROUP__" : safeGroupValue);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String resolveRuleColumn(String field) {
        String safeField = normalizeField(field);
        if (safeField == null) {
            return null;
        }
        return resolveColumnFromMaps(safeField);
    }

    private static boolean isRequiredSelectedField(String field) {
        String safeField = normalizeField(field);
        if (safeField == null) {
            return false;
        }
        for (String requiredField : REQUIRED_SELECT_FIELDS) {
            if (requiredField.equals(safeField)) {
                return true;
            }
        }
        return false;
    }

    private static void collectFilterTreeFields(AggregationRule.FilterExpression node, Set<String> fields) {
        if (node == null || fields == null) {
            return;
        }
        String field = trimToNull(node.getField());
        if (field != null) {
            fields.add(field);
        }
        if (node.getChildren() == null) {
            return;
        }
        for (AggregationRule.FilterExpression child : node.getChildren()) {
            collectFilterTreeFields(child, fields);
        }
    }

    private static boolean requiresPortfolioFlatView(Set<String> fields) {
        if (fields == null) {
            return false;
        }
        for (String field : fields) {
            String safeField = trimToNull(field);
            if (safeField == null) {
                continue;
            }
            for (int i = 1; i <= PORTFOLIO_LEVEL_MAX; i++) {
                if (("PORTFOLIO_CODE_" + i).equalsIgnoreCase(safeField)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void appendSelectFields(StringBuilder sql, String[] fields, boolean appendCommaPrefix) {
        boolean appended = false;
        for (String field : fields) {
            String safeField = normalizeField(field);
            String expression = resolveColumnFromMaps(safeField);
            if (safeField == null || expression == null) {
                throw new IllegalArgumentException("FRTB SBA 必选字段未配置 SQL 映射: " + field);
            }
            if (appendCommaPrefix || appended) {
                sql.append(", ");
            }
            sql.append(expression).append(" AS ").append(safeField);
            appended = true;
        }
    }

    private static String resolveColumnFromMaps(String safeField) {
        if (safeField == null) {
            return null;
        }
        String expression = TRADE_FIELD_SQL.get(safeField);
        if (expression != null) {
            return expression;
        }
        expression = PORTFOLIO_FIELD_SQL.get(safeField);
        if (expression != null) {
            return expression;
        }
        return FRTB_RESULT_FIELD_SQL.get(safeField);
    }

    private static Map<String, String> buildTradeFieldSqlMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("INSTRUMENT_ID", "r.INSTRUMENT_ID");
        map.put("PRODUCT_CODE", "r.PRODUCT_CODE");
        map.put("PORTFOLIO", "r.PORTFOLIO");
        map.put("DESK", "r.DESK");
        map.put("TRADER", "r.TRADER");
        map.put("VALUATION_CCY", "r.VALUATION_CCY");
        return map;
    }

    private static Map<String, String> buildPortfolioFieldSqlMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 1; i <= PORTFOLIO_LEVEL_MAX; i++) {
            map.put("PORTFOLIO_CODE_" + i, "p.PORTFOLIO_CODE_" + i);
        }
        return map;
    }

    private static Map<String, String> buildFrtbResultFieldSqlMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("RISK_FACTOR_ID", "d.RISK_FACTOR_ID");
        map.put("RISK_FACTOR_VERTEX_1", "d.RISK_FACTOR_VERTEX_1");
        map.put("RISK_FACTOR_VERTEX_2", "d.RISK_FACTOR_VERTEX_2");
        map.put("RISK_FACTOR_CLASS", "d.RISK_FACTOR_CLASS");
        map.put("RISK_FACTOR_BUCKET", "d.RISK_FACTOR_BUCKET");
        map.put("RISK_FACTOR_TYPE", "d.RISK_FACTOR_TYPE");
        map.put("SENSITIVITY_TYPE", "d.SENSITIVITY_TYPE");
        map.put("SENSITIVITY_VAL_INST_CURR_CNY", "d.SENSITIVITY_VAL_INST_CURR_CNY");
        map.put("DATA_DATE", "d.DATA_DATE");
        map.put("BATCH_ID", "d.BATCH_ID");
        return map;
    }

    private static String normalizeField(String field) {
        String safeField = trimToNull(field);
        return safeField == null ? null : safeField.toUpperCase(java.util.Locale.ROOT);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
