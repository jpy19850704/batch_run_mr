package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.frtbima.rfet.model.RfetResult;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.springboot.measurement.ima.ImaRfetDataRepository;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;
import com.zcyh.mr.springboot.runtime.AlertService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 情景请求装配器。
 */
public class ScenarioRequestAssembler {
    private final ScenarioMapper scenarioMapper;
    private final MarketInputScenarioLoader marketInputScenarioLoader;
    private final ScenarioDefinitionExpansionService definitionExpansionService;
    private final ImaRfetDataRepository imaRfetDataRepository;
    private final String defaultHolidayCalendarCode;

    public ScenarioRequestAssembler(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.calendar.Calendar holidayCalendar,
            String defaultHolidayCalendarCode) {
        this(scenarioMapper, holidayCalendar, null, null, defaultHolidayCalendarCode, "USD");
    }

    public ScenarioRequestAssembler(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.calendar.Calendar holidayCalendar,
            AlertService alertService,
            String defaultHolidayCalendarCode) {
        this(scenarioMapper, holidayCalendar, alertService, null, defaultHolidayCalendarCode, "USD");
    }

    public ScenarioRequestAssembler(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.calendar.Calendar holidayCalendar,
            AlertService alertService,
            ImaRfetDataRepository imaRfetDataRepository,
            String defaultHolidayCalendarCode,
            String fxSpotBaseCurrency) {
        if (scenarioMapper == null) {
            throw new IllegalArgumentException("scenarioMapper 不能为空");
        }
        this.scenarioMapper = scenarioMapper;
        this.marketInputScenarioLoader = new MarketInputScenarioLoader(
                scenarioMapper, holidayCalendar, alertService, fxSpotBaseCurrency);
        this.definitionExpansionService = new ScenarioDefinitionExpansionService(scenarioMapper);
        this.imaRfetDataRepository = imaRfetDataRepository;
        this.defaultHolidayCalendarCode = normalize(defaultHolidayCalendarCode);
    }

    public ScenarioGenerationRequest build(
            String scenarioIdList,
            LocalDate valuationDate,
            String user,
            String source) {
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
        List<RfetResult> imaRfetResults = loadImaRfetResults(scenarioRows, valuationDate);
        for (Map<String, Object> scenarioRow : scenarioRows) {
            String scenarioId = toStringValue(scenarioRow.get("SCENARIO_ID"));
            String scenarioType = toStringValue(scenarioRow.get("SCENARIO_TYPE"));
            if (scenarioId == null || scenarioType == null) {
                continue;
            }
            ScenarioTaskRequest task = buildTask(
                    scenarioId, scenarioType, valuationDate, imaRfetResults);
            if (task != null) {
                tasks.add(task);
            }
        }
        request.setTasks(tasks);
        return request;
    }

    private ScenarioTaskRequest buildTask(
            String scenarioId,
            String scenarioType,
            LocalDate valuationDate,
            List<RfetResult> imaRfetResults) {
        List<Map<String, Object>> definitionRows = loadDefinitionRows(scenarioId, scenarioType);
        if (definitionRows.isEmpty()) {
            return null;
        }
        List<ScenarioDefinition> baseDefinitions = convertDefinitions(scenarioId, scenarioType, definitionRows);
        List<ScenarioDefinition> expandedDefinitions = definitionExpansionService.expandRiskGroups(baseDefinitions);
        MarketInputScenarioLoader.CurrentLoadResult fxSeedLoadResult =
                marketInputScenarioLoader.loadCurrent(scenarioId, valuationDate, expandedDefinitions);
        List<ScenarioDefinition> finalDefinitions = definitionExpansionService.expandFxSpotContainers(
                expandedDefinitions, fxSeedLoadResult.getMarketData());
        List<ScenarioDefinition> marketLoadDefinitions = definitionExpansionService.buildMarketLoadDefinitions(
                expandedDefinitions, finalDefinitions);
        MarketInputScenarioLoader.CurrentLoadResult currentLoadResult =
                marketInputScenarioLoader.loadCurrent(scenarioId, valuationDate, marketLoadDefinitions);

        ScenarioTaskRequest task = new ScenarioTaskRequest();
        task.setScenarioId(scenarioId);
        task.setScenarioType(scenarioType);
        task.setValuationDate(valuationDate);
        task.setDefinitions(finalDefinitions);
        task.setWarnings(currentLoadResult.getWarnings());
        task.setCurrentMarketData(currentLoadResult.getMarketData());
        task.setHistoricalMarketData(marketInputScenarioLoader.loadHistorical(
                scenarioId, valuationDate, marketLoadDefinitions));
        if (isImaScenario(scenarioType)) {
            task.setImaRfetResults(imaRfetResults);
        }
        return task;
    }

