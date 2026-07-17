package com.zcyh.mr.springboot.measurement.var;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.db.RuleDefinitionRepository;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationFilterSqlBuilder;
import com.zcyh.mr.springboot.measurement.aggregation.RuleColumnSqlResolver;
import com.zcyh.mr.var.VarScenarioPnl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.collectFilterFields;
import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.parseRuleJson;

/**
 * VaR 输入查询服务。
 */
@Service
public class VarInputQueryService {
    private static final String TABLE = "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL";
    private final RuleDefinitionRepository ruleDefinitionRepository;
    private final JdbcTemplate engineResultDbJdbcTemplate;

    @Autowired
    public VarInputQueryService(RuleDefinitionRepository ruleDefinitionRepository,
                                @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
    }

    /**
     * 按 rule_id 读取 VAR 汇总规则 JSON。
     */
    public JSONObject loadVarRuleJson(String ruleId) {
        String ruleLabel = "VAR 汇总规则";
        return parseRuleJson(ruleDefinitionRepository.findRequired("VAR", ruleId, ruleLabel), ruleLabel);
    }

    /**
     * 按规则维度进行数据库侧第一轮汇总：
     * 场景键 + 维度字段聚合，返回 ALL/IR/FX/EQ/COMM 各口径损益。
     */
    public List<RuleScenarioPnlRow> queryRuleScenarioPnlRows(String batchId,
                                                             String dataDate,
                                                             String scenarioId,
                                                             AggregationRule rule) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        String safeScenarioId = trimToNull(scenarioId);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batch_id 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("data_date 不能为空");
        }
        if (safeScenarioId == null) {
            throw new IllegalArgumentException("scenarioId 不能为空");
        }
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空");
        }

        Set<String> dimensionFields = new LinkedHashSet<String>();
        for (String level : rule.getBuildOrder()) {
            String safeField = trimToNull(level);
            if (safeField != null && !"TOTAL".equalsIgnoreCase(safeField)) {
                dimensionFields.add(safeField.toUpperCase());
            }
        }
        Set<String> filterFields = collectFilterFields(rule);
        boolean usePortfolioFlatView = requiresPortfolioFlatView(dimensionFields, filterFields);

        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append("d.SCENARIO_ID AS SCENARIO_ID, ")
                .append("d.SUBSCENARIO_ID AS SUBSCENARIO_ID, ")
                .append("d.SCENARIO_NAME AS SCENARIO_NAME");

        List<String> dimensionExprList = new ArrayList<String>();
        for (String dimensionField : dimensionFields) {
            String expr = resolveRuleColumn(dimensionField);
            if (expr == null) {
                throw new IllegalArgumentException("VaR rule 维度字段不支持: " + dimensionField);
            }
            sql.append(", ").append(expr).append(" AS ").append(dimensionField);
            dimensionExprList.add(expr);
        }

        sql.append(", SUM(d.ALL_PNL) AS ALL_PNL")
                .append(", SUM(d.IR_PNL) AS IR_PNL")
                .append(", SUM(d.FX_PNL) AS FX_PNL")
                .append(", SUM(d.EQ_PNL) AS EQ_PNL")
                .append(", SUM(d.COMM_PNL) AS COMM_PNL")
                .append(", SUM(d.BASE_VALUATION_CNY) AS BASE_VALUATION_CNY")
                .append(" FROM ").append(TABLE).append(" d ")
                .append(" INNER JOIN TB_OUT_TRADE_RESULT_DETAIL r ")
                .append(" ON r.BATCH_ID = d.BATCH_ID ")
                .append(" AND r.DATA_DATE = d.DATA_DATE ")
                .append(" AND r.INSTRUMENT_ID = d.INSTRUMENT_ID ");
        if (usePortfolioFlatView) {
            sql.append(" LEFT JOIN ").append(RuleColumnSqlResolver.PORTFOLIO_FLAT_VIEW).append(" p ")
                    .append(" ON p.BATCH_ID = d.BATCH_ID ")
                    .append(" AND p.DATA_DATE = d.DATA_DATE ")
                    .append(" AND p.PORTFOLIO_CODE = r.PORTFOLIO ");
        }
        sql.append(" WHERE d.BATCH_ID = ? AND d.DATA_DATE = ? AND d.SCENARIO_ID = ?");

        List<Object> params = new ArrayList<Object>();
        params.add(safeBatchId);
        params.add(safeDataDate);
        params.add(safeScenarioId);

        AggregationFilterSqlBuilder.appendWhereClause(sql, params, rule, VarInputQueryService::resolveRuleColumn);

        sql.append(" GROUP BY d.SCENARIO_ID, d.SUBSCENARIO_ID, d.SCENARIO_NAME");
        for (String expr : dimensionExprList) {
            sql.append(", ").append(expr);
        }
        sql.append(" ORDER BY d.SCENARIO_ID, d.SUBSCENARIO_ID, d.SCENARIO_NAME");

        try {
            List<RuleScenarioPnlRow> rows = engineResultDbJdbcTemplate.query(
                    sql.toString(),
                    params.toArray(),
                    (rs, rowNum) -> {
                        Map<String, String> groupValues = new LinkedHashMap<String, String>();
                        for (String dimensionField : dimensionFields) {
                            groupValues.put(dimensionField, trimToNull(rs.getString(dimensionField)));
                        }
                        return new RuleScenarioPnlRow(
                                trimToNull(rs.getString("SCENARIO_ID")),
                                trimToNull(rs.getString("SUBSCENARIO_ID")),
                                trimToNull(rs.getString("SCENARIO_NAME")),
                                groupValues,
                                rs.getBigDecimal("ALL_PNL"),
                                rs.getBigDecimal("IR_PNL"),
                                rs.getBigDecimal("FX_PNL"),
                                rs.getBigDecimal("EQ_PNL"),
                                rs.getBigDecimal("COMM_PNL"),
                                rs.getBigDecimal("BASE_VALUATION_CNY"));
                    });
            if (rows == null || rows.isEmpty()) {
                throw new IllegalArgumentException("未查到可用于 VaR 规则汇总的情景损益明细: batch_id="
                        + safeBatchId + ", data_date=" + safeDataDate + ", rule_id=" + rule.getRuleId()
                        + ", scenario_id=" + safeScenarioId);
            }
            return rows;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 VaR 规则汇总底层数据失败: " + ex.getMessage(), ex);
        }
    }

    public List<VarScenarioPnl> queryScenarioPnlRows(String batchId, String dataDate, String pnlField) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batch_id 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("data_date 不能为空");
        }
        String pnlColumn = resolvePnlColumn(pnlField);

        String sql = "SELECT SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, "
                + "SUM(" + pnlColumn + ") AS PNL "
                + "FROM " + TABLE + " "
                + "WHERE BATCH_ID=? AND DATA_DATE=? "
                + "GROUP BY SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME "
                + "ORDER BY PNL ASC, SCENARIO_ID, SUBSCENARIO_ID";

        List<VarScenarioPnl> rows = engineResultDbJdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, safeBatchId);
                    ps.setString(2, safeDataDate);
                },
                (rs, rowNum) -> new VarScenarioPnl(
                        trimToNull(rs.getString("SCENARIO_ID")),
                        trimToNull(rs.getString("SUBSCENARIO_ID")),
                        trimToNull(rs.getString("SCENARIO_NAME")),
                        rs.getBigDecimal("PNL")));
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("未查到可用于 VaR 计量的情景损益明细: batch_id="
                    + safeBatchId + ", data_date=" + safeDataDate + ", pnl_field=" + pnlColumn);
        }
        return rows;
    }

    public static String resolvePnlColumn(String pnlField) {
        String token = trimToNull(pnlField);
        if (token == null) {
            return "ALL_PNL";
        }
        String upper = token.toUpperCase();
        if ("ALL".equals(upper) || "ALL_PNL".equals(upper) || "PNL".equals(upper)) {
            return "ALL_PNL";
        }
        if ("IR".equals(upper) || "IR_PNL".equals(upper)) {
            return "IR_PNL";
        }
        if ("FX".equals(upper) || "FX_PNL".equals(upper)) {
            return "FX_PNL";
        }
        if ("EQ".equals(upper) || "EQ_PNL".equals(upper)) {
            return "EQ_PNL";
        }
        if ("COMM".equals(upper) || "CMTY".equals(upper) || "COMM_PNL".equals(upper)) {
            return "COMM_PNL";
        }
        throw new IllegalArgumentException("不支持的 pnl_field: " + pnlField);
    }

    static BigDecimal readPnlByColumn(RuleScenarioPnlRow row, String pnlColumn) {
        if (row == null) {
            return BigDecimal.ZERO;
        }
        if ("ALL_PNL".equalsIgnoreCase(pnlColumn)) {
            return safeBigDecimal(row.getAllPnl());
        }
        if ("IR_PNL".equalsIgnoreCase(pnlColumn)) {
            return safeBigDecimal(row.getIrPnl());
        }
        if ("FX_PNL".equalsIgnoreCase(pnlColumn)) {
            return safeBigDecimal(row.getFxPnl());
        }
        if ("EQ_PNL".equalsIgnoreCase(pnlColumn)) {
            return safeBigDecimal(row.getEqPnl());
        }
        if ("COMM_PNL".equalsIgnoreCase(pnlColumn)) {
            return safeBigDecimal(row.getCommPnl());
        }
        throw new IllegalArgumentException("不支持的 pnl 列: " + pnlColumn);
    }

    private static BigDecimal safeBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String resolveRuleColumn(String field) {
        return RuleColumnSqlResolver.resolveVarColumn(field);
    }

    private static boolean requiresPortfolioFlatView(Set<String> dimensionFields, Set<String> filterFields) {
        if (RuleColumnSqlResolver.requiresPortfolioFlatView(dimensionFields)) {
            return true;
        }
        return RuleColumnSqlResolver.requiresPortfolioFlatView(filterFields);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 
     * 规则维度聚合后的场景损益行。
     */
    public static class RuleScenarioPnlRow {
        private final String scenarioId;
        private final String subScenarioId;
        private final String scenarioName;
        private final Map<String, String> groupValues;
        private final BigDecimal allPnl;
        private final BigDecimal irPnl;
        private final BigDecimal fxPnl;
        private final BigDecimal eqPnl;
        private final BigDecimal commPnl;
        private final BigDecimal baseValuationCny;

        public RuleScenarioPnlRow(String scenarioId,
                                  String subScenarioId,
                                  String scenarioName,
                                  Map<String, String> groupValues,
                                  BigDecimal allPnl,
                                  BigDecimal irPnl,
                                  BigDecimal fxPnl,
                                  BigDecimal eqPnl,
                                  BigDecimal commPnl,
                                  BigDecimal baseValuationCny) {
            this.scenarioId = scenarioId;
            this.subScenarioId = subScenarioId;
            this.scenarioName = scenarioName;
            this.groupValues = groupValues == null ? new LinkedHashMap<String, String>() : groupValues;
            this.allPnl = allPnl;
            this.irPnl = irPnl;
            this.fxPnl = fxPnl;
            this.eqPnl = eqPnl;
            this.commPnl = commPnl;
            this.baseValuationCny = baseValuationCny;
        }

        public String getScenarioId() {
            return scenarioId;
        }

        public String getSubScenarioId() {
            return subScenarioId;
        }

        public String getScenarioName() {
            return scenarioName;
        }

        public Map<String, String> getGroupValues() {
            return groupValues;
        }

        public BigDecimal getAllPnl() {
            return allPnl;
        }

        public BigDecimal getIrPnl() {
            return irPnl;
        }

        public BigDecimal getFxPnl() {
            return fxPnl;
        }

        public BigDecimal getEqPnl() {
            return eqPnl;
        }

        public BigDecimal getCommPnl() {
            return commPnl;
        }

        public BigDecimal getBaseValuationCny() {
            return baseValuationCny;
        }
    }

}
