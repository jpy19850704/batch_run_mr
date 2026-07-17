package com.zcyh.mr.springboot.measurement.aggregation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * 汇总规则字段到 SQL 列表达式的统一映射。
 */
public final class RuleColumnSqlResolver {
    public static final String PORTFOLIO_FLAT_VIEW = "V_TB_OUT_PORTFOLIO_HIERARCHY_FLAT";
    public static final int PORTFOLIO_LEVEL_MAX = 7;

    private static final Map<String, String> TRADE_FIELD_SQL = buildTradeFieldSqlMap();
    private static final Map<String, String> PORTFOLIO_FIELD_SQL = buildPortfolioFieldSqlMap();
    private static final Map<String, String> FRTB_SBA_RESULT_FIELD_SQL = buildFrtbSbaResultFieldSqlMap();
    private static final Map<String, String> FRTB_DRC_RESULT_FIELD_SQL = buildFrtbDrcResultFieldSqlMap();
    private static final Map<String, String> VAR_RESULT_FIELD_SQL = buildVarResultFieldSqlMap();

    private RuleColumnSqlResolver() {
    }

    public static String resolveFrtbSbaColumn(String field) {
        String safeField = normalizeField(field);
        if (safeField == null) {
            return null;
        }
        return resolveColumn(safeField, FRTB_SBA_RESULT_FIELD_SQL);
    }

    public static String resolveVarColumn(String field) {
        String safeField = normalizeField(field);
        if (safeField == null) {
            return null;
        }
        return resolveColumn(safeField, VAR_RESULT_FIELD_SQL);
    }

    public static String resolveFrtbDrcColumn(String field) {
        String safeField = normalizeField(field);
        if (safeField == null) {
            return null;
        }
        return resolveColumn(safeField, FRTB_DRC_RESULT_FIELD_SQL);
    }

    public static boolean requiresPortfolioFlatView(Set<String> fields) {
        if (fields == null) {
            return false;
        }
        for (String field : fields) {
            if (isPortfolioFlatField(field)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPortfolioFlatField(String field) {
        String safeField = normalizeField(field);
        if (safeField == null) {
            return false;
        }
        for (int i = 1; i <= PORTFOLIO_LEVEL_MAX; i++) {
            if (("PORTFOLIO_CODE_" + i).equals(safeField)) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeField(String field) {
        String safeField = trimToNull(field);
        return safeField == null ? null : safeField.toUpperCase(Locale.ROOT);
    }

    private static String resolveColumn(String safeField, Map<String, String> moduleFieldSql) {
        String expression = TRADE_FIELD_SQL.get(safeField);
        if (expression != null) {
            return expression;
        }
        expression = PORTFOLIO_FIELD_SQL.get(safeField);
        if (expression != null) {
            return expression;
        }
        return moduleFieldSql.get(safeField);
    }

    private static Map<String, String> buildTradeFieldSqlMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("BATCH_ID", "r.BATCH_ID");
        map.put("DATA_DATE", "r.DATA_DATE");
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

    private static Map<String, String> buildFrtbSbaResultFieldSqlMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("RISK_FACTOR_ID", "d.RISK_FACTOR_ID");
        map.put("RISK_FACTOR_VERTEX_1", "d.RISK_FACTOR_VERTEX_1");
        map.put("RISK_FACTOR_VERTEX_2", "d.RISK_FACTOR_VERTEX_2");
        map.put("RISK_FACTOR_CLASS", "d.RISK_FACTOR_CLASS");
        map.put("RISK_FACTOR_BUCKET", "d.RISK_FACTOR_BUCKET");
        map.put("RISK_FACTOR_TYPE", "d.RISK_FACTOR_TYPE");
        map.put("SENSITIVITY_TYPE", "d.SENSITIVITY_TYPE");
        map.put("SENSITIVITY_VAL_INST_CURR_CNY", "d.SENSITIVITY_VAL_INST_CURR_CNY");
        return map;
    }

    private static Map<String, String> buildFrtbDrcResultFieldSqlMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("PORTFOLIO_CODE", "d.PORTFOLIO_CODE");
        map.put("SECURITY_ID", "d.SECURITY_ID");
        map.put("SECURITY_TYPE", "d.SECURITY_TYPE");
        map.put("LEGAL_ENTITY", "d.LEGAL_ENTITY");
        map.put("DRC_BUCKET", "d.DRC_BUCKET");
        map.put("JTD_TYPE", "d.JTD_TYPE");
        map.put("SENIORITY", "d.SENIORITY");
        map.put("TERM_TO_MATURITY", "d.TERM_TO_MATURITY");
        map.put("MODIFIED_REMAIN_TERM", "d.MODIFIED_REMAIN_TERM");
        map.put("RISK_WEIGHT", "d.RISK_WEIGHT");
        map.put("JTD", "d.JTD");
        map.put("JTD_CNY", "d.JTD_CNY");
        map.put("INSTRUMENT_VALUE", "d.INSTRUMENT_VALUE");
        map.put("FRTB_LGD", "d.FRTB_LGD");
        map.put("NOTIONAL", "d.NOTIONAL");
        return map;
    }

    private static Map<String, String> buildVarResultFieldSqlMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("SCENARIO_ID", "d.SCENARIO_ID");
        map.put("SUBSCENARIO_ID", "d.SUBSCENARIO_ID");
        map.put("SCENARIO_NAME", "d.SCENARIO_NAME");
        map.put("ALL_PNL", "d.ALL_PNL");
        map.put("IR_PNL", "d.IR_PNL");
        map.put("FX_PNL", "d.FX_PNL");
        map.put("EQ_PNL", "d.EQ_PNL");
        map.put("COMM_PNL", "d.COMM_PNL");
        map.put("BASE_VALUATION_CNY", "d.BASE_VALUATION_CNY");
        return map;
    }

}
