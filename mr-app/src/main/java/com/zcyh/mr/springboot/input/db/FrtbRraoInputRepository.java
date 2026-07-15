package com.zcyh.mr.springboot.input.db;

import com.zcyh.mr.frtbsa.rrao.FrtbRraoCalculator;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.prepare.filter.AggregationFilterSqlBuilder;
import com.zcyh.mr.springboot.prepare.mapping.RuleColumnSqlResolver;
import com.zcyh.mr.springboot.service.DimensionAggregationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.zcyh.mr.springboot.prepare.rule.AggregationRuleSupport.collectFilterFields;
import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * FRTB RRAO 计算输入仓储。
 */
@Repository
public class FrtbRraoInputRepository {
    private static final Logger log = LoggerFactory.getLogger(FrtbRraoInputRepository.class);
    private static final String TOTAL = "TOTAL";
    private static final String TYPE_EXOTIC = "EXOTIC";
    private static final String TYPE_OTHER = "OTHER";

    private final JdbcTemplate resultDbJdbcTemplate;
    private final DimensionAggregationService dimensionAggregationService;

    public FrtbRraoInputRepository(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate resultDbJdbcTemplate,
            DimensionAggregationService dimensionAggregationService) {
        this.resultDbJdbcTemplate = resultDbJdbcTemplate;
        this.dimensionAggregationService = dimensionAggregationService;
    }

    public List<FrtbRraoCalculator.Input> queryInputs(
            String batchId,
            String dataDate,
            AggregationRule rule) {
        return buildInputs(queryRows(batchId, dataDate, rule), rule.getBuildOrder());
    }

