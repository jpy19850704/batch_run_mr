package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 情景请求装配器。
 *
 * <p>
 * 该类负责把数据库原始结果转换成 core 可直接消费的标准化场景请求对象。
 */
public class ScenarioRequestAssembler {

    private final ScenarioMapper scenarioMapper;
    private final ScenarioHistoricalMarketLoader historicalMarketLoader;
    private final String defaultHolidayCalendarCode;

    public ScenarioRequestAssembler(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar,
            String defaultHolidayCalendarCode) {
        if (scenarioMapper == null) {
            throw new IllegalArgumentException("scenarioMapper 不能为空");
        }
        this.scenarioMapper = scenarioMapper;
        com.zcyh.mr.core.Calendar sharedCalendar = holidayCalendar == null
                ? new com.zcyh.mr.core.Calendar()
                : holidayCalendar;
        this.historicalMarketLoader = new ScenarioHistoricalMarketLoader(scenarioMapper, sharedCalendar);
        this.defaultHolidayCalendarCode = normalize(defaultHolidayCalendarCode);
    }

    /**
     * 构建标准化场景请求。
     */
    public ScenarioGenerationRequest build(String scenarioIdList, LocalDate valuationDate, String user, String source) {
        ScenarioGenerationRequest request = new ScenarioGenerationRequest();
        request.setScenarioIdList(scenarioIdList);
        request.setValuationDate(valuationDate);
        request.setUser(user);
        request.setSource(source);

        List<Map<String, Object>> scenarioRows = scenarioMapper.selectScenario(scenarioIdList);
        List<ScenarioTaskRequest> tasks = new ArrayList<ScenarioTaskRequest>();
        if (scenarioRows == null || scenarioRows.isEmpty()) {
            request.setTasks(tasks);
            return request;
        }

        for (Map<String, Object> scenarioRow : scenarioRows) {
            String scenarioId = toStringValue(scenarioRow.get("SCENARIO_ID"));
            String scenarioType = toStringValue(scenarioRow.get("SCENARIO_TYPE"));
            if (scenarioId == null || scenarioId.isEmpty() || scenarioType == null || scenarioType.isEmpty()) {
                continue;
            }

            List<Map<String, Object>> definitionRows = loadDefinitionRows(scenarioId, scenarioType);
            if (definitionRows.isEmpty()) {
                continue;
            }

            List<ScenarioDefinition> definitions = convertDefinitions(scenarioId, scenarioType, definitionRows);
            ScenarioTaskRequest task = new ScenarioTaskRequest();
            task.setScenarioId(scenarioId);
            task.setScenarioType(scenarioType);
            task.setValuationDate(valuationDate);
            task.setDefinitions(definitions);
            task.setCurrentMarketData(loadCurrentMarketData(scenarioId, valuationDate, definitions));
            task.setHistoricalMarketData(historicalMarketLoader.load(scenarioId, valuationDate, definitions));
            tasks.add(task);
        }

        request.setTasks(tasks);
        return request;
    }

    private List<Map<String, Object>> loadDefinitionRows(String scenarioId, String scenarioType) {
        switch (scenarioType) {
            case "MC":
                return emptyIfNull(scenarioMapper.selectMcScenarioMpByScenarioIdList(scenarioId));
            case "HISTORY":
            case "VAR":
            case "BACKTEST":
            case "SVAR":
                return emptyIfNull(scenarioMapper.selectHistoryScenarioMpByScenarioIdList(scenarioId));
            case "CUSTOM":
            case "KEY_RATE":
            default:
                return emptyIfNull(scenarioMapper.selectScenarioMpByScenarioIdList(scenarioId));
        }
    }

