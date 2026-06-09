package com.zcyh.mr.scenario.strategy;

import com.zcyh.mr.core.SystemCalendarCache;
import com.zcyh.mr.scenario.ScenarioRangeResolver;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.scenario.processor.HistoryDataCompleter;
import com.zcyh.mr.scenario.util.ShockUtils;
import com.zcyh.mr.scenario.util.ScenarioModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 历史类情景策略。
 *
 * <p>
 * 处理 HISTORY、VAR、BACKTEST、SVAR 四类历史情景。
 */
public class HistoricalScenarioStrategy implements ScenarioStrategy {
    private static final Logger log = LoggerFactory.getLogger(HistoricalScenarioStrategy.class);
    private static final DateTimeFormatter SUB_SCENARIO_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String[] CURVE_TYPES = {
            "IR_SPOT", "FX_SPOT", "COMM_SPOT", "EQ_SPOT", "FX_VOL", "IR_VOL", "COMM_VOL", "EQ_VOL"
    };

    private static final int BATCH_SIZE = 40;

    private final HistoryDataCompleter dataCompleter;
    private final com.zcyh.mr.core.Calendar holidayCalendar;
    private final ScenarioRangeResolver rangeResolver;

    public HistoricalScenarioStrategy(com.zcyh.mr.core.Calendar holidayCalendar) {
        this.dataCompleter = new HistoryDataCompleter();
        this.holidayCalendar = SystemCalendarCache.resolve(holidayCalendar);
        this.rangeResolver = new ScenarioRangeResolver(this.holidayCalendar);
    }

    @Override
    public List<ScenarioGeneratedRecord> generate(
            ScenarioTaskRequest task,
            String user) {
        List<ScenarioDefinition> controlPoints = ScenarioModelUtils.getDefinitions(task);
        if (controlPoints.isEmpty()) {
            return Collections.emptyList();
        }

        ScenarioDefinition representativeControl = resolveRepresentativeControl(controlPoints);
        ScenarioRangeResolver.ResolvedRange dateRange = rangeResolver.resolve(representativeControl, task.getValuationDate());
        if (dateRange == null) {
            return Collections.emptyList();
        }

        List<ScenarioGeneratedRecord> result = Arrays.stream(CURVE_TYPES)
                .map(curveType -> generateByCurveType(task, representativeControl, curveType, dateRange, user))
                .flatMap(List::stream)
                .sorted(ScenarioModelUtils.getGeneratedRecordComparator())
                .collect(Collectors.toList());

        return result;
    }

