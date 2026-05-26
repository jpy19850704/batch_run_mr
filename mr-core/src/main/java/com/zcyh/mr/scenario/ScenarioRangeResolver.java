package com.zcyh.mr.scenario;

import com.zcyh.mr.core.SystemCalendarCache;
import com.zcyh.mr.scenario.model.ScenarioDefinition;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 情景日期区间解析器。
 *
 * <p>
 * 该类统一定义历史类情景的计量区间与查询区间规则，
 * app 侧查询规划与 core 侧计量逻辑都应复用这里的结果。
 */
public class ScenarioRangeResolver {

    private final com.zcyh.mr.core.Calendar holidayCalendar;

    public ScenarioRangeResolver() {
        this(null);
    }

    public ScenarioRangeResolver(com.zcyh.mr.core.Calendar holidayCalendar) {
        this.holidayCalendar = SystemCalendarCache.resolve(holidayCalendar);
    }

    /**
     * 解析日期区间。
     */
    public ResolvedRange resolve(ScenarioDefinition definition, LocalDate valuationDate) {
        if (definition == null || valuationDate == null) {
            return null;
        }

        String scenarioType = normalize(definition.getScenarioType());
        if (!requiresHistoryData(scenarioType)) {
            return null;
        }

        switch (scenarioType) {
            case "VAR":
                return calculateVarRange(definition, valuationDate);
            case "BACKTEST":
                return calculateBacktestRange(definition, valuationDate);
            case "SVAR":
                return calculateSvarRange(definition, valuationDate);
            case "MC":
                return calculateMcRange(definition, valuationDate);
            case "HISTORY":
            default:
                return calculateHistoryRange(definition, valuationDate);
        }
    }

    /**
     * 是否需要历史数据。
     */
    public boolean requiresHistoryData(String scenarioType) {
        String normalized = normalize(scenarioType);
        return "HISTORY".equals(normalized)
                || "VAR".equals(normalized)
                || "BACKTEST".equals(normalized)
                || "SVAR".equals(normalized)
                || "MC".equals(normalized);
    }

    private ResolvedRange calculateVarRange(ScenarioDefinition definition, LocalDate valuationDate) {
        String calendarCode = resolveCalendarCode(definition);
        int jumpDayNo = resolveJumpDayNo(definition);
        int increaseDays = resolveIncreaseDays(definition);
        int scenarioNumber = resolveScenarioNumber(definition);
        List<LocalDate> sampleDates = buildBackwardSamples(calendarCode, valuationDate, scenarioNumber, increaseDays);
        return buildResolvedRange(sampleDates, jumpDayNo, increaseDays, calendarCode);
    }

    private ResolvedRange calculateMcRange(ScenarioDefinition definition, LocalDate valuationDate) {
        String calendarCode = resolveCalendarCode(definition);
        int jumpDayNo = resolveJumpDayNo(definition);
        int increaseDays = resolveIncreaseDays(definition);
        int scenarioNumber = resolveScenarioNumber(definition);
        List<LocalDate> sampleDates = buildBackwardSamples(calendarCode, valuationDate, scenarioNumber, increaseDays);
        return buildResolvedRange(sampleDates, jumpDayNo, increaseDays, calendarCode);
    }

    private ResolvedRange calculateBacktestRange(ScenarioDefinition definition, LocalDate valuationDate) {
        String calendarCode = resolveCalendarCode(definition);
        int jumpDayNo = 1;
        int increaseDays = 1;
        LocalDate sampleDate = moveBusinessDays(calendarCode, alignDate(calendarCode, valuationDate, true), increaseDays);
        return buildResolvedRange(Collections.singletonList(sampleDate), jumpDayNo, increaseDays, calendarCode);
    }

    private ResolvedRange calculateSvarRange(ScenarioDefinition definition, LocalDate valuationDate) {
        LocalDate startDate = definition.getStartDate();
        if (startDate == null) {
            logSkip(definition, "SVAR 缺少 START_DATE");
            return null;
        }
        String calendarCode = resolveCalendarCode(definition);
        int jumpDayNo = resolveJumpDayNo(definition);
        int increaseDays = resolveIncreaseDays(definition);
        int scenarioNumber = resolveScenarioNumber(definition);
        if (scenarioNumber <= 0) {
            logSkip(definition, "SVAR 缺少有效的 SCENARIO_NO");
            return null;
        }
        List<LocalDate> sampleDates = buildForwardSamples(calendarCode, startDate, scenarioNumber, increaseDays, null);
        return buildResolvedRange(sampleDates, jumpDayNo, increaseDays, calendarCode);
    }

