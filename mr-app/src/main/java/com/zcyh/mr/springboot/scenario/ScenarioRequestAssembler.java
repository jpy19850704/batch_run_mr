package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;
import com.zcyh.mr.springboot.service.AlertService;

import java.math.BigDecimal;
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
 */
public class ScenarioRequestAssembler {

    private static final String FX_SPOT = "FX_SPOT";

    private final ScenarioMapper scenarioMapper;
    private final MarketInputScenarioLoader marketInputScenarioLoader;
    private final String defaultHolidayCalendarCode;

    public ScenarioRequestAssembler(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar,
            String defaultHolidayCalendarCode) {
        this(scenarioMapper, holidayCalendar, null, defaultHolidayCalendarCode, "USD");
    }

    public ScenarioRequestAssembler(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar,
            AlertService alertService,
            String defaultHolidayCalendarCode) {
        this(scenarioMapper, holidayCalendar, alertService, defaultHolidayCalendarCode, "USD");
    }

    public ScenarioRequestAssembler(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar,
            AlertService alertService,
            String defaultHolidayCalendarCode,
            String fxSpotBaseCurrency) {
        if (scenarioMapper == null) {
            throw new IllegalArgumentException("scenarioMapper 不能为空");
        }
        this.scenarioMapper = scenarioMapper;
        this.marketInputScenarioLoader = new MarketInputScenarioLoader(scenarioMapper, holidayCalendar, alertService, fxSpotBaseCurrency);
        this.defaultHolidayCalendarCode = normalize(defaultHolidayCalendarCode);
    }

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
            if (scenarioId == null || scenarioType == null) {
                continue;
            }

            List<Map<String, Object>> definitionRows = loadDefinitionRows(scenarioId, scenarioType);
            if (definitionRows.isEmpty()) {
                continue;
            }

            List<ScenarioDefinition> baseDefinitions = convertDefinitions(scenarioId, scenarioType, definitionRows);
            List<ScenarioDefinition> expandedDefinitions = expandRiskGroupDefinitions(baseDefinitions);
            MarketInputScenarioLoader.CurrentLoadResult fxSeedLoadResult =
                    marketInputScenarioLoader.loadCurrent(scenarioId, valuationDate, expandedDefinitions);
            List<ScenarioDefinition> finalDefinitions =
                    expandFxSpotContainerDefinitions(expandedDefinitions, fxSeedLoadResult.getMarketData());
            List<ScenarioDefinition> marketLoadDefinitions =
                    buildMarketLoadDefinitions(expandedDefinitions, finalDefinitions);
            MarketInputScenarioLoader.CurrentLoadResult currentLoadResult =
                    marketInputScenarioLoader.loadCurrent(scenarioId, valuationDate, marketLoadDefinitions);

