package com.zcyh.mr.springboot.measurement.frtb;

import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.springboot.input.db.RuleDefinitionRepository;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationFilterSqlBuilder;
import com.zcyh.mr.springboot.measurement.aggregation.RuleColumnSqlResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.collectFilterFields;
import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.parseRule;

/**
 * FRTB DRC 输入查询服务。
 * 从 engine_result_db 的 DRC 明细表按批次与估值日读取计量输入。
 */
@Service
public class FrtbDrcInputQueryService {
    private static final String[] REQUIRED_SELECT_FIELDS = {
            "DATA_DATE",
            "PORTFOLIO_CODE",
            "PRODUCT_CODE",
            "INSTRUMENT_ID",
            "SECURITY_ID",
            "SECURITY_TYPE",
            "LEGAL_ENTITY",
            "DRC_BUCKET",
            "JTD_TYPE",
            "SENIORITY",
            "TERM_TO_MATURITY",
            "MODIFIED_REMAIN_TERM",
            "RISK_WEIGHT",
            "JTD",
            "JTD_CNY",
            "INSTRUMENT_VALUE",
            "FRTB_LGD",
            "NOTIONAL"
    };

    private final RuleDefinitionRepository ruleDefinitionRepository;
    private final JdbcTemplate engineResultDbJdbcTemplate;

    @Autowired
    public FrtbDrcInputQueryService(RuleDefinitionRepository ruleDefinitionRepository,
                                    @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate) {
        this.ruleDefinitionRepository = ruleDefinitionRepository;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
    }

    public AggregationRule loadAggregationRule(String ruleId) {
        String ruleLabel = "FRTB DRC 汇总规则";
        return parseRule(ruleDefinitionRepository.findRequired("FRTB_DRC", ruleId, ruleLabel), ruleLabel);
    }