    private List<RfetResult> loadImaRfetResults(
            List<Map<String, Object>> scenarioRows,
            LocalDate valuationDate) {
        if (!hasImaScenario(scenarioRows)) {
            return Collections.emptyList();
        }
        if (imaRfetDataRepository == null) {
            throw new IllegalStateException("IMA 情景需要配置 RFET 数据读取组件");
        }
        List<RfetResult> results = imaRfetDataRepository.loadRfetResults(valuationDate);
        if (results.isEmpty()) {
            throw new IllegalStateException("MR_IMA_RFET_RESULT 未找到当前估值日数据，dataDate=" + valuationDate);
        }
        return results;
    }

    private List<Map<String, Object>> loadDefinitionRows(String scenarioId, String scenarioType) {
        switch (scenarioType) {
            case "MC":
                return emptyIfNull(scenarioMapper.selectMcScenarioMpByScenarioIdList(scenarioId));
            case "HISTORY":
            case "VAR":
            case "BACKTEST":
            case "SVAR":
            case "IMA_NORMAL":
            case "IMA_STRESS":
            case "IMA_NMRF":
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
            definition.setScenarioName(toStringValue(row.get("SCENARIO_NAME")));
            String resolvedScenarioType = firstNonBlank(row.get("SCENARIO_TYPE"), scenarioType);
            definition.setScenarioType(resolvedScenarioType);
            definition.setReducedSetFlag(toBoolean(row.get("REDUCED_SET_FLAG")));
            definition.setCurveType(toStringValue(row.get("CURVE_TYPE")));
            definition.setCurveCode(toStringValue(row.get("CURVE_CODE")));
            definition.setRiskGroupId(toStringValue(row.get("RISKGROUP_ID")));
            definition.setTermDays(toInteger(row.get("TERM_DAYS")));
            definition.setTermCode(resolveDefinitionTermCode(resolvedScenarioType, row));
            definition.setShockValue(toBigDecimal(row.get("SCENARIO_SHIFT_VALUE")));
            definition.setScenarioShiftRule(resolveScenarioShiftRule(resolvedScenarioType, row));
            definition.setScenarioNo(toInteger(row.get("SCENARIO_NO")));
            definition.setHoldingPeriod(toInteger(row.get("HOLDING_PERIOD")));
            definition.setJumpDayNo(toInteger(row.get("JUMP_DAY_NO")));
            definition.setIncreaseDays(toInteger(row.get("INCREASE_DAYS")));
            definition.setHolidayCalendarCode(firstNonBlank(
                    row.get("HOLIDAY_CALENDAR"),
                    row.get("holiday_calendar"),
                    defaultHolidayCalendarCode));
            definition.setStartDate(toLocalDate(row.get("START_DATE")));
            definition.setEndDate(toLocalDate(row.get("END_DATE")));
            result.add(definition);
        }
        return result;
    }

    private boolean hasImaScenario(List<Map<String, Object>> scenarioRows) {
        for (Map<String, Object> row : scenarioRows) {
            if (isImaScenario(toStringValue(row.get("SCENARIO_TYPE")))) {
                return true;
            }
        }
        return false;
    }

    private boolean isImaScenario(String scenarioType) {
        String safe = normalize(scenarioType);
        return "IMA_NORMAL".equals(safe) || "IMA_STRESS".equals(safe) || "IMA_NMRF".equals(safe);
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
            String text = value == null ? null : value.toString().trim();
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

    private Boolean toBoolean(Object value) {
        if (value == null) {
            return Boolean.FALSE;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = value.toString().trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text);
    }

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        }
        if (value instanceof java.util.Date) {
            return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
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
        if ("CUSTOM".equals(scenarioType) || "KEY_RATE".equals(scenarioType)) {
            return null;
        }
        return toStringValue(row.get("TERM_CODE"));
    }

    private String resolveScenarioShiftRule(String scenarioType, Map<String, Object> row) {
        String explicitRule = normalize(toStringValue(row.get("SCENARIO_SHIFT_RULE")));
        if ("RELATIVE".equalsIgnoreCase(explicitRule)) {
            return "RELATIVE";
        }
        if ("ABSOLUTE".equalsIgnoreCase(explicitRule)) {
            return "ABSOLUTE";
        }
        String normalized = normalize(scenarioType);
        if ("MC".equals(normalized)) {
            return "ABSOLUTE";
        }
        if ("HISTORY".equals(normalized)
                || "VAR".equals(normalized)
                || "SVAR".equals(normalized)
                || "BACKTEST".equals(normalized)
                || "IMA_NORMAL".equals(normalized)
                || "IMA_STRESS".equals(normalized)
                || "IMA_NMRF".equals(normalized)) {
            return "RELATIVE";
        }
        return "ABSOLUTE";
    }
}