    private ResolvedRange calculateHistoryRange(ScenarioDefinition definition, LocalDate valuationDate) {
        LocalDate startDate = definition.getStartDate();
        if (startDate == null) {
            logSkip(definition, "HISTORY 缺少 START_DATE");
            return null;
        }
        int scenarioNumber = resolveScenarioNumber(definition);
        if (scenarioNumber <= 0) {
            logSkip(definition, "HISTORY 缺少有效的 SCENARIO_NO");
            return null;
        }
        String calendarCode = resolveCalendarCode(definition);
        int jumpDayNo = resolveJumpDayNo(definition);
        int increaseDays = resolveIncreaseDays(definition);
        List<LocalDate> sampleDates = buildForwardSamples(calendarCode, startDate, scenarioNumber, increaseDays, null);
        if (sampleDates.isEmpty()) {
            logSkip(definition, "HISTORY 基于 START_DATE 与 SCENARIO_NO 未生成有效样本日期");
            return null;
        }
        return buildResolvedRange(sampleDates, jumpDayNo, increaseDays, calendarCode);
    }

    private ResolvedRange buildResolvedRange(
            List<LocalDate> sampleDates,
            int jumpDayNo,
            int increaseDays,
            String calendarCode) {
        if (sampleDates == null || sampleDates.isEmpty()) {
            return null;
        }
        List<LocalDate> normalizedSampleDates = new ArrayList<LocalDate>(sampleDates);
        List<LocalDate> comparisonDates = new ArrayList<LocalDate>(normalizedSampleDates.size());
        for (LocalDate sampleDate : normalizedSampleDates) {
            comparisonDates.add(moveBusinessDays(calendarCode, sampleDate, -jumpDayNo));
        }

        LocalDate calculationStartDate = normalizedSampleDates.stream().min(LocalDate::compareTo).orElse(null);
        LocalDate calculationEndDate = normalizedSampleDates.stream().max(LocalDate::compareTo).orElse(null);
        LocalDate dataSearchStartDate = comparisonDates.stream().min(LocalDate::compareTo).orElse(calculationStartDate);
        LocalDate dataSearchEndDate = calculationEndDate;
        List<LocalDate> dataSearchDates = buildBusinessSpan(calendarCode, dataSearchStartDate, dataSearchEndDate);

        return new ResolvedRange(
                dataSearchStartDate,
                dataSearchEndDate,
                calculationStartDate,
                calculationEndDate,
                jumpDayNo,
                increaseDays,
                normalizedSampleDates,
                comparisonDates,
                dataSearchDates);
    }

    private int resolveJumpDayNo(ScenarioDefinition definition) {
        if (definition == null || definition.getJumpDayNo() == null || definition.getJumpDayNo() <= 0) {
            return 1;
        }
        return definition.getJumpDayNo();
    }

    private int resolveIncreaseDays(ScenarioDefinition definition) {
        if (definition == null || definition.getIncreaseDays() == null || definition.getIncreaseDays() <= 0) {
            return 1;
        }
        return definition.getIncreaseDays();
    }

    private int resolveScenarioNumber(ScenarioDefinition definition) {
        if (definition == null || definition.getScenarioNo() == null || definition.getScenarioNo() <= 0) {
            return 0;
        }
        return definition.getScenarioNo();
    }

    private String resolveCalendarCode(ScenarioDefinition definition) {
        return definition == null ? null : normalize(definition.getHolidayCalendarCode());
    }

    private List<LocalDate> buildBackwardSamples(
            String calendarCode,
            LocalDate anchorDate,
            int sampleCount,
            int increaseDays) {
        if (anchorDate == null || sampleCount <= 0) {
            return Collections.emptyList();
        }
        List<LocalDate> result = new ArrayList<LocalDate>(sampleCount);
        LocalDate currentDate = alignDate(calendarCode, anchorDate, false);
        for (int i = 0; i < sampleCount; i++) {
            result.add(currentDate);
            currentDate = moveBusinessDays(calendarCode, currentDate, -increaseDays);
        }
        return result;
    }

    private List<LocalDate> buildForwardSamples(
            String calendarCode,
            LocalDate anchorDate,
            int sampleCount,
            int increaseDays,
            LocalDate endDate) {
        if (anchorDate == null || sampleCount <= 0) {
            return Collections.emptyList();
        }
        List<LocalDate> result = new ArrayList<LocalDate>();
        LocalDate currentDate = alignDate(calendarCode, anchorDate, true);
        while (result.size() < sampleCount) {
            if (endDate != null && currentDate.isAfter(endDate)) {
                break;
            }
            result.add(currentDate);
            currentDate = moveBusinessDays(calendarCode, currentDate, increaseDays);
        }
        return result;
    }