    /**
     * 按 batch_id + data_date 读取 DRC 明细输入。
     */
    public List<DrcDetail> queryDrcDetails(String batchId, String dataDate) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batch_id 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("data_date 不能为空");
        }

        String sql = "SELECT DATA_DATE, PORTFOLIO_CODE, PRODUCT_CODE, INSTRUMENT_ID, SECURITY_ID, "
                + "SECURITY_TYPE, LEGAL_ENTITY, DRC_BUCKET, JTD_TYPE, SENIORITY, "
                + "TERM_TO_MATURITY, MODIFIED_REMAIN_TERM, RISK_WEIGHT, JTD, JTD_CNY, "
                + "INSTRUMENT_VALUE, FRTB_LGD, NOTIONAL "
                + "FROM TB_OUT_TRADE_DRC_DETAIL "
                + "WHERE BATCH_ID=? AND DATA_DATE=? "
                + "ORDER BY SEQ_NO, ID";

        List<DrcDetail> rows = engineResultDbJdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, safeBatchId);
                    ps.setString(2, safeDataDate);
                },
                (rs, rowNum) -> {
                    DrcDetail detail = new DrcDetail();
                    detail.portfolioCode = trimToNull(rs.getString("PORTFOLIO_CODE"));
                    detail.productCode = trimToNull(rs.getString("PRODUCT_CODE"));
                    detail.instrumentId = trimToNull(rs.getString("INSTRUMENT_ID"));
                    detail.securityId = trimToNull(rs.getString("SECURITY_ID"));
                    detail.securityType = trimToNull(rs.getString("SECURITY_TYPE"));
                    detail.legalEntity = trimToNull(rs.getString("LEGAL_ENTITY"));
                    detail.drcBucket = trimToNull(rs.getString("DRC_BUCKET"));
                    detail.jtdType = trimToNull(rs.getString("JTD_TYPE"));
                    int seniority = rs.getInt("SENIORITY");
                    detail.seniority = rs.wasNull() ? null : seniority;
                    detail.termToMaturity = rs.getDouble("TERM_TO_MATURITY");
                    if (rs.wasNull()) {
                        detail.termToMaturity = null;
                    }
                    detail.modifiedRemainTerm = rs.getDouble("MODIFIED_REMAIN_TERM");
                    if (rs.wasNull()) {
                        detail.modifiedRemainTerm = null;
                    }
                    detail.riskWeight = rs.getDouble("RISK_WEIGHT");
                    if (rs.wasNull()) {
                        detail.riskWeight = null;
                    }
                    detail.jtd = rs.getDouble("JTD");
                    if (rs.wasNull()) {
                        detail.jtd = null;
                    }
                    detail.jtdCny = rs.getDouble("JTD_CNY");
                    if (rs.wasNull()) {
                        detail.jtdCny = null;
                    }
                    detail.instrumentValue = rs.getDouble("INSTRUMENT_VALUE");
                    if (rs.wasNull()) {
                        detail.instrumentValue = null;
                    }
                    detail.frtbLgd = rs.getDouble("FRTB_LGD");
                    if (rs.wasNull()) {
                        detail.frtbLgd = null;
                    }
                    detail.notional = rs.getDouble("NOTIONAL");
                    if (rs.wasNull()) {
                        detail.notional = null;
                    }
                    return detail;
                });

        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("未查到可用于 DRC 计量的明细输入: batch_id=" + safeBatchId + ", data_date=" + safeDataDate);
        }
        return rows;
    }

    public List<RuleDrcDetailRow> queryRuleDetailRows(String batchId, String dataDate, AggregationRule rule) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batch_id 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("data_date 不能为空");
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
        selectedFields.addAll(collectFilterFields(rule));
        boolean usePortfolioFlatView = requiresPortfolioFlatView(selectedFields);

        StringBuilder sql = new StringBuilder().append("SELECT ");
        appendSelectFields(sql, REQUIRED_SELECT_FIELDS, false);
        for (String field : selectedFields) {
            String safeField = RuleColumnSqlResolver.normalizeField(field);
            if (safeField == null || isRequiredSelectedField(safeField)) {
                continue;
            }
            String columnExpr = resolveRuleColumn(safeField);
            if (columnExpr == null) {
                throw new IllegalArgumentException("DRC 规则字段无法映射到结果明细: " + safeField);
            }
            sql.append(", ").append(columnExpr).append(" AS ").append(safeField);
        }
        sql.append(" FROM TB_OUT_TRADE_DRC_DETAIL d ")
                .append("LEFT JOIN TB_OUT_TRADE_RESULT_DETAIL r ")
                .append("ON r.BATCH_ID = d.BATCH_ID ")
                .append("AND r.DATA_DATE = d.DATA_DATE ")
                .append("AND r.INSTRUMENT_ID = d.INSTRUMENT_ID ")
                .append(usePortfolioFlatView
                        ? "LEFT JOIN " + RuleColumnSqlResolver.PORTFOLIO_FLAT_VIEW + " p ON p.BATCH_ID = d.BATCH_ID AND p.DATA_DATE = d.DATA_DATE AND p.PORTFOLIO_CODE = COALESCE(r.PORTFOLIO, d.PORTFOLIO_CODE) "
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
        sql.append(" ORDER BY d.SEQ_NO, d.ID");

        try {
            List<Map<String, Object>> rows = engineResultDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
            if (rows == null || rows.isEmpty()) {
                throw new IllegalArgumentException("未查到可用于 DRC 规则汇总的明细输入: batch_id="
                        + safeBatchId + ", data_date=" + safeDataDate + ", rule_id=" + rule.getRuleId());
            }
            List<RuleDrcDetailRow> output = new ArrayList<RuleDrcDetailRow>();
            for (Map<String, Object> row : rows) {
                output.add(new RuleDrcDetailRow(toDrcDetail(row), row));
            }
            return output;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 DRC 汇总底层明细失败，请确认明细表已生成且可访问: " + ex.getMessage(), ex);
        }
    }

    private static DrcDetail toDrcDetail(Map<String, Object> row) {
        DrcDetail detail = new DrcDetail();
        detail.dataDate = parseDate(stringValue(row.get("DATA_DATE")));
        detail.portfolioCode = trimToNull(stringValue(row.get("PORTFOLIO_CODE")));
        detail.productCode = trimToNull(stringValue(row.get("PRODUCT_CODE")));
        detail.instrumentId = trimToNull(stringValue(row.get("INSTRUMENT_ID")));
        detail.securityId = trimToNull(stringValue(row.get("SECURITY_ID")));
        detail.securityType = trimToNull(stringValue(row.get("SECURITY_TYPE")));
        detail.legalEntity = trimToNull(stringValue(row.get("LEGAL_ENTITY")));
        detail.drcBucket = trimToNull(stringValue(row.get("DRC_BUCKET")));
        detail.jtdType = trimToNull(stringValue(row.get("JTD_TYPE")));
        detail.seniority = toInteger(row.get("SENIORITY"));
        detail.termToMaturity = toDouble(row.get("TERM_TO_MATURITY"));
        detail.modifiedRemainTerm = toDouble(row.get("MODIFIED_REMAIN_TERM"));
        detail.riskWeight = toDouble(row.get("RISK_WEIGHT"));
        detail.jtd = toDouble(row.get("JTD"));
        detail.jtdCny = toDouble(row.get("JTD_CNY"));
        detail.instrumentValue = toDouble(row.get("INSTRUMENT_VALUE"));
        detail.frtbLgd = toDouble(row.get("FRTB_LGD"));
        detail.notional = toDouble(row.get("NOTIONAL"));
        return detail;
    }

    private static void appendSelectFields(StringBuilder sql, String[] fields, boolean appendCommaPrefix) {
        boolean appended = false;
        for (String field : fields) {
            String safeField = RuleColumnSqlResolver.normalizeField(field);
            String expression = resolveRuleColumn(safeField);
            if (safeField == null || expression == null) {
                throw new IllegalArgumentException("DRC 必选字段未配置 SQL 映射: " + field);
            }
            if (appendCommaPrefix || appended) {
                sql.append(", ");
            }
            sql.append(expression).append(" AS ").append(safeField);
            appended = true;
        }
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

    private static String resolveRuleColumn(String field) {
        return RuleColumnSqlResolver.resolveFrtbDrcColumn(field);
    }

    private static LocalDate parseDate(String value) {
        String safe = trimToNull(value);
        if (safe == null) {
            return null;
        }
        if (safe.length() == 8 && safe.chars().allMatch(Character::isDigit)) {
            return LocalDate.parse(safe, DateTimeFormatter.BASIC_ISO_DATE);
        }
        return LocalDate.parse(safe);
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = trimToNull(String.valueOf(value));
        return text == null ? null : Integer.valueOf(text);
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = trimToNull(String.valueOf(value));
        if (text == null) {
            return null;
        }
        return new BigDecimal(text).doubleValue();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    public static class RuleDrcDetailRow {
        private final DrcDetail detail;
        private final Map<String, Object> fields;

        RuleDrcDetailRow(DrcDetail detail, Map<String, Object> fields) {
            this.detail = detail;
            this.fields = fields;
        }

        public DrcDetail getDetail() {
            return detail;
        }

        public Map<String, Object> getFields() {
            return fields;
        }
    }
}
