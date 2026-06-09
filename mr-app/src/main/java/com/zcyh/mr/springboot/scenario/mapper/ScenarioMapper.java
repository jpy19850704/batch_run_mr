package com.zcyh.mr.springboot.scenario.mapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 情景输入查询仓储。
 */
@Repository
public class ScenarioMapper {

    private final JdbcTemplate engineDbJdbcTemplate;

    public ScenarioMapper(@Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public List<Map<String, Object>> selectScenario(String scenarioIdList) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT SCENARIO_ID, SCENARIO_TYPE FROM V_SCENARIO_RULE");
        List<Object> params = new ArrayList<Object>();
        appendScenarioIdFilter(sql, params, scenarioIdList);
        return engineDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> selectScenarioMpByScenarioIdList(String scenarioIdList) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append("SCENARIO_ID, ")
                .append("SCENARIO_NAME, ")
                .append("SCENARIO_TYPE, ")
                .append("REDUCED_SET_FLAG, ")
                .append("CURVE_TYPE, ")
                .append("CURVE_CODE, ")
                .append("RISKGROUP_ID, ")
                .append("TERM_CODE, ")
                .append("TERM_DAYS, ")
                .append("END_DATE, ")
                .append("SCENARIO_SHIFT_VALUE, ")
                .append("SCENARIO_SHIFT_RULE ")
                .append("FROM V_SCENARIO_RULE");
        List<Object> params = new ArrayList<Object>();
        appendScenarioIdFilter(sql, params, scenarioIdList);
        sql.append(" ORDER BY SCENARIO_ID, CURVE_TYPE, CURVE_CODE, COALESCE(TERM_DAYS, 0), TERM_CODE");
        return engineDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> selectRiskGroupMembers(List<String> riskGroupIds) {
        if (riskGroupIds == null || riskGroupIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder()
                .append("SELECT RISKGROUP_ID, RISKFACTOR_TYPE, RISKFACTOR_ID ")
                .append("FROM MR_RISKGROUP_DATA WHERE RISKGROUP_ID IN (");
        List<Object> params = new ArrayList<Object>();
        appendPlaceholders(sql, params, riskGroupIds);
        sql.append(") ORDER BY RISKGROUP_ID, RISKFACTOR_TYPE, RISKFACTOR_ID");
        return engineDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> selectMarketInputRows(
            String marketDataType,
            Date startDate,
            Date endDate,
            List<String> curveIds) {
        String safeMarketDataType = trimToNull(marketDataType);
        if (safeMarketDataType == null) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder()
                .append("SELECT DATA_DATE, MARKET_DATA_TYPE, CURVE_ID, CURVE_CONTENT_TEXT ")
                .append("FROM MR_MARKET_CURVE_INPUT WHERE MARKET_DATA_TYPE = ?");
        List<Object> params = new ArrayList<Object>();
        params.add(safeMarketDataType);
        if (startDate != null && endDate != null) {
            sql.append(" AND DATA_DATE BETWEEN ? AND ?");
            params.add(new java.sql.Date(startDate.getTime()));
            params.add(new java.sql.Date(endDate.getTime()));
        } else if (startDate != null) {
            sql.append(" AND DATA_DATE = ?");
            params.add(new java.sql.Date(startDate.getTime()));
        } else if (endDate != null) {
            sql.append(" AND DATA_DATE = ?");
            params.add(new java.sql.Date(endDate.getTime()));
        }

        List<String> normalizedCurveIds = normalizeList(curveIds);
        if (!normalizedCurveIds.isEmpty()) {
            sql.append(" AND CURVE_ID IN (");
            appendPlaceholders(sql, params, normalizedCurveIds);
            sql.append(")");
        }
        sql.append(" ORDER BY DATA_DATE, MARKET_DATA_TYPE, CURVE_ID");
        return engineDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> selectMcScenarioMpByScenarioIdList(String scenarioIdList) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append("SCENARIO_ID, ")
                .append("SCENARIO_NAME, ")
                .append("SCENARIO_TYPE, ")
                .append("REDUCED_SET_FLAG, ")
                .append("START_DATE, ")
                .append("SCENARIO_NO, ")
                .append("SCENARIO_NO AS SCENARIO_NUMBER, ")
                .append("JUNP_DAY_NO AS JUMP_DAY_NO, ")
                .append("INCREASE_DAYS, ")
                .append("SCENARIO_SHIFT_RULE, ")
                .append("CURVE_TYPE, ")
                .append("CURVE_CODE, ")
                .append("RISKGROUP_ID, ")
                .append("TERM_CODE, ")
                .append("TERM_DAYS ")
                .append(", ")
                .append("CAL_END_DATE AS END_DATE ")
                .append("FROM MR_SCENARIO_RULE WHERE STATUS = 'ACTIVE'");
        List<Object> params = new ArrayList<Object>();
        appendScenarioIdFilter(sql, params, scenarioIdList, true);
        sql.append(" ORDER BY SCENARIO_ID, CURVE_TYPE, CURVE_CODE, COALESCE(TERM_DAYS, 0), TERM_CODE");
        return engineDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> selectHistoryScenarioMpByScenarioIdList(String scenarioIdList) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append("SCENARIO_ID, ")
                .append("SCENARIO_NAME, ")
                .append("SCENARIO_TYPE, ")
                .append("REDUCED_SET_FLAG, ")
                .append("START_DATE, ")
                .append("SCENARIO_NO, ")
                .append("JUNP_DAY_NO AS JUMP_DAY_NO, ")
                .append("INCREASE_DAYS, ")
                .append("SCENARIO_SHIFT_RULE, ")
                .append("CURVE_TYPE, ")
                .append("CURVE_CODE, ")
                .append("RISKGROUP_ID, ")
                .append("TERM_CODE, ")
                .append("TERM_DAYS ")
                .append(", ")
                .append("CAL_END_DATE AS END_DATE ")
                .append("FROM MR_SCENARIO_RULE WHERE STATUS = 'ACTIVE'");
        List<Object> params = new ArrayList<Object>();
        appendScenarioIdFilter(sql, params, scenarioIdList, true);
        sql.append(" ORDER BY SCENARIO_ID, CURVE_TYPE, CURVE_CODE, COALESCE(TERM_DAYS, 0), TERM_CODE");
        return engineDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    private void appendScenarioIdFilter(StringBuilder sql, List<Object> params, String scenarioIdList) {
        appendScenarioIdFilter(sql, params, scenarioIdList, false);
    }

    private void appendScenarioIdFilter(StringBuilder sql, List<Object> params, String scenarioIdList, boolean appendAnd) {
        List<String> ids = parseScenarioIds(scenarioIdList);
        if (ids.isEmpty()) {
            return;
        }
        sql.append(appendAnd ? " AND " : " WHERE ");
        if (ids.size() == 1) {
            sql.append("SCENARIO_ID = ?");
            params.add(ids.get(0));
            return;
        }
        StringJoiner placeholders = new StringJoiner(", ");
        for (int i = 0; i < ids.size(); i++) {
            placeholders.add("?");
            params.add(ids.get(i));
        }
        sql.append("SCENARIO_ID IN (").append(placeholders).append(")");
    }

    private List<String> parseScenarioIds(String scenarioIdList) {
        String safe = trimToNull(scenarioIdList);
        if (safe == null) {
            return Collections.emptyList();
        }
        String[] split = safe.split(",");
        List<String> ids = new ArrayList<String>();
        for (String item : split) {
            String value = trimToNull(item);
            if (value != null) {
                ids.add(value);
            }
        }
        return ids;
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    private void appendPlaceholders(StringBuilder sql, List<Object> params, List<String> values) {
        StringJoiner placeholders = new StringJoiner(", ");
        for (String value : values) {
            placeholders.add("?");
            params.add(value);
        }
        sql.append(placeholders);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