    private List<Map<String, Object>> queryRows(
            String batchId,
            String dataDate,
            AggregationRule rule) {
        List<Object> params = new ArrayList<Object>();
        Set<String> selectedFields = collectSelectedFields(rule);
        boolean usePortfolioFlatView = RuleColumnSqlResolver.requiresPortfolioFlatView(selectedFields);

        StringBuilder sql = new StringBuilder()
                .append("SELECT r.INSTRUMENT_ID, r.RRAO_TYPE, r.RRAO_NOTIONAL");
        for (String field : selectedFields) {
            String column = resolveRuleColumn(field);
            if (column == null) {
                throw new IllegalArgumentException("RRAO 规则字段无法映射到交易结果明细: " + field);
            }
            sql.append(", ").append(column).append(" AS ").append(field);
        }
        sql.append(" FROM TB_OUT_TRADE_RESULT_DETAIL r ");
        if (usePortfolioFlatView) {
            sql.append("LEFT JOIN ").append(RuleColumnSqlResolver.PORTFOLIO_FLAT_VIEW)
                    .append(" p ON p.BATCH_ID = r.BATCH_ID AND p.DATA_DATE = r.DATA_DATE AND p.PORTFOLIO_CODE = r.PORTFOLIO ");
        }
        sql.append("WHERE r.BATCH_ID = ? AND r.DATA_DATE = ? ")
                .append("AND r.RRAO_TYPE IS NOT NULL AND r.RRAO_NOTIONAL IS NOT NULL");
        params.add(batchId);
        params.add(dataDate);
        AggregationFilterSqlBuilder.appendWhereClause(
                sql,
                params,
                rule,
                FrtbRraoInputRepository::resolveRuleColumn);
        sql.append(" ORDER BY r.INSTRUMENT_ID");

        try {
            return resultDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "读取 RRAO 汇总底层明细失败，请确认交易结果明细已生成且可访问: " + ex.getMessage(), ex);
        }
    }

    private Set<String> collectSelectedFields(AggregationRule rule) {
        Set<String> selectedFields = new LinkedHashSet<String>();
        for (String level : rule.getBuildOrder()) {
            String groupType = normalizeGroupType(level);
            if (!TOTAL.equals(groupType)) {
                selectedFields.add(groupType);
            }
        }
        selectedFields.addAll(collectFilterFields(rule));
        return selectedFields;
    }

    private List<FrtbRraoCalculator.Input> buildInputs(
            List<Map<String, Object>> rows,
            List<String> buildOrder) {
        List<FrtbRraoCalculator.Input> inputs = new ArrayList<FrtbRraoCalculator.Input>();
        if (rows == null || rows.isEmpty()) {
            return inputs;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                log.warn("RRAO 输入排除空记录");
                continue;
            }
            String instrumentId = trimToNull(stringValue(row.get("INSTRUMENT_ID")));
            String rraoType = parseRraoType(row.get("RRAO_TYPE"), instrumentId);
            BigDecimal notional = parseNotional(row.get("RRAO_NOTIONAL"), instrumentId);
            if (rraoType == null || notional == null) {
                continue;
            }
            appendDimensionInputs(inputs, row, buildOrder, rraoType, notional);
        }
        return inputs;
    }

    private void appendDimensionInputs(
            List<FrtbRraoCalculator.Input> inputs,
            Map<String, Object> row,
            List<String> buildOrder,
            String rraoType,
            BigDecimal notional) {
        List<String> pathValues = new ArrayList<String>();
        for (String level : buildOrder) {
            String groupType;
            String groupValue;
            if (TOTAL.equals(level)) {
                groupType = TOTAL;
                groupValue = TOTAL;
            } else {
                String levelValue = dimensionAggregationService.normalizeDimensionValue(row.get(level));
                pathValues.add(levelValue);
                groupType = level;
                groupValue = dimensionAggregationService.buildGroupValue(pathValues);
            }
            inputs.add(new FrtbRraoCalculator.Input(groupType, groupValue, rraoType, notional));
        }
    }

    private static String parseRraoType(Object value, String instrumentId) {
        String type = trimToNull(stringValue(value));
        if (TYPE_EXOTIC.equals(type) || TYPE_OTHER.equals(type)) {
            return type;
        }
        log.warn("RRAO 输入排除非法类型: instrumentId={}, field=RRAO_TYPE, value={}", instrumentId, value);
        return null;
    }

    private static BigDecimal parseNotional(Object value, String instrumentId) {
        if (value == null) {
            log.warn("RRAO 输入排除空名义本金: instrumentId={}, field=RRAO_NOTIONAL", instrumentId);
            return null;
        }
        try {
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            if (value instanceof Number) {
                return new BigDecimal(String.valueOf(value));
            }
            String text = trimToNull(String.valueOf(value));
            if (text == null) {
                log.warn("RRAO 输入排除空名义本金: instrumentId={}, field=RRAO_NOTIONAL", instrumentId);
                return null;
            }
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            log.warn("RRAO 输入排除非法名义本金: instrumentId={}, field=RRAO_NOTIONAL, value={}",
                    instrumentId, value);
            return null;
        }
    }

    private static String resolveRuleColumn(String field) {
        String safeField = RuleColumnSqlResolver.normalizeField(field);
        if (safeField == null) {
            return null;
        }
        if (RuleColumnSqlResolver.isPortfolioFlatField(safeField)) {
            return "p." + safeField;
        }
        if ("BATCH_ID".equals(safeField)
                || "DATA_DATE".equals(safeField)
                || "INSTRUMENT_ID".equals(safeField)
                || "PRODUCT_CODE".equals(safeField)
                || "PORTFOLIO".equals(safeField)
                || "DESK".equals(safeField)
                || "TRADER".equals(safeField)
                || "VALUATION_CCY".equals(safeField)
                || "RRAO_TYPE".equals(safeField)
                || "RRAO_NOTIONAL".equals(safeField)) {
            return "r." + safeField;
        }
        return null;
    }

    private static String normalizeGroupType(String value) {
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            throw new IllegalArgumentException("RRAO build_order 不能包含空值");
        }
        String groupType = safeValue.toUpperCase(Locale.ROOT);
        if (resolveRuleColumn(groupType) == null && !TOTAL.equals(groupType)) {
            throw new IllegalArgumentException("RRAO build_order 字段不支持: " + value);
        }
        return groupType;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