    private List<LocalDate> buildBusinessSpan(String calendarCode, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            return Collections.emptyList();
        }
        List<LocalDate> result = new ArrayList<LocalDate>();
        LocalDate currentDate = alignDate(calendarCode, startDate, true);
        while (!currentDate.isAfter(endDate)) {
            result.add(currentDate);
            currentDate = moveBusinessDays(calendarCode, currentDate, 1);
        }
        return result;
    }

    private LocalDate alignDate(String calendarCode, LocalDate date, boolean forward) {
        if (date == null) {
            return null;
        }
        if (holidayCalendar == null) {
            return date;
        }
        return holidayCalendar.getBusinessDay(calendarCode, date, forward ? "F" : "P", 0);
    }

    private LocalDate moveBusinessDays(String calendarCode, LocalDate refDate, int offset) {
        if (refDate == null || offset == 0 || holidayCalendar == null) {
            return refDate;
        }
        if (offset > 0) {
            return holidayCalendar.addBusinessDays(calendarCode, refDate, offset);
        }
        return holidayCalendar.getBusinessDay(calendarCode, refDate, "P", -offset);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private void logSkip(ScenarioDefinition definition, String reason) {
        String scenarioId = definition == null ? null : definition.getScenarioId();
        String scenarioType = definition == null ? null : definition.getScenarioType();
        String curveType = definition == null ? null : definition.getCurveType();
        String curveCode = definition == null ? null : definition.getCurveCode();
        System.err.println("情景区间解析跳过: scenarioId="
                + safeValue(scenarioId)
                + ", scenarioType="
                + safeValue(scenarioType)
                + ", curveType="
                + safeValue(curveType)
                + ", curveCode="
                + safeValue(curveCode)
                + ", reason="
                + reason);
    }

    private String safeValue(String value) {
        return value == null || value.trim().isEmpty() ? "UNKNOWN" : value.trim();
    }

    /**
     * 区间解析结果。
     */
    public static class ResolvedRange {
        private final LocalDate dataSearchStartDate;
        private final LocalDate dataSearchEndDate;
        private final LocalDate calculationStartDate;
        private final LocalDate calculationEndDate;
        private final Integer jumpDayNo;
        private final Integer increaseDays;
        private final List<LocalDate> sampleDates;
        private final List<LocalDate> comparisonDates;
        private final List<LocalDate> dataSearchDates;

        public ResolvedRange(
                LocalDate dataSearchStartDate,
                LocalDate dataSearchEndDate,
                LocalDate calculationStartDate,
                LocalDate calculationEndDate,
                Integer jumpDayNo,
                Integer increaseDays,
                List<LocalDate> sampleDates,
                List<LocalDate> comparisonDates,
                List<LocalDate> dataSearchDates) {
            this.dataSearchStartDate = dataSearchStartDate;
            this.dataSearchEndDate = dataSearchEndDate;
            this.calculationStartDate = calculationStartDate;
            this.calculationEndDate = calculationEndDate;
            this.jumpDayNo = jumpDayNo;
            this.increaseDays = increaseDays;
            this.sampleDates = sampleDates == null ? Collections.<LocalDate>emptyList() : Collections.unmodifiableList(new ArrayList<LocalDate>(sampleDates));
            this.comparisonDates = comparisonDates == null ? Collections.<LocalDate>emptyList() : Collections.unmodifiableList(new ArrayList<LocalDate>(comparisonDates));
            this.dataSearchDates = dataSearchDates == null ? Collections.<LocalDate>emptyList() : Collections.unmodifiableList(new ArrayList<LocalDate>(dataSearchDates));
        }

        public LocalDate getDataSearchStartDate() {
            return dataSearchStartDate;
        }

        public LocalDate getDataSearchEndDate() {
            return dataSearchEndDate;
        }

        public LocalDate getCalculationStartDate() {
            return calculationStartDate;
        }

        public LocalDate getCalculationEndDate() {
            return calculationEndDate;
        }

        public Integer getJumpDayNo() {
            return jumpDayNo;
        }

        public Integer getIncreaseDays() {
            return increaseDays;
        }

        public List<LocalDate> getSampleDates() {
            return sampleDates;
        }

        public List<LocalDate> getComparisonDates() {
            return comparisonDates;
        }

        public List<LocalDate> getDataSearchDates() {
            return dataSearchDates;
        }
    }
}