    private List<ScenarioDefinition> convertDefinitions(
            String scenarioId,
            String scenarioType,
            List<Map<String, Object>> rows) {
        List<ScenarioDefinition> result = new ArrayList<ScenarioDefinition>();
        for (Map<String, Object> row : rows) {
            ScenarioDefinition definition = new ScenarioDefinition();
            definition.setScenarioId(scenarioId);
            definition.setScenarioCode(firstNonBlank(row.get("SCENARIO_CODE"), scenarioId));
            definition.setScenarioName(toStringValue(row.get("SCENARIO_NAME")));
            String resolvedScenarioType = firstNonBlank(row.get("SCENARIO_TYPE"), scenarioType);
            definition.setScenarioType(resolvedScenarioType);
            definition.setCurveType(firstNonBlank(row.get("CURVE_TYPE"), row.get("RISKFACTOR_TYPE")));
            definition.setCurveCode(toStringValue(row.get("CURVE_CODE")));
            definition.setRiskGroupId(firstNonBlank(row.get("RISKGROUP_ID"), row.get("RISK_GROUP_ID")));
            definition.setTermDays(toInteger(row.get("TERM_DAYS")));
            definition.setTermCode(resolveDefinitionTermCode(resolvedScenarioType, row));
            definition.setShockValue(toBigDecimal(row.get("SCENARIO_SHIFT_VALUE")));
            definition.setScenarioShiftRule(resolveScenarioShiftRule(resolvedScenarioType, row));
            definition.setScenarioNo(toInteger(row.get("SCENARIO_NO")));
            definition.setHoldingPeriod(toInteger(row.get("HOLDING_PERIOD")));
            definition.setJumpDayNo(toInteger(firstNonBlank(row.get("JUNP_DAY_NO"), row.get("JUMP_DAY_NO"))));
            definition.setIncreaseDays(toInteger(row.get("INCREASE_DAYS")));
            definition.setHolidayCalendarCode(defaultHolidayCalendarCode);
            definition.setStartDate(toLocalDate(row.get("START_DATE")));
            result.add(definition);
        }
        return result;
    }

    private Map<String, List<ScenarioMarketSeries>> loadCurrentMarketData(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        Map<String, List<ScenarioMarketSeries>> result = new LinkedHashMap<String, List<ScenarioMarketSeries>>();
        Date currentDate = Date.valueOf(valuationDate);
        for (MarketQueryKey key : collectCurrentMarketKeys(definitions)) {
            List<Map<String, Object>> rows = queryMarketData(scenarioId, key.getCurveType(), key.getCurveCode(), currentDate, null);
            if (rows.isEmpty()) {
                continue;
            }
            result.computeIfAbsent(key.getCurveType(), ignore -> new ArrayList<ScenarioMarketSeries>())
                    .addAll(convertSeries(rows));
        }
        return result;
    }

    private List<Map<String, Object>> queryMarketData(String scenarioId, String curveType, String curveCode, Date startDate, Date endDate) {
        if (curveType == null || curveType.trim().isEmpty()) {
            return Collections.emptyList();
        }
        switch (curveType.trim()) {
            case "IR_SPOT":
                return emptyIfNull(scenarioMapper.selectIrData(scenarioId, curveCode, startDate, endDate));
            case "FX_SPOT":
                return emptyIfNull(scenarioMapper.selectFxData(scenarioId, curveCode, startDate, endDate));
            case "COMM_SPOT":
                return emptyIfNull(scenarioMapper.selectCommData(scenarioId, curveCode, startDate, endDate));
            case "EQ_SPOT":
                return emptyIfNull(scenarioMapper.selectEqData(scenarioId, curveCode, startDate, endDate));
            case "FX_VOL":
                return emptyIfNull(scenarioMapper.selectFxVolData(scenarioId, curveCode, startDate, endDate));
            case "IR_VOL":
                return emptyIfNull(scenarioMapper.selectIrVolData(scenarioId, curveCode, startDate, endDate));
            case "COMM_VOL":
                return emptyIfNull(scenarioMapper.selectCommVolData(scenarioId, curveCode, startDate, endDate));
            case "EQ_VOL":
                return emptyIfNull(scenarioMapper.selectEqVolData(scenarioId, curveCode, startDate, endDate));
            default:
                return Collections.emptyList();
        }
    }

