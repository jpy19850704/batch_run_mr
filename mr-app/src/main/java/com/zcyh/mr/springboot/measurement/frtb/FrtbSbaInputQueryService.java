package com.zcyh.mr.springboot.measurement.frtb;

import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.springboot.input.db.RuleDefinitionRepository;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationFilterSqlBuilder;
import com.zcyh.mr.springboot.measurement.aggregation.RuleColumnSqlResolver;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.collectFilterFields;
import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.parseRule;

/**
 * FRTB SBA 输入查询服务。
 */
@Service
public class FrtbSbaInputQueryService {
    private static final Map<String, String> VIRTUAL_FIELD_SQL = buildVirtualFieldSqlMap();
    private static final String VIRTUAL_SELECTION_MODE_ENABLED = "ENABLED";
    private static final String VIRTUAL_SELECTION_MODE_SELECTED = "SELECTED";
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

    private final RuleDefinitionRepository ruleDefinitionRepository;
    private final JdbcTemplate engineResultDbJdbcTemplate;

    @Autowired
    public FrtbSbaInputQueryService(
            RuleDefinitionRepository ruleDefinitionRepository,
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
    }

    public AggregationRule loadAggregationRule(String ruleId) {
        String ruleLabel = "FRTB SBA 汇总规则";
        return parseRule(ruleDefinitionRepository.findRequired("FRTB_SBA", ruleId, ruleLabel), ruleLabel);
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
        for (String level : rule.getBuildOrder()) {
            String safeField = trimToNull(level);
            if (safeField != null && !"TOTAL".equalsIgnoreCase(safeField)) {
                selectedFields.add(safeField);
            }
        }
        selectedFields.addAll(rule.getSumFields());
        selectedFields.addAll(collectFilterFields(rule));
        boolean usePortfolioFlatView = requiresPortfolioFlatView(selectedFields);

        StringBuilder sql = new StringBuilder()
                .append("SELECT ");
        appendSelectFields(sql, REQUIRED_SELECT_FIELDS, false);

        for (String field : selectedFields) {
            String safeField = RuleColumnSqlResolver.normalizeField(field);
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
                        ? "LEFT JOIN " + RuleColumnSqlResolver.PORTFOLIO_FLAT_VIEW + " p ON p.BATCH_ID = d.BATCH_ID AND p.DATA_DATE = d.DATA_DATE AND p.PORTFOLIO_CODE = r.PORTFOLIO "
                        : "")
                .append("WHERE d.BATCH_ID = ? AND d.DATA_DATE=STR_TO_DATE(?, '%Y%m%d')");
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
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            rows.addAll(engineResultDbJdbcTemplate.queryForList(sql.toString(), params.toArray()));
            rows.addAll(queryVirtualRuleDetailRows(safeBatchId, safeDataDate, rule, selectedFields));
            return rows;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 FRTB 汇总底层明细失败，请确认明细表已生成且可访问: " + ex.getMessage(), ex);
        }
    }

    private List<Map<String, Object>> queryVirtualRuleDetailRows(String batchId,
                                                                 String dataDate,
                                                                 AggregationRule rule,
                                                                 Set<String> selectedFields) {
        String virtualSelectionMode = normalizeVirtualSelectionMode(rule.getVirtualSelectionMode());
        List<String> selectedVirtualTradeIds = sanitizeVirtualTradeIds(rule.getVirtualTradeIds());
        if (VIRTUAL_SELECTION_MODE_SELECTED.equals(virtualSelectionMode) && selectedVirtualTradeIds.isEmpty()) {
            return new ArrayList<Map<String, Object>>();
        }

        List<Object> params = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT ");
        appendVirtualSelectFields(sql, REQUIRED_SELECT_FIELDS, false, batchId);

        for (String field : selectedFields) {
            String safeField = RuleColumnSqlResolver.normalizeField(field);
            if (safeField == null || isRequiredSelectedField(safeField)) {
                continue;
            }
            String columnExpr = resolveVirtualRuleColumn(safeField, batchId);
            if (columnExpr == null) {
                continue;
            }
            sql.append(", ").append(columnExpr).append(" AS ").append(safeField);
        }

        sql.append(" FROM TB_FRTB_VIRTUAL_SENSITIVITY_INPUT v ");
        sql.append("WHERE v.DATA_DATE=STR_TO_DATE(?, '%Y%m%d')");
        params.add(dataDate);
        if (VIRTUAL_SELECTION_MODE_SELECTED.equals(virtualSelectionMode)) {
            sql.append(" AND v.VIRTUAL_TRADE_ID IN (");
            for (int i = 0; i < selectedVirtualTradeIds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(selectedVirtualTradeIds.get(i));
            }
            sql.append(")");
        } else {
            sql.append(" AND v.ENABLED = 1");
        }

        AggregationFilterSqlBuilder.appendWhereClause(sql, params, rule, new AggregationFilterSqlBuilder.ColumnResolver() {
            @Override
            public String resolve(String field) {
                return resolveVirtualRuleColumn(field, batchId);
            }
        });
        sql.append(" ORDER BY v.VIRTUAL_TRADE_ID, v.RISK_FACTOR_CLASS, v.RISK_FACTOR_BUCKET, v.RISK_FACTOR_ID, v.SENSITIVITY_TYPE");
        return engineResultDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, List<FrtbInput>> groupByRuleIdAndGroupValue(List<FrtbInput> inputList) {
        Map<String, List<FrtbInput>> grouped = new LinkedHashMap<String, List<FrtbInput>>();
        if (inputList == null || inputList.isEmpty()) {
            return grouped;
        }

        for (FrtbInput input : inputList) {
            // 这里直接沿用上游给定的 rule_id / group_value 分组。
            // 上游约束：
            // 1. 每个 rule_id 下已经提供一条 GROUP_TYPE=TOTAL、GROUP_VALUE=TOTAL 的总维度输入；
            // 2. group_type / group_value 表示上游定义好的维度标签，不在此处重新构造 TOTAL 或重切分资本口径。
            String taskKey = buildTaskKey(input.getRuleId(), input.getGroupValue());
            List<FrtbInput> items = grouped.get(taskKey);
            if (items == null) {
                items = new ArrayList<FrtbInput>();
                grouped.put(taskKey, items);
            }
            items.add(input);
        }
        return grouped;
    }

    private static String buildTaskKey(String ruleId, String groupValue) {
        String safeRuleId = trimToNull(ruleId);
        String safeGroupValue = trimToNull(groupValue);
        // taskKey 仅用于承接上游已组织好的规则维度标签。
        // TOTAL 维度由上游提供，因此这里不额外拼接或派生新的 TOTAL 任务键。
        return (safeRuleId == null ? "__EMPTY_RULE__" : safeRuleId)
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
        return RuleColumnSqlResolver.resolveFrtbSbaColumn(field);
    }

    private static String resolveVirtualRuleColumn(String field, String batchId) {
        String safeField = RuleColumnSqlResolver.normalizeField(field);
        if (safeField == null) {
            return null;
        }
        if ("BATCH_ID".equals(safeField)) {
            return sqlStringLiteral(batchId);
        }
        return VIRTUAL_FIELD_SQL.get(safeField);
    }

    private static boolean isRequiredSelectedField(String field) {
        String safeField = RuleColumnSqlResolver.normalizeField(field);
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

    private static boolean requiresPortfolioFlatView(Set<String> fields) {
        return RuleColumnSqlResolver.requiresPortfolioFlatView(fields);
    }

    private static String normalizeVirtualSelectionMode(String mode) {
        String safeMode = trimToNull(mode);
        // 业务口径：空设置表示按启用状态纳入虚拟补录；试算页面传 SELECTED 时只纳入本次选中列表。
        if (safeMode == null) {
            return VIRTUAL_SELECTION_MODE_ENABLED;
        }
        String upper = safeMode.toUpperCase();
        if (VIRTUAL_SELECTION_MODE_ENABLED.equals(upper) || VIRTUAL_SELECTION_MODE_SELECTED.equals(upper)) {
            return upper;
        }
        throw new IllegalArgumentException("FRTB SBA 虚拟交易选择模式不支持: " + mode);
    }

    private static List<String> sanitizeVirtualTradeIds(List<String> virtualTradeIds) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        if (virtualTradeIds != null) {
            for (String virtualTradeId : virtualTradeIds) {
                String safeId = trimToNull(virtualTradeId);
                if (safeId != null) {
                    result.add(safeId);
                }
            }
        }
        return new ArrayList<String>(result);
    }

    private static void appendSelectFields(StringBuilder sql, String[] fields, boolean appendCommaPrefix) {
        boolean appended = false;
        for (String field : fields) {
            String safeField = RuleColumnSqlResolver.normalizeField(field);
            String expression = resolveRuleColumn(safeField);
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

    private static void appendVirtualSelectFields(StringBuilder sql, String[] fields, boolean appendCommaPrefix, String batchId) {
        boolean appended = false;
        for (String field : fields) {
            String safeField = RuleColumnSqlResolver.normalizeField(field);
            String expression = resolveVirtualRuleColumn(safeField, batchId);
            if (safeField == null || expression == null) {
                throw new IllegalArgumentException("FRTB SBA 虚拟交易必选字段未配置 SQL 映射: " + field);
            }
            if (appendCommaPrefix || appended) {
                sql.append(", ");
            }
            sql.append(expression).append(" AS ").append(safeField);
            appended = true;
        }
    }

    private static Map<String, String> buildVirtualFieldSqlMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("DATA_DATE", "v.DATA_DATE");
        map.put("INSTRUMENT_ID", "v.VIRTUAL_TRADE_ID");
        map.put("PRODUCT_CODE", "v.PRODUCT_CODE");
        map.put("PORTFOLIO", "v.PORTFOLIO");
        map.put("DESK", "v.DESK");
        map.put("TRADER", "v.TRADER");
        map.put("VALUATION_CCY", "v.VALUATION_CCY");
        for (int i = 1; i <= RuleColumnSqlResolver.PORTFOLIO_LEVEL_MAX; i++) {
            map.put("PORTFOLIO_CODE_" + i, "v.PORTFOLIO_CODE_" + i);
        }
        map.put("RISK_FACTOR_ID", "v.RISK_FACTOR_ID");
        map.put("RISK_FACTOR_VERTEX_1", "v.RISK_FACTOR_VERTEX_1");
        map.put("RISK_FACTOR_VERTEX_2", "v.RISK_FACTOR_VERTEX_2");
        map.put("RISK_FACTOR_CLASS", "v.RISK_FACTOR_CLASS");
        map.put("RISK_FACTOR_BUCKET", "v.RISK_FACTOR_BUCKET");
        map.put("RISK_FACTOR_TYPE", "v.RISK_FACTOR_TYPE");
        map.put("SENSITIVITY_TYPE", "v.SENSITIVITY_TYPE");
        map.put("SENSITIVITY_VAL_INST_CURR_CNY", "v.SENSITIVITY_VAL_INST_CURR_CNY");
        return map;
    }

    private static String sqlStringLiteral(String value) {
        String safe = value == null ? "" : value.replace("'", "''");
        return "'" + safe + "'";
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
