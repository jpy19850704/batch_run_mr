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
            definition.setScenarioType(firstNonBlank(row.get("SCENARIO_TYPE"), scenarioType));
            definition.setCurveType(firstNonBlank(row.get("CURVE_TYPE"), row.get("RISKFACTOR_TYPE")));
            definition.setCurveCode(toStringValue(row.get("CURVE_CODE")));
            definition.setRiskGroupId(firstNonBlank(row.get("RISKGROUP_ID"), row.get("RISK_GROUP_ID")));
            definition.setTermCode(toStringValue(row.get("TERM_CODE")));
            definition.setTermDays(toInteger(row.get("TERM_DAYS")));
            definition.setShockValue(toBigDecimal(row.get("SCENARIO_SHIFT_VALUE")));
            definition.setShockType(toStringValue(row.get("SHOCK_TYPE")));
            definition.setShockRule(toStringValue(row.get("SCENARIO_SHIFT_RULE")));
            definition.setScenarioNo(toInteger(row.get("SCENARIO_NO")));
            definition.setHoldingPeriod(toInteger(row.get("HOLDING_PERIOD")));
            definition.setJumpDayNo(toInteger(firstNonBlank(row.get("JUNP_DAY_NO"), row.get("JUMP_DAY_NO"))));
            definition.setIncreaseDays(toInteger(row.get("INCREASE_DAYS")));
            definition.setHolidayCalendarCode(defaultHolidayCalendarCode);
            definition.setStartDate(toLocalDate(firstNonBlank(row.get("START_DATE"), row.get("CAL_START_DATE"))));
            definition.setEndDate(toLocalDate(firstNonBlank(row.get("END_DATE"), row.get("CAL_END_DATE"))));
            result.add(definition);
        }
        return result;
    }

    private Map<String, List<ScenarioMarketSeries>> loadCurrentMarketData(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        Map<String, List<ScenarioMarketSeries>> result = new LinkedHashMap<String, List<ScenarioMarketSeries>>();
        for (String curveType : collectCurveTypes(definitions)) {
            List<Map<String, Object>> rows = queryMarketData(scenarioId, curveType, Date.valueOf(valuationDate), null);
            result.put(curveType, convertSeries(rows));
        }
        return result;
    }

    private List<Map<String, Object>> queryMarketData(String scenarioId, String curveType, Date startDate, Date endDate) {
        if (curveType == null || curveType.trim().isEmpty()) {
            return Collections.emptyList();
        }
        switch (curveType.trim()) {
            case "IR_SPOT":
                return emptyIfNull(scenarioMapper.selectIrData(scenarioId, null, startDate, endDate));
            case "FX_SPOT":
                return emptyIfNull(scenarioMapper.selectFxData(scenarioId, null, startDate, endDate));
            case "COMM_SPOT":
                return emptyIfNull(scenarioMapper.selectCommData(scenarioId, null, startDate, endDate));
            case "EQ_SPOT":
                return emptyIfNull(scenarioMapper.selectEqData(scenarioId, null, startDate, endDate));
            case "FX_VOL":
                return emptyIfNull(scenarioMapper.selectFxVolData(scenarioId, null, startDate, endDate));
            case "IR_VOL":
                return emptyIfNull(scenarioMapper.selectIrVolData(scenarioId, null, startDate, endDate));
            case "COMM_VOL":
                return emptyIfNull(scenarioMapper.selectCommVolData(scenarioId, null, startDate, endDate));
            case "EQ_VOL":
                return emptyIfNull(scenarioMapper.selectEqVolData(scenarioId, null, startDate, endDate));
            default:
                return Collections.emptyList();
        }
    }

    private Set<String> collectCurveTypes(List<ScenarioDefinition> definitions) {
        Set<String> curveTypes = new LinkedHashSet<String>();
        for (ScenarioDefinition definition : definitions) {
            if (definition.getCurveType() != null && !definition.getCurveType().trim().isEmpty()) {
                curveTypes.add(definition.getCurveType().trim());
            }
        }
        return curveTypes;
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
        series.setValue(toBigDecimal(row.get("YIELD_RATE")));
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
}
