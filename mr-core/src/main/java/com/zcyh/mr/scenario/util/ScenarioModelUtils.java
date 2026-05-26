package com.zcyh.mr.scenario.util;

import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 情景模型工具。
 */
public final class ScenarioModelUtils {

    private ScenarioModelUtils() {
    }

    public static List<ScenarioDefinition> getDefinitions(ScenarioTaskRequest task) {
        return getDefinitions(task, null);
    }

    public static List<ScenarioDefinition> getDefinitions(ScenarioTaskRequest task, String curveType) {
        List<ScenarioDefinition> result = new ArrayList<ScenarioDefinition>();
        if (task == null || task.getDefinitions() == null) {
            return result;
        }
        for (ScenarioDefinition definition : task.getDefinitions()) {
            if (!matchesCurveType(curveType, definition.getCurveType())) {
                continue;
            }
            result.add(definition);
        }
        result.sort(Comparator.comparingInt(ScenarioModelUtils::resolveTermDays));
        return result;
    }

    public static List<ScenarioMarketSeries> getHistoricalMarketSeries(
            ScenarioTaskRequest task,
            String curveType,
            LocalDate startDate,
            LocalDate endDate) {
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        if (task == null || task.getHistoricalMarketData() == null) {
            return result;
        }
        Map<LocalDate, List<ScenarioMarketSeries>> byDate = task.getHistoricalMarketData().get(curveType);
        if (byDate == null || byDate.isEmpty()) {
            return result;
        }
        for (Map.Entry<LocalDate, List<ScenarioMarketSeries>> entry : byDate.entrySet()) {
            LocalDate dataDate = entry.getKey();
            if (dataDate == null) {
                continue;
            }
            if (startDate != null && dataDate.isBefore(startDate)) {
                continue;
            }
            if (endDate != null && dataDate.isAfter(endDate)) {
                continue;
            }
            for (ScenarioMarketSeries series : entry.getValue()) {
                result.add(copySeries(series, dataDate));
            }
        }
        result.sort(Comparator
                .comparing(ScenarioMarketSeries::getDataDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(getMarketSeriesComparator()));
        return result;
    }

    public static List<ScenarioMarketSeries> getCurrentMarketSeries(ScenarioTaskRequest task, String curveType) {
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        if (task == null || task.getCurrentMarketData() == null) {
            return result;
        }
        List<ScenarioMarketSeries> currentList = task.getCurrentMarketData().get(curveType);
        if (currentList == null) {
            return result;
        }
        for (ScenarioMarketSeries series : currentList) {
            result.add(copySeries(series, task.getValuationDate()));
        }
        result.sort(getMarketSeriesComparator());
        return result;
    }

    public static ScenarioMarketSeries copySeries(ScenarioMarketSeries series, LocalDate dataDate) {
        ScenarioMarketSeries copied = new ScenarioMarketSeries();
        copied.setCurveType(normalize(series.getCurveType()));
        copied.setCurveCode(series.getCurveCode());
        copied.setDataDate(dataDate);
        copied.setTermCode(firstNonBlank(series.getTermCode(), series.getTermDays() == null ? null : series.getTermDays() + "D"));
        copied.setTermDays(resolveTermDays(series.getTermDays(), series.getTermCode()));
        copied.setDimension2(normalize(series.getDimension2()));
        copied.setValue(series.getValue());
        return copied;
    }

    public static Set<java.util.Date> toHolidaySet(Calendar holidayCalendar, ScenarioDefinition definition) {
        if (holidayCalendar == null || definition == null) {
            return Collections.emptySet();
        }
        String calendarCode = normalize(definition.getHolidayCalendarCode());
        // calendarCode 为空时，按“无节假日定义”处理，即所有自然日都视为工作日。
        if (calendarCode == null || calendarCode.isEmpty()) {
            return Collections.emptySet();
        }
        Set<LocalDate> holidays = holidayCalendar.getHolidays(calendarCode);
        // 查不到对应日历时，同样按“无节假日定义”处理，即所有自然日都视为工作日。
        if (holidays == null || holidays.isEmpty()) {
            return Collections.emptySet();
        }
        Set<java.util.Date> result = new LinkedHashSet<java.util.Date>();
        for (LocalDate holidayDate : holidays) {
            if (holidayDate != null) {
                result.add(Date.valueOf(holidayDate));
            }
        }
        return result;
    }

    public static ScenarioGeneratedRecord toGeneratedRecord(
            ScenarioMarketSeries series,
            ScenarioDefinition definition,
            BigDecimal shiftValue,
            String shiftRule,
            BigDecimal changedValue,
            String subScenarioId,
            String modifier) {
        ScenarioGeneratedRecord record = new ScenarioGeneratedRecord();
        record.setScenarioId(definition == null ? null : definition.getScenarioId());
        record.setSubScenarioId(subScenarioId);
        record.setScenarioName(definition == null ? null : definition.getScenarioName());
        record.setScenarioType(definition == null ? null : definition.getScenarioType());
        record.setRiskGroupId(definition == null ? null : definition.getRiskGroupId());
        record.setCurveType(series == null ? null : series.getCurveType());
        record.setCurveCode(series == null ? null : series.getCurveCode());
        record.setDataDate(series == null ? null : series.getDataDate());
        record.setTermCode(resolveOutputTermCode(series));
        record.setTermDays(series == null ? null : series.getTermDays());
        record.setDimension2(series == null ? null : series.getDimension2());
        record.setOriginalValue(series == null ? null : series.getValue());
        record.setChangedValue(changedValue);
        record.setShiftValue(shiftValue);
        record.setShiftRule(shiftRule);
        record.setModifier(modifier);
        return record;
    }

    public static Comparator<ScenarioGeneratedRecord> getGeneratedRecordComparator() {
        return Comparator
                .comparing((ScenarioGeneratedRecord record) -> safeString(record.getCurveCode()))
                .thenComparing(record -> safeString(record.getDimension2()))
                .thenComparingInt(record -> record.getTermDays() == null ? resolveTermDays(null, record.getTermCode()) : record.getTermDays())
                .thenComparing(record -> safeString(record.getSubScenarioId()));
    }

    public static Comparator<ScenarioMarketSeries> getMarketSeriesComparator() {
        return Comparator
                .comparing((ScenarioMarketSeries series) -> safeString(series.getCurveCode()))
                .thenComparing(series -> safeString(series.getDimension2()))
                .thenComparingInt(series -> series.getTermDays() == null ? resolveTermDays(null, series.getTermCode()) : series.getTermDays());
    }

    public static int resolveTermDays(ScenarioDefinition definition) {
        if (definition == null) {
            return 0;
        }
        if (isCustomLikeDefinition(definition)) {
            Integer termDays = definition.getTermDays();
            return termDays != null && termDays >= 0 ? termDays : 0;
        }
        return resolveTermDays(definition.getTermDays(), definition.getTermCode());
    }

    public static int resolveTermDays(ScenarioMarketSeries series) {
        return series == null ? 0 : resolveTermDays(series.getTermDays(), series.getTermCode());
    }

    public static int resolveTermDays(Integer termDays, String termCode) {
        if (termDays != null && termDays > 0) {
            return termDays;
        }
        if (termCode == null || termCode.trim().isEmpty()) {
            return 0;
        }
        return ShockUtils.termCodeToInt(termCode);
    }

    public static String buildDimension2Suffix(ScenarioMarketSeries series) {
        String axis2 = series == null ? null : normalize(series.getDimension2());
        return axis2 == null || axis2.isEmpty() ? "" : "_" + axis2;
    }

    private static String resolveOutputTermCode(ScenarioMarketSeries series) {
        if (series == null) {
            return null;
        }
        String termCode = series.getTermCode();
        if (termCode == null || termCode.trim().isEmpty()) {
            Integer termDays = series.getTermDays();
            termCode = termDays == null ? null : termDays + "D";
        }
        String suffix = buildDimension2Suffix(series);
        if (!suffix.isEmpty() && termCode != null && !termCode.endsWith(suffix)) {
            return termCode + suffix;
        }
        return termCode;
    }

    private static boolean matchesCurveType(String targetCurveType, String actualCurveType) {
        String normalizedTarget = normalize(targetCurveType);
        String normalizedActual = normalize(actualCurveType);
        if (normalizedTarget == null || normalizedTarget.isEmpty()) {
            return true;
        }
        return normalizedTarget.equals(normalizedActual);
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = normalize(first);
        return normalizedFirst == null || normalizedFirst.isEmpty() ? normalize(second) : normalizedFirst;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static boolean isCustomLikeDefinition(ScenarioDefinition definition) {
        String scenarioType = normalize(definition.getScenarioType());
        return "CUSTOM".equals(scenarioType) || "KEY_RATE".equals(scenarioType);
    }
}