            ScenarioTaskRequest task = new ScenarioTaskRequest();
            task.setScenarioId(scenarioId);
            task.setScenarioType(scenarioType);
            task.setValuationDate(valuationDate);
            task.setDefinitions(finalDefinitions);
            task.setWarnings(currentLoadResult.getWarnings());
            task.setCurrentMarketData(currentLoadResult.getMarketData());
            task.setHistoricalMarketData(marketInputScenarioLoader.loadHistorical(valuationDate, marketLoadDefinitions));
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
            definition.setHolidayCalendarCode(defaultHolidayCalendarCode);
            definition.setStartDate(toLocalDate(row.get("START_DATE")));
            definition.setEndDate(toLocalDate(row.get("END_DATE")));
            result.add(definition);
        }
        return result;
    }

    private List<ScenarioDefinition> expandRiskGroupDefinitions(List<ScenarioDefinition> definitions) {
        Map<String, List<RiskGroupMember>> membersByGroup = loadRiskGroupMembers(definitions);
        List<ScenarioDefinition> result = new ArrayList<ScenarioDefinition>();
        for (ScenarioDefinition definition : definitions) {
            String riskGroupId = normalize(definition.getRiskGroupId());
            if (riskGroupId == null) {
                result.add(definition);
                continue;
            }
            List<RiskGroupMember> members = membersByGroup.get(riskGroupId);
            boolean expanded = false;
            if (members != null) {
                for (RiskGroupMember member : members) {
                    if (!matchCurveType(definition.getCurveType(), member.riskFactorType)) {
                        continue;
                    }
                    ScenarioDefinition copied = copyDefinition(definition);
                    copied.setCurveType(member.riskFactorType);
                    copied.setCurveCode(member.riskFactorId);
                    result.add(copied);
                    expanded = true;
                }
            }
            if (!expanded && normalize(definition.getCurveCode()) != null) {
                result.add(definition);
            }
        }
        return result;
    }

    private Map<String, List<RiskGroupMember>> loadRiskGroupMembers(List<ScenarioDefinition> definitions) {
        Set<String> groupIds = new LinkedHashSet<String>();
        for (ScenarioDefinition definition : definitions) {
            String riskGroupId = normalize(definition.getRiskGroupId());
            if (riskGroupId != null) {
                groupIds.add(riskGroupId);
            }
        }
        if (groupIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = scenarioMapper.selectRiskGroupMembers(new ArrayList<String>(groupIds));
        Map<String, List<RiskGroupMember>> result = new LinkedHashMap<String, List<RiskGroupMember>>();
        for (Map<String, Object> row : rows) {
            String riskGroupId = normalize(toStringValue(row.get("RISKGROUP_ID")));
            String riskFactorType = normalize(toStringValue(row.get("RISKFACTOR_TYPE")));
            String riskFactorId = normalize(toStringValue(row.get("RISKFACTOR_ID")));
            if (riskGroupId == null || riskFactorType == null || riskFactorId == null) {
                continue;
            }
            result.computeIfAbsent(riskGroupId, key -> new ArrayList<RiskGroupMember>())
                    .add(new RiskGroupMember(riskFactorType, riskFactorId));
        }
        return result;
    }

    private List<ScenarioDefinition> expandFxSpotContainerDefinitions(
            List<ScenarioDefinition> definitions,
            Map<String, List<ScenarioMarketSeries>> currentMarketData) {
        List<ScenarioMarketSeries> fxSeries = currentMarketData == null ? null : currentMarketData.get(FX_SPOT);
        if (fxSeries == null || fxSeries.isEmpty()) {
            return definitions;
        }
        LinkedHashSet<String> fxPairs = new LinkedHashSet<String>();
        for (ScenarioMarketSeries series : fxSeries) {
            String curveCode = normalize(series.getCurveCode());
            if (curveCode != null) {
                fxPairs.add(curveCode);
            }
        }
        if (fxPairs.isEmpty()) {
            return definitions;
        }
        List<ScenarioDefinition> result = new ArrayList<ScenarioDefinition>();
        for (ScenarioDefinition definition : definitions) {
            String curveType = normalize(definition.getCurveType());
            String curveCode = normalize(definition.getCurveCode());
            String riskGroupId = normalize(definition.getRiskGroupId());
            if (!FX_SPOT.equals(curveType) || riskGroupId != null || curveCode == null || curveCode.contains("/")) {
                result.add(definition);
                continue;
            }
            for (String fxPair : fxPairs) {
                ScenarioDefinition copied = copyDefinition(definition);
                copied.setCurveCode(fxPair);
                result.add(copied);
            }
        }
        return result;
    }

    private List<ScenarioDefinition> buildMarketLoadDefinitions(
            List<ScenarioDefinition> expandedDefinitions,
            List<ScenarioDefinition> finalDefinitions) {
        List<ScenarioDefinition> result = new ArrayList<ScenarioDefinition>();
        if (finalDefinitions != null && !finalDefinitions.isEmpty()) {
            result.addAll(finalDefinitions);
        }
        if (expandedDefinitions == null || expandedDefinitions.isEmpty()) {
            return result;
        }
        for (ScenarioDefinition definition : expandedDefinitions) {
            String curveType = normalize(definition.getCurveType());
            String curveCode = normalize(definition.getCurveCode());
            if (!FX_SPOT.equals(curveType) || curveCode == null || curveCode.contains("/")) {
                continue;
            }
            result.add(copyDefinition(definition));
        }
        return result;
    }

    private ScenarioDefinition copyDefinition(ScenarioDefinition source) {
        ScenarioDefinition copied = new ScenarioDefinition();
        copied.setScenarioId(source.getScenarioId());
        copied.setScenarioName(source.getScenarioName());
        copied.setScenarioType(source.getScenarioType());
        copied.setReducedSetFlag(source.getReducedSetFlag());
        copied.setCurveType(source.getCurveType());
        copied.setCurveCode(source.getCurveCode());
        copied.setRiskGroupId(source.getRiskGroupId());
        copied.setTermCode(source.getTermCode());
        copied.setTermDays(source.getTermDays());
        copied.setShockValue(source.getShockValue());
        copied.setScenarioShiftRule(source.getScenarioShiftRule());
        copied.setScenarioNo(source.getScenarioNo());
        copied.setHoldingPeriod(source.getHoldingPeriod());
        copied.setJumpDayNo(source.getJumpDayNo());
        copied.setIncreaseDays(source.getIncreaseDays());
        copied.setHolidayCalendarCode(source.getHolidayCalendarCode());
        copied.setStartDate(source.getStartDate());
        copied.setEndDate(source.getEndDate());
        return copied;
    }

    private boolean matchCurveType(String definitionCurveType, String riskFactorType) {
        String normalizedCurveType = normalize(definitionCurveType);
        String normalizedRiskFactorType = normalize(riskFactorType);
        if (normalizedCurveType == null) {
            return normalizedRiskFactorType == null;
        }
        return normalizedCurveType.equals(normalizedRiskFactorType);
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
            java.util.Date date = (java.util.Date) value;
            return new java.sql.Date(date.getTime()).toLocalDate();
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
                || "BACKTEST".equals(normalized)
                || "IMA_NORMAL".equals(normalized)
                || "IMA_STRESS".equals(normalized)
                || "IMA_NMRF".equals(normalized)) {
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

    private static class RiskGroupMember {
        private final String riskFactorType;
        private final String riskFactorId;

        private RiskGroupMember(String riskFactorType, String riskFactorId) {
            this.riskFactorType = riskFactorType;
            this.riskFactorId = riskFactorId;
        }
    }
}
