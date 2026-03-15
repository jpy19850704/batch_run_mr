package com.zcyh.mr.springboot.service;

import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
}