    private List<ScenarioGeneratedRecord> generateByCurveType(
            ScenarioTaskRequest task,
            ScenarioDefinition representativeControl,
            String curveType,
            ScenarioRangeResolver.ResolvedRange dateRange,
            String user) {
        List<ScenarioDefinition> curveDefinitions = ScenarioModelUtils.getDefinitions(task, curveType);
        List<ScenarioMarketSeries> historicalData = ScenarioModelUtils.getHistoricalMarketSeries(
                task, curveType, dateRange.getDataSearchStartDate(), dateRange.getDataSearchEndDate());
        List<ScenarioMarketSeries> nowData = ScenarioModelUtils.getCurrentMarketSeries(task, curveType);
        logInactiveCurves(task, curveType, curveDefinitions, nowData);

        if (nowData.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<ScenarioMarketSeries>> dataByCurve = nowData.stream()
                .collect(Collectors.groupingBy(ShockUtils::getUnique));
        Map<String, List<ScenarioMarketSeries>> historicalDataByCurve = historicalData.stream()
                .collect(Collectors.groupingBy(ShockUtils::getUnique));
        List<String> allCurveKeys = new ArrayList<String>(dataByCurve.keySet());
        int curveCount = allCurveKeys.size();
        List<ScenarioGeneratedRecord> typeResult = new ArrayList<ScenarioGeneratedRecord>();

        if (curveCount > BATCH_SIZE) {
            List<List<String>> batches = new ArrayList<List<String>>();
            for (int i = 0; i < curveCount; i += BATCH_SIZE) {
                batches.add(allCurveKeys.subList(i, Math.min(i + BATCH_SIZE, curveCount)));
            }

            for (List<String> batchKeys : batches) {
                typeResult.addAll(processHistoryBatch(
                        batchKeys,
                        dataByCurve,
                        historicalDataByCurve,
                        representativeControl,
                        curveDefinitions,
                        dateRange,
                        user));
            }
        } else {
            List<ScenarioMarketSeries> completedData = dataCompleter.complete(
                    nowData,
                    historicalData,
                    dateRange.getDataSearchDates());
            typeResult.addAll(generateHistoryScenarios(representativeControl, curveDefinitions, dateRange, completedData, nowData, user));
        }
        return typeResult;
    }

    private List<ScenarioGeneratedRecord> processHistoryBatch(
            List<String> batchKeys,
            Map<String, List<ScenarioMarketSeries>> dataByCurve,
            Map<String, List<ScenarioMarketSeries>> historicalDataByCurve,
            ScenarioDefinition representativeControl,
            List<ScenarioDefinition> curveDefinitions,
            ScenarioRangeResolver.ResolvedRange dateRange,
            String user) {
        try {
            List<ScenarioMarketSeries> batchNowData = new ArrayList<ScenarioMarketSeries>();
            for (String key : batchKeys) {
                batchNowData.addAll(dataByCurve.get(key));
            }

            List<ScenarioMarketSeries> batchHistoryData = new ArrayList<ScenarioMarketSeries>();
            for (String key : batchKeys) {
                List<ScenarioMarketSeries> seriesList = historicalDataByCurve.get(key);
                if (seriesList != null && !seriesList.isEmpty()) {
                    batchHistoryData.addAll(seriesList);
                }
            }

            List<ScenarioMarketSeries> batchCompletedData = dataCompleter.complete(
                    batchNowData,
                    batchHistoryData,
                    dateRange.getDataSearchDates());

            return generateHistoryScenarios(
                    representativeControl,
                    curveDefinitions,
                    dateRange,
                    batchCompletedData,
                    batchNowData,
                    user);
        } catch (Exception ex) {
            throw new IllegalStateException("历史情景批次处理失败: batchKeys=" + batchKeys, ex);
        }
    }

    /**
     * 生成历史情景。
     */
    private List<ScenarioGeneratedRecord> generateHistoryScenarios(
            ScenarioDefinition representativeControl,
            List<ScenarioDefinition> curveDefinitions,
            ScenarioRangeResolver.ResolvedRange dateRange,
            List<ScenarioMarketSeries> completedData,
            List<ScenarioMarketSeries> nowData,
            String user) {
        if (dateRange == null || dateRange.getSampleDates().isEmpty()) {
            return Collections.emptyList();
        }
        int jumpDayNo = dateRange.getJumpDayNo() == null ? 1 : dateRange.getJumpDayNo();
        List<LocalDate> sampleDates = dateRange.getSampleDates();
        int expectedRecordCount = estimateRecordCount(sampleDates, nowData);
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>(expectedRecordCount);

        Map<LocalDate, Map<String, ScenarioMarketSeries>> historyDataIndex = completedData.stream()
                .collect(Collectors.groupingBy(
                        ScenarioMarketSeries::getDataDate,
                        Collectors.toMap(ShockUtils::getUniqueCode, point -> point, (left, right) -> left)));

        Map<String, ScenarioMarketSeries> nowDataMap = nowData.stream()
                .collect(Collectors.toMap(ShockUtils::getUniqueCode, point -> point, (left, right) -> left));
        Map<String, PointScenarioContext> pointScenarioContextMap = buildPointScenarioContextMap(
                nowDataMap,
                curveDefinitions,
                representativeControl);

        int subscenarioIndex = 1;
        List<LocalDate> comparisonDates = dateRange.getComparisonDates();
        for (int sampleIndex = 0; sampleIndex < sampleDates.size(); sampleIndex++) {
            LocalDate tDate = sampleDates.get(sampleIndex);
            LocalDate prevDate = sampleIndex < comparisonDates.size()
                    ? comparisonDates.get(sampleIndex)
                    : shiftBusinessDate(representativeControl, tDate, -jumpDayNo);

            Map<String, ScenarioMarketSeries> tDataMap = historyDataIndex.get(tDate);
            Map<String, ScenarioMarketSeries> prevDataMap = historyDataIndex.get(prevDate);
            if (tDataMap == null || prevDataMap == null) {
                continue;
            }
            String subScenarioId = buildSubScenarioId(prevDate, tDate, subscenarioIndex - 1);

            for (Map.Entry<String, ScenarioMarketSeries> entry : nowDataMap.entrySet()) {
                ScenarioMarketSeries nowPoint = entry.getValue();
                try {
                    PointScenarioContext pointScenarioContext = pointScenarioContextMap.get(entry.getKey());
                    ScenarioDefinition matchedDefinition = pointScenarioContext == null
                            ? representativeControl
                            : pointScenarioContext.getMatchedDefinition();
                    String scenarioShiftRule = pointScenarioContext == null
                            ? resolveScenarioShiftRule(representativeControl, representativeControl)
                            : pointScenarioContext.getScenarioShiftRule();
                    ScenarioMarketSeries tPoint = tDataMap.get(entry.getKey());
                    ScenarioMarketSeries prevPoint = prevDataMap.get(entry.getKey());
                    if (tPoint == null || prevPoint == null) {
                        continue;
                    }

                    BigDecimal shockValue = ShockUtils.calculateShock(tPoint.getValue(), prevPoint.getValue(), scenarioShiftRule);
                    BigDecimal changedValue;
                    if ("RELATIVE".equalsIgnoreCase(scenarioShiftRule)) {
                        changedValue = nowPoint.getValue().multiply(shockValue.add(BigDecimal.ONE));
                    } else {
                        changedValue = nowPoint.getValue().add(shockValue);
                    }

                    result.add(ScenarioModelUtils.toGeneratedRecord(
                            nowPoint,
                            matchedDefinition,
                            shockValue,
                            scenarioShiftRule,
                            changedValue,
                            subScenarioId,
                            user));
                } catch (Exception ex) {
                    String curveCode = nowPoint.getCurveCode() == null ? "UNKNOWN" : nowPoint.getCurveCode();
                    String termCode = nowPoint.getTermCode() == null ? "UNKNOWN" : nowPoint.getTermCode();
                    throw new IllegalStateException("历史情景数据点处理失败: curveCode="
                            + curveCode + ", termCode=" + termCode + ", subScenarioId=" + subScenarioId, ex);
                }
            }
            subscenarioIndex++;
        }

        return result;
    }

    /**
     * 解析并校验历史类任务的统一控制字段。
     */
    private ScenarioDefinition resolveRepresentativeControl(List<ScenarioDefinition> controlPoints) {
        ScenarioDefinition first = controlPoints.get(0);
        String baseScenarioType = normalize(first.getScenarioType());
        Integer baseScenarioNo = normalizePositive(first.getScenarioNo());
        Integer baseJumpDayNo = normalizePositive(first.getJumpDayNo());
        Integer baseIncreaseDays = normalizePositive(first.getIncreaseDays());
        LocalDate baseStartDate = first.getStartDate();
        LocalDate baseEndDate = first.getEndDate();
        String baseCalendarCode = normalize(first.getHolidayCalendarCode());
        Boolean baseReducedSetFlag = normalizeBoolean(first.getReducedSetFlag());

        for (int i = 1; i < controlPoints.size(); i++) {
            ScenarioDefinition current = controlPoints.get(i);
            if (!safeEquals(baseScenarioType, normalize(current.getScenarioType()))
                    || !safeEquals(baseScenarioNo, normalizePositive(current.getScenarioNo()))
                    || !safeEquals(baseJumpDayNo, normalizePositive(current.getJumpDayNo()))
                    || !safeEquals(baseIncreaseDays, normalizePositive(current.getIncreaseDays()))
                    || !safeEquals(baseStartDate, current.getStartDate())
                    || !safeEquals(baseEndDate, current.getEndDate())
                    || !safeEquals(baseCalendarCode, normalize(current.getHolidayCalendarCode()))
                    || !safeEquals(baseReducedSetFlag, normalizeBoolean(current.getReducedSetFlag()))) {
                throw new IllegalArgumentException(buildConsistencyError(first, current));
            }
        }
        return first;
    }

    private String buildConsistencyError(ScenarioDefinition first, ScenarioDefinition current) {
        List<String> diffs = new ArrayList<String>();
        appendDiff(diffs, "scenarioType", normalize(first.getScenarioType()), normalize(current.getScenarioType()));
        appendDiff(diffs, "scenarioNo", normalizePositive(first.getScenarioNo()), normalizePositive(current.getScenarioNo()));
        appendDiff(diffs, "jumpDayNo", normalizePositive(first.getJumpDayNo()), normalizePositive(current.getJumpDayNo()));
        appendDiff(diffs, "increaseDays", normalizePositive(first.getIncreaseDays()), normalizePositive(current.getIncreaseDays()));
        appendDiff(diffs, "startDate", first.getStartDate(), current.getStartDate());
        appendDiff(diffs, "endDate", first.getEndDate(), current.getEndDate());
        appendDiff(diffs, "holidayCalendarCode", normalize(first.getHolidayCalendarCode()), normalize(current.getHolidayCalendarCode()));
        appendDiff(diffs, "reducedSetFlag", normalizeBoolean(first.getReducedSetFlag()), normalizeBoolean(current.getReducedSetFlag()));
        return "历史类情景定义存在不一致的公共字段，无法按统一日期规则执行: scenarioId="
                + safeValue(first.getScenarioId())
                + ", diffs="
                + diffs;
    }

    private void appendDiff(List<String> diffs, String fieldName, Object left, Object right) {
        if (!safeEquals(left, right)) {
            diffs.add(fieldName + "(" + safeValue(left) + " != " + safeValue(right) + ")");
        }
    }

    private ScenarioDefinition resolveDefinitionForPoint(
            List<ScenarioDefinition> curveDefinitions,
            ScenarioDefinition representativeControl,
            ScenarioMarketSeries point) {
        if (point == null || curveDefinitions == null || curveDefinitions.isEmpty()) {
            return representativeControl;
        }
        ScenarioDefinition exact = null;
        ScenarioDefinition curveOnly = null;
        for (ScenarioDefinition definition : curveDefinitions) {
            if (!safeEquals(normalize(definition.getCurveCode()), normalize(point.getCurveCode()))) {
                continue;
            }
            if (curveOnly == null) {
                curveOnly = definition;
            }
            if (matchTerm(definition, point)) {
                exact = definition;
                break;
            }
        }
        return exact != null ? exact : (curveOnly != null ? curveOnly : representativeControl);
    }

    private Map<String, PointScenarioContext> buildPointScenarioContextMap(
            Map<String, ScenarioMarketSeries> nowDataMap,
            List<ScenarioDefinition> curveDefinitions,
            ScenarioDefinition representativeControl) {
        Map<String, PointScenarioContext> result = new HashMap<String, PointScenarioContext>();
        for (Map.Entry<String, ScenarioMarketSeries> entry : nowDataMap.entrySet()) {
            ScenarioDefinition matchedDefinition = resolveDefinitionForPoint(
                    curveDefinitions,
                    representativeControl,
                    entry.getValue());
            result.put(
                    entry.getKey(),
                    new PointScenarioContext(
                            matchedDefinition,
                            resolveScenarioShiftRule(matchedDefinition, representativeControl)));
        }
        return result;
    }

    private String buildSubScenarioId(LocalDate prevDate, LocalDate tDate, int subscenarioIndex) {
        return prevDate.format(SUB_SCENARIO_DATE_FORMATTER)
                + "_"
                + tDate.format(SUB_SCENARIO_DATE_FORMATTER)
                + "_"
                + subscenarioIndex;
    }

    private int estimateRecordCount(List<LocalDate> sampleDates, List<ScenarioMarketSeries> nowData) {
        int scenarioCount = sampleDates == null ? 0 : sampleDates.size();
        int pointCount = nowData == null ? 0 : nowData.size();
        long estimated = (long) scenarioCount * pointCount;
        if (estimated <= 0L) {
            return 16;
        }
        if (estimated >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) estimated;
    }

    private boolean matchTerm(ScenarioDefinition definition, ScenarioMarketSeries point) {
        int definitionTermDays = ScenarioModelUtils.resolveTermDays(definition);
        int pointTermDays = ScenarioModelUtils.resolveTermDays(point);
        if (definitionTermDays > 0 && pointTermDays > 0) {
            return definitionTermDays == pointTermDays;
        }
        return safeEquals(normalize(definition.getTermCode()), normalize(point.getTermCode()));
    }

    private LocalDate shiftBusinessDate(ScenarioDefinition definition, LocalDate refDate, int offset) {
        if (refDate == null || offset == 0) {
            return refDate;
        }
        String calendarCode = firstNonBlank(
                definition == null ? null : definition.getHolidayCalendarCode());
        if (offset > 0) {
            return holidayCalendar.addBusinessDays(calendarCode, refDate, offset);
        }
        return holidayCalendar.getBusinessDay(calendarCode, refDate, "P", -offset);
    }

    private String firstNonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalize(String value) {
        return firstNonBlank(value);
    }

    private String normalizeScenarioShiftRule(String value) {
        String normalized = normalize(value);
        if ("RELATIVE".equalsIgnoreCase(normalized)) {
            return "RELATIVE";
        }
        if ("ABSOLUTE".equalsIgnoreCase(normalized)) {
            return "ABSOLUTE";
        }
        return null;
    }

    private String resolveScenarioShiftRule(ScenarioDefinition matchedDefinition, ScenarioDefinition representativeControl) {
        String explicitRule = normalizeScenarioShiftRule(matchedDefinition == null ? null : matchedDefinition.getScenarioShiftRule());
        if (explicitRule != null) {
            return explicitRule;
        }
        explicitRule = normalizeScenarioShiftRule(representativeControl == null ? null : representativeControl.getScenarioShiftRule());
        if (explicitRule != null) {
            return explicitRule;
        }

        String scenarioType = normalize(matchedDefinition == null ? null : matchedDefinition.getScenarioType());
        if (scenarioType == null) {
            scenarioType = normalize(representativeControl == null ? null : representativeControl.getScenarioType());
        }
        if ("HISTORY".equals(scenarioType)
                || "VAR".equals(scenarioType)
                || "SVAR".equals(scenarioType)
                || "BACKTEST".equals(scenarioType)
                || "IMA_NORMAL".equals(scenarioType)
                || "IMA_STRESS".equals(scenarioType)
                || "IMA_NMRF".equals(scenarioType)) {
            return "RELATIVE";
        }
        return "ABSOLUTE";
    }

    private Integer normalizePositive(Integer value) {
        return value == null || value <= 0 ? Integer.valueOf(1) : value;
    }

    private Boolean normalizeBoolean(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private String safeValue(String value) {
        return value == null ? "NULL" : value;
    }

    private String safeValue(Object value) {
        return value == null ? "NULL" : String.valueOf(value);
    }

    /**
     * 记录估值日缺失的曲线编码。
     */
    private void logInactiveCurves(
            ScenarioTaskRequest task,
            String curveType,
            List<ScenarioDefinition> definitions,
            List<ScenarioMarketSeries> nowData) {
        Set<String> expectedCurveCodes = definitions.stream()
                .map(ScenarioDefinition::getCurveCode)
                .filter(code -> code != null && !code.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (expectedCurveCodes.isEmpty()) {
            return;
        }
        Set<String> activeCurveCodes = nowData == null
                ? Collections.<String>emptySet()
                : nowData.stream()
                        .map(ScenarioMarketSeries::getCurveCode)
                        .filter(code -> code != null && !code.trim().isEmpty())
                        .map(String::trim)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String curveCode : expectedCurveCodes) {
            if (!activeCurveCodes.contains(curveCode)) {
                log.warn("情景曲线不生效: scenarioId={}, scenarioType={}, curveType={}, curveCode={}, reason=估值日当前市场缺失，按不生效曲线处理",
                        task.getScenarioId(), task.getScenarioType(), curveType, curveCode);
            }
        }
    }

    private static final class PointScenarioContext {
        private final ScenarioDefinition matchedDefinition;
        private final String scenarioShiftRule;

        private PointScenarioContext(ScenarioDefinition matchedDefinition, String scenarioShiftRule) {
            this.matchedDefinition = matchedDefinition;
            this.scenarioShiftRule = scenarioShiftRule;
        }

        private ScenarioDefinition getMatchedDefinition() {
            return matchedDefinition;
        }

        private String getScenarioShiftRule() {
            return scenarioShiftRule;
        }
    }
}