    private Set<MarketQueryKey> collectCurrentMarketKeys(List<ScenarioDefinition> definitions) {
        Set<MarketQueryKey> keys = new LinkedHashSet<MarketQueryKey>();
        for (ScenarioDefinition definition : definitions) {
            String curveType = normalize(definition.getCurveType());
            String curveCode = normalize(definition.getCurveCode());
            if (curveType != null && curveCode != null) {
                keys.add(new MarketQueryKey(curveType, curveCode));
            }
        }
        return keys;
    }

    private List<ScenarioMarketSeries> convertSeries(List<Map<String, Object>> rows) {
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        for (Map<String, Object> row : rows) {
            result.add(convertSeriesRow(row));
        }
        return result;
    }

    private ScenarioMarketSeries convertSeriesRow(Map<String, Object> row) {
        ScenarioMarketSeries series = new ScenarioMarketSeries();
        series.setCurveType(toStringValue(row.get("CURVE_TYPE")));
        series.setCurveCode(toStringValue(row.get("CURVE_CODE")));
        series.setDataDate(toLocalDate(row.get("DATA_DATE")));
        series.setTermCode(toStringValue(row.get("TERM_CODE")));
        series.setTermDays(toInteger(row.get("TERM_DAYS")));
        series.setDimension2(firstNonBlank(row.get("VERTEX2"), row.get("UNDERLYING_TERM"), row.get("VOLATILITY_TERM")));
        series.setValue(toBigDecimal(row.get("RISKFACTOR_VALUE")));
        return series;
    }

    private List<Map<String, Object>> emptyIfNull(List<Map<String, Object>> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }

    private String toStringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = toStringValue(value);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof Date) {
            return ((Date) value).toLocalDate();
        }
        if (value instanceof java.util.Date) {
            java.util.Date date = (java.util.Date) value;
            return new Date(date.getTime()).toLocalDate();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private String resolveDefinitionTermCode(String scenarioType, Map<String, Object> row) {
        if (isCustomLikeScenarioType(scenarioType)) {
            return null;
        }
        return toStringValue(row.get("TERM_CODE"));
    }

    private boolean isCustomLikeScenarioType(String scenarioType) {
        return "CUSTOM".equals(scenarioType) || "KEY_RATE".equals(scenarioType);
    }

    private String resolveDefaultScenarioShiftRule(String scenarioType) {
        String normalized = normalize(scenarioType);
        if ("MC".equals(normalized)) {
            return "ABSOLUTE";
        }
        if ("HISTORY".equals(normalized)
                || "VAR".equals(normalized)
                || "SVAR".equals(normalized)
                || "BACKTEST".equals(normalized)) {
            return "RELATIVE";
        }
        return "ABSOLUTE";
    }

    private String resolveScenarioShiftRule(String scenarioType, Map<String, Object> row) {
        String explicitRule = normalize(toStringValue(row.get("SCENARIO_SHIFT_RULE")));
        if ("RELATIVE".equalsIgnoreCase(explicitRule)) {
            return "RELATIVE";
        }
        if ("ABSOLUTE".equalsIgnoreCase(explicitRule)) {
            return "ABSOLUTE";
        }
        return resolveDefaultScenarioShiftRule(scenarioType);
    }

    /**
     * 当前市场查询键。
     */
    private static class MarketQueryKey {
        private final String curveType;
        private final String curveCode;

        private MarketQueryKey(String curveType, String curveCode) {
            this.curveType = curveType;
            this.curveCode = curveCode;
        }

        public String getCurveType() {
            return curveType;
        }

        public String getCurveCode() {
            return curveCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MarketQueryKey)) {
                return false;
            }
            MarketQueryKey other = (MarketQueryKey) obj;
            return Objects.equals(curveType, other.curveType)
                    && Objects.equals(curveCode, other.curveCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(curveType, curveCode);
        }
    }
}
