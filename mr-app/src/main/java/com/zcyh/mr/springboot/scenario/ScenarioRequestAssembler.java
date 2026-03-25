package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioHolidayCalendar;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
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

    public ScenarioRequestAssembler(ScenarioMapper scenarioMapper) {
        if (scenarioMapper == null) {
            throw new IllegalArgumentException("scenarioMapper 不能为空");
        }
        this.scenarioMapper = scenarioMapper;
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
            task.setHistoricalMarketData(loadHistoricalMarketData(scenarioId, valuationDate, definitions));
            task.setHolidayCalendars(loadHolidayCalendars(definitions));
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
            definition.setTerm(toStringValue(row.get("TERM_CODE")));
            definition.setTermDays(toInteger(row.get("TERM_DAYS")));
            definition.setShockValue(toBigDecimal(row.get("SCENARIO_SHIFT_VALUE")));
            definition.setShockType(toStringValue(row.get("SHOCK_TYPE")));
            definition.setShockRule(toStringValue(row.get("SCENARIO_SHIFT_RULE")));
            definition.setScenarioNo(toInteger(row.get("SCENARIO_NO")));
            definition.setHoldingPeriod(toInteger(row.get("HOLDING_PERIOD")));
            definition.setJumpDayNo(toInteger(firstNonBlank(row.get("JUNP_DAY_NO"), row.get("JUMP_DAY_NO"))));
            definition.setIncreaseDays(toInteger(row.get("INCREASE_DAYS")));
            definition.setStartDate(toLocalDate(row.get("START_DATE")));
            definition.setEndDate(toLocalDate(firstNonBlank(row.get("END_DATE"), row.get("CAL_END_DATE"))));

            Map<String, Object> extraParams = new LinkedHashMap<String, Object>(row);
            extraParams.remove("SCENARIO_ID");
            extraParams.remove("SCENARIO_CODE");
            extraParams.remove("SCENARIO_NAME");
            extraParams.remove("SCENARIO_TYPE");
            extraParams.remove("CURVE_TYPE");
            extraParams.remove("RISKFACTOR_TYPE");
            extraParams.remove("CURVE_CODE");
            extraParams.remove("RISKGROUP_ID");
            extraParams.remove("RISK_GROUP_ID");
            extraParams.remove("TERM_CODE");
            extraParams.remove("TERM_DAYS");
            extraParams.remove("SCENARIO_SHIFT_VALUE");
            extraParams.remove("SCENARIO_SHIFT_RULE");
            extraParams.remove("SHOCK_TYPE");
            extraParams.remove("SCENARIO_NO");
            extraParams.remove("HOLDING_PERIOD");
            extraParams.remove("JUNP_DAY_NO");
            extraParams.remove("JUMP_DAY_NO");
            extraParams.remove("INCREASE_DAYS");
            extraParams.remove("START_DATE");
            extraParams.remove("END_DATE");
            definition.setExtraParams(extraParams);
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

    private Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> loadHistoricalMarketData(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> result =
                new LinkedHashMap<String, Map<LocalDate, List<ScenarioMarketSeries>>>();

        DateRange dateRange = calculateHistoryRange(definitions, valuationDate);
        if (dateRange == null) {
            return result;
        }

        for (String curveType : collectCurveTypes(definitions)) {
            List<Map<String, Object>> rows = queryMarketData(scenarioId, curveType, dateRange.startDate, dateRange.endDate);
            Map<LocalDate, List<ScenarioMarketSeries>> grouped =
                    new LinkedHashMap<LocalDate, List<ScenarioMarketSeries>>();
            for (Map<String, Object> row : rows) {
                LocalDate dataDate = toLocalDate(row.get("DATA_DATE"));
                if (dataDate == null) {
                    continue;
                }
                grouped.computeIfAbsent(dataDate, key -> new ArrayList<ScenarioMarketSeries>())
                        .add(convertSeriesRow(row));
            }
            result.put(curveType, grouped);
        }
        return result;
    }

    private Map<String, ScenarioHolidayCalendar> loadHolidayCalendars(List<ScenarioDefinition> definitions) {
        Map<String, ScenarioHolidayCalendar> result = new LinkedHashMap<String, ScenarioHolidayCalendar>();
        Set<String> calendarCodes = new LinkedHashSet<String>();
        for (ScenarioDefinition definition : definitions) {
            if (definition.getExtraParams() == null || definition.getExtraParams().isEmpty()) {
                continue;
            }
            for (String key : new String[]{"HOLIDAY_CALENDAR", "CALENDAR_CODE", "CALENDAR", "CAL_PEK"}) {
                Object value = definition.getExtraParams().get(key);
                String code = toStringValue(value);
                if (code != null && !code.isEmpty()) {
                    calendarCodes.add(code);
                }
            }
        }

        for (String calendarCode : calendarCodes) {
            List<Map<String, Object>> rows = emptyIfNull(scenarioMapper.getHolidayDate(calendarCode));
            ScenarioHolidayCalendar calendar = new ScenarioHolidayCalendar();
            calendar.setCalendarCode(calendarCode);
            Set<LocalDate> holidays = new LinkedHashSet<LocalDate>();
            for (Map<String, Object> row : rows) {
                LocalDate holidayDate = toLocalDate(firstNonBlank(row.get("DATA_DATE"), row.get("HOLIDAY")));
                if (holidayDate != null) {
                    holidays.add(holidayDate);
                }
            }
            calendar.setHolidayDates(holidays);
            result.put(calendarCode, calendar);
        }
        return result;
    }

    private List<Map<String, Object>> queryMarketData(String scenarioId, String curveType, Date startDate, Date endDate) {
        if (curveType == null || curveType.trim().isEmpty()) {
            return Collections.emptyList();
        }
        switch (curveType.trim()) {
            case "IR_SPOT":
                return emptyIfNull(scenarioMapper.selectIrData(scenarioId, startDate, endDate));
            case "FX_SPOT":
                return emptyIfNull(scenarioMapper.selectFxData(scenarioId, startDate, endDate));
            case "COMM_SPOT":
                return emptyIfNull(scenarioMapper.selectCommData(scenarioId, startDate, endDate));
            case "EQ_SPOT":
                return emptyIfNull(scenarioMapper.selectEqData(scenarioId, startDate, endDate));
            case "FX_VOL":
                return emptyIfNull(scenarioMapper.selectFxVolData(scenarioId, startDate, endDate));
            case "IR_VOL":
                return emptyIfNull(scenarioMapper.selectIrVolData(scenarioId, startDate, endDate));
            case "COMM_VOL":
                return emptyIfNull(scenarioMapper.selectCommVolData(scenarioId, startDate, endDate));
            case "EQ_VOL":
                return emptyIfNull(scenarioMapper.selectEqVolData(scenarioId, startDate, endDate));
            default:
                return Collections.emptyList();
        }
    }

    private DateRange calculateHistoryRange(List<ScenarioDefinition> definitions, LocalDate valuationDate) {
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }
        ScenarioDefinition first = definitions.get(0);
        String scenarioType = first.getScenarioType();
        if (!requiresHistoryData(scenarioType)) {
            return null;
        }

        Date currentDate = Date.valueOf(valuationDate);
        switch (scenarioType) {
            case "VAR":
                return calculateVarDateRange(first, currentDate);
            case "BACKTEST":
                return calculateBacktestDateRange(currentDate);
            case "SVAR":
                return calculateSvarDateRange(first, currentDate);
            case "HISTORY":
            default:
                return calculateHistoryDateRange(first, currentDate);
        }
    }

    private boolean requiresHistoryData(String scenarioType) {
        return "HISTORY".equals(scenarioType)
                || "VAR".equals(scenarioType)
                || "BACKTEST".equals(scenarioType)
                || "SVAR".equals(scenarioType)
                || "MC".equals(scenarioType);
    }

    private DateRange calculateVarDateRange(ScenarioDefinition definition, Date currentDate) {
        int dayNo = resolveDayNo(definition);
        int maxDayNo = reserveDayNo(dayNo);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(currentDate);
        calendar.add(Calendar.DATE, -maxDayNo);
        return new DateRange(new Date(calendar.getTimeInMillis()), currentDate);
    }

    private DateRange calculateBacktestDateRange(Date currentDate) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(currentDate);
        calendar.add(Calendar.DATE, 30);
        return new DateRange(currentDate, new Date(calendar.getTimeInMillis()));
    }

    private DateRange calculateSvarDateRange(ScenarioDefinition definition, Date currentDate) {
        LocalDate startLocalDate = definition.getStartDate();
        if (startLocalDate == null) {
            return calculateVarDateRange(definition, currentDate);
        }
        Date startDate = Date.valueOf(startLocalDate);
        int dayNo = resolveDayNo(definition);
        int maxDayNo = reserveDayNo(dayNo);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startDate);
        calendar.add(Calendar.DATE, maxDayNo);
        return new DateRange(startDate, new Date(calendar.getTimeInMillis()));
    }

    private DateRange calculateHistoryDateRange(ScenarioDefinition definition, Date currentDate) {
        String calType = toStringValue(definition.getExtraParams().get("CAL_TYPE"));
        if ("ABSOLUTE".equals(calType)) {
            LocalDate startLocalDate = toLocalDate(definition.getExtraParams().get("CAL_START_DATE"));
            LocalDate endLocalDate = toLocalDate(definition.getExtraParams().get("CAL_END_DATE"));
            if (startLocalDate == null || endLocalDate == null) {
                return calculateVarDateRange(definition, currentDate);
            }
            int maxDayNo = reserveDayNo(resolveDayNo(definition));
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(Date.valueOf(startLocalDate));
            calendar.add(Calendar.DATE, maxDayNo);
            return new DateRange(Date.valueOf(startLocalDate), new Date(calendar.getTimeInMillis()));
        }
        return calculateVarDateRange(definition, currentDate);
    }

    private int resolveDayNo(ScenarioDefinition definition) {
        int scenarioNo = definition.getScenarioNo() == null ? 1 : definition.getScenarioNo();
        int increaseDays = definition.getIncreaseDays() == null ? 1 : definition.getIncreaseDays();
        int jumpDayNo = definition.getJumpDayNo() == null ? 1 : definition.getJumpDayNo();
        return (scenarioNo - 1) * increaseDays + jumpDayNo;
    }

    private int reserveDayNo(int dayNo) {
        return dayNo + dayNo / 7 * 2 + (dayNo / 100 + 1) * 30;
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
        series.setCurveId(toStringValue(row.get("CURVE_CODE")));
        series.setTermCode(toStringValue(row.get("TERM_CODE")));
        series.setTermDays(toInteger(row.get("TERM_DAYS")));
        series.setDimension2(firstNonBlank(row.get("VERTEX2"), row.get("UNDERLYING_TERM"), row.get("VOLATILITY_TERM")));
        series.setValue(toBigDecimal(row.get("YIELD_RATE")));

        Map<String, Object> metadata = new LinkedHashMap<String, Object>(row);
        metadata.remove("CURVE_TYPE");
        metadata.remove("CURVE_CODE");
        metadata.remove("DATA_DATE");
        metadata.remove("TERM_CODE");
        metadata.remove("TERM_DAYS");
        metadata.remove("YIELD_RATE");
        metadata.remove("VERTEX2");
        metadata.remove("UNDERLYING_TERM");
        metadata.remove("VOLATILITY_TERM");
        series.setMetadata(metadata);
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

    private static class DateRange {
        private final Date startDate;
        private final Date endDate;

        private DateRange(Date startDate, Date endDate) {
            this.startDate = startDate;
            this.endDate = endDate;
        }
    }
}
