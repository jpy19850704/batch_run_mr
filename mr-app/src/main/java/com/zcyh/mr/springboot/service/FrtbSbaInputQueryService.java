package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final RowMapper<FrtbInput> ROW_MAPPER = new RowMapper<FrtbInput>() {
        @Override
        public FrtbInput mapRow(ResultSet rs, int rowNum) throws SQLException {
            FrtbInput input = new FrtbInput();
            input.setTreeId(trimToNull(rs.getString("TREE_ID")));
            input.setGroupType(trimToNull(rs.getString("GROUP_TYPE")));
            input.setGroupValue(trimToNull(rs.getString("GROUP_VALUE")));
            input.setRiskFactorId(trimToNull(rs.getString("RISK_FACTOR_ID")));
            input.setRiskFactorVertex1(trimToNull(rs.getString("RISK_FACTOR_VERTEX_1")));
            input.setRiskFactorVertex2(trimToNull(rs.getString("RISK_FACTOR_VERTEX_2")));
            input.setRiskFactorClass(trimToNull(rs.getString("RISK_FACTOR_CLASS")));
            input.setRiskFactorBucket(trimToNull(rs.getString("RISK_FACTOR_BUCKET")));
            input.setRiskFactorType(trimToNull(rs.getString("RISK_FACTOR_TYPE")));
            input.setSensitivityType(trimToNull(rs.getString("SENSITIVITY_TYPE")));
            input.setSensitivityValRptCurrCny(rs.getBigDecimal("SENSITIVITY_VAL_RPT_CURR_CNY"));
            input.setDataDate(trimToNull(rs.getString("DATA_DATE")));
            input.setModifier(trimToNull(rs.getString("MODIFIER")));
            return input;
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public FrtbSbaInputQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FrtbInput> queryInputs(String dataDate, List<String> treeIdList, List<String> groupTypeList) {
        List<Object> params = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append("TREE_ID, GROUP_TYPE, GROUP_VALUE, ")
                .append("RISK_FACTOR_ID, RISK_FACTOR_VERTEX_1, RISK_FACTOR_VERTEX_2, ")
                .append("RISK_FACTOR_CLASS, RISK_FACTOR_BUCKET, RISK_FACTOR_TYPE, ")
                .append("SENSITIVITY_TYPE, SENSITIVITY_VAL_RPT_CURR_CNY, DATA_DATE, MODIFIER ")
                .append("FROM MR_FRTB_SBA_INPUT ")
                .append("WHERE DATA_DATE = ?");
        params.add(dataDate);

        appendInClause(sql, params, "TREE_ID", normalizeList(treeIdList));
        appendInClause(sql, params, "GROUP_TYPE", normalizeList(groupTypeList));
        sql.append(" ORDER BY TREE_ID, GROUP_VALUE, ID");

        try {
            return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 MR_FRTB_SBA_INPUT 失败，请确认接口表已创建且可访问: " + ex.getMessage(), ex);
        }
    }

    public AggregationRule loadAggregationRule(String ruleId) {
        String safeRuleId = trimToNull(ruleId);
        if (safeRuleId == null) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
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
        for (AggregationRule.FilterCondition filter : rule.getFilters()) {
            String safeField = trimToNull(filter.getField());
            if (safeField != null) {
                selectedFields.add(safeField);
            }
        }

        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append("d.INSTRUMENT_ID AS INSTRUMENT_ID, ")
                .append("d.PRODUCT_CODE AS PRODUCT_CODE, ")
                .append("d.RISK_FACTOR_ID AS RISK_FACTOR_ID, ")
                .append("d.RISK_FACTOR_VERTEX_1 AS RISK_FACTOR_VERTEX_1, ")
                .append("d.RISK_FACTOR_VERTEX_2 AS RISK_FACTOR_VERTEX_2, ")
                .append("d.RISK_FACTOR_CLASS AS RISK_FACTOR_CLASS, ")
                .append("d.RISK_FACTOR_BUCKET AS RISK_FACTOR_BUCKET, ")
                .append("d.RISK_FACTOR_TYPE AS RISK_FACTOR_TYPE, ")
                .append("d.SENSITIVITY_TYPE AS SENSITIVITY_TYPE, ")
                .append("d.SENSITIVITY_VAL_INST_CURR_CNY AS SENSITIVITY_VAL_INST_CURR_CNY");

        for (String field : selectedFields) {
            if (isBaseSelectedField(field)) {
                continue;
            }
            String columnExpr = resolveRuleColumn(field);
            if (columnExpr == null) {
                continue;
            }
            sql.append(", ").append(columnExpr).append(" AS ").append(field);
        }

        sql.append(" FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL d ")
                .append("INNER JOIN TB_OUT_TRADE_RESULT_DETAIL r ")
                .append("ON r.BATCH_ID = d.BATCH_ID ")
                .append("AND r.INSTRUMENT_ID = d.INSTRUMENT_ID ")
                .append("WHERE d.BATCH_ID = ? AND d.DATA_DATE = ?");
        params.add(safeBatchId);
        params.add(safeDataDate);

        for (AggregationRule.FilterCondition filter : rule.getFilters()) {
            appendFilterClause(sql, params, filter);
        }
        sql.append(" ORDER BY d.INSTRUMENT_ID, d.RISK_FACTOR_CLASS, d.RISK_FACTOR_BUCKET, d.RISK_FACTOR_ID, d.SENSITIVITY_TYPE");

        try {
            return jdbcTemplate.queryForList(sql.toString(), params.toArray());
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

    private static void appendInClause(StringBuilder sql, List<Object> params, String column, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(values.get(i));
        }
        sql.append(")");
    }

    private static List<String> normalizeList(List<String> values) {
        List<String> normalized = new ArrayList<String>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                normalized.add(trimmed);
            }
        }
        return normalized;
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

    private static void appendFilterClause(StringBuilder sql, List<Object> params, AggregationRule.FilterCondition filter) {
        String field = trimToNull(filter.getField());
        String operator = trimToNull(filter.getOperator());
        String column = resolveRuleColumn(field);
        if (column == null || operator == null) {
            throw new IllegalArgumentException("不支持的过滤字段或操作符: " + field + " / " + operator);
        }
        if ("=".equals(operator)) {
            sql.append(" AND ").append(column).append(" = ?");
            params.add(filter.getValue());
            return;
        }
        if ("!=".equals(operator)) {
            sql.append(" AND ").append(column).append(" <> ?");
            params.add(filter.getValue());
            return;
        }
        if (">".equals(operator) || ">=".equals(operator) || "<".equals(operator) || "<=".equals(operator)) {
            sql.append(" AND ").append(column).append(" ").append(operator).append(" ?");
            params.add(filter.getValue());
            return;
        }
        if ("contains".equals(operator) || "not_contains".equals(operator)) {
            sql.append(" AND ").append(column);
            sql.append("contains".equals(operator) ? " LIKE ?" : " NOT LIKE ?");
            params.add("%" + String.valueOf(filter.getValue()) + "%");
            return;
        }
        if ("in".equals(operator) || "not_in".equals(operator)) {
            List<Object> values = asList(filter.getValue());
            if (values.isEmpty()) {
                throw new IllegalArgumentException("过滤条件 " + field + " 的取值不能为空");
            }
            sql.append(" AND ").append(column).append(" ").append("in".equals(operator) ? "IN (" : "NOT IN (");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(values.get(i));
            }
            sql.append(")");
            return;
        }
        throw new IllegalArgumentException("不支持的过滤操作符: " + operator);
    }

    private static List<Object> asList(Object value) {
        List<Object> values = new ArrayList<Object>();
        if (value == null) {
            return values;
        }
        if (value instanceof List) {
            values.addAll((List<?>) value);
            return values;
        }
        values.add(value);
        return values;
    }

    private static String resolveRuleColumn(String field) {
        String safeField = trimToNull(field);
        if (safeField == null) {
            return null;
        }
        if ("PORTFOLIO".equalsIgnoreCase(safeField)) {
            return "r.PORTFOLIO";
        }
        if ("DESK".equalsIgnoreCase(safeField)) {
            return "r.DESK";
        }
        if ("TRADER".equalsIgnoreCase(safeField)) {
            return "r.TRADER";
        }
        if ("PRODUCT_TYPE".equalsIgnoreCase(safeField)) {
            return "r.PRODUCT_CODE";
        }
        if ("TRADE_ID".equalsIgnoreCase(safeField)) {
            return "r.INSTRUMENT_ID";
        }
        if ("INSTRUMENT_ID".equalsIgnoreCase(safeField)) {
            return "d.INSTRUMENT_ID";
        }
        if ("PRODUCT_CODE".equalsIgnoreCase(safeField)) {
            return "d.PRODUCT_CODE";
        }
        if ("RISK_FACTOR_ID".equalsIgnoreCase(safeField)) {
            return "d.RISK_FACTOR_ID";
        }
        if ("RISK_FACTOR_VERTEX_1".equalsIgnoreCase(safeField)) {
            return "d.RISK_FACTOR_VERTEX_1";
        }
        if ("RISK_FACTOR_VERTEX_2".equalsIgnoreCase(safeField)) {
            return "d.RISK_FACTOR_VERTEX_2";
        }
        if ("RISK_FACTOR_CLASS".equalsIgnoreCase(safeField)) {
            return "d.RISK_FACTOR_CLASS";
        }
        if ("RISK_FACTOR_BUCKET".equalsIgnoreCase(safeField)) {
            return "d.RISK_FACTOR_BUCKET";
        }
        if ("RISK_FACTOR_TYPE".equalsIgnoreCase(safeField)) {
            return "d.RISK_FACTOR_TYPE";
        }
        if ("SENSITIVITY_TYPE".equalsIgnoreCase(safeField)) {
            return "d.SENSITIVITY_TYPE";
        }
        if ("SENSITIVITY_VAL_INST_CURR_CNY".equalsIgnoreCase(safeField)) {
            return "d.SENSITIVITY_VAL_INST_CURR_CNY";
        }
        if ("DATA_DATE".equalsIgnoreCase(safeField)) {
            return "d.DATA_DATE";
        }
        if ("BATCH_ID".equalsIgnoreCase(safeField)) {
            return "d.BATCH_ID";
        }
        return null;
    }

    private static boolean isBaseSelectedField(String field) {
        String safeField = trimToNull(field);
        if (safeField == null) {
            return false;
        }
        return "INSTRUMENT_ID".equalsIgnoreCase(safeField)
                || "PRODUCT_CODE".equalsIgnoreCase(safeField)
                || "RISK_FACTOR_ID".equalsIgnoreCase(safeField)
                || "RISK_FACTOR_VERTEX_1".equalsIgnoreCase(safeField)
                || "RISK_FACTOR_VERTEX_2".equalsIgnoreCase(safeField)
                || "RISK_FACTOR_CLASS".equalsIgnoreCase(safeField)
                || "RISK_FACTOR_BUCKET".equalsIgnoreCase(safeField)
                || "RISK_FACTOR_TYPE".equalsIgnoreCase(safeField)
                || "SENSITIVITY_TYPE".equalsIgnoreCase(safeField)
                || "SENSITIVITY_VAL_INST_CURR_CNY".equalsIgnoreCase(safeField);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
