package com.zcyh.mr.scenario.strategy;

import com.zcyh.mr.math.RandomMatrix;
import com.zcyh.mr.calendar.SystemCalendarCache;
import com.zcyh.mr.scenario.ScenarioRangeResolver;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.scenario.util.ScenarioModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 蒙特卡洛情景策略。
 *
 * <p>
 * 基于历史序列波动生成随机冲击，并应用到当前市场点。
 */
public class McScenarioStrategy implements ScenarioStrategy {
    private static final Logger log = LoggerFactory.getLogger(McScenarioStrategy.class);

    private static final String[] CURVE_TYPES = {
            "IR_SPOT", "CREDIT_SPOT", "FX_SPOT", "COMM_SPOT", "EQ_SPOT", "FX_VOL", "IR_VOL", "COMM_VOL", "EQ_VOL"
    };
    private final com.zcyh.mr.calendar.Calendar holidayCalendar;
    private final ScenarioRangeResolver rangeResolver;

    public McScenarioStrategy(com.zcyh.mr.calendar.Calendar holidayCalendar) {
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
        int scenarioNumber = representativeControl.getScenarioNo() == null ? 0 : representativeControl.getScenarioNo();
        if (scenarioNumber <= 0) {
            return Collections.emptyList();
        }

        ScenarioRangeResolver.ResolvedRange dateRange = rangeResolver.resolve(representativeControl, task.getValuationDate());
        if (dateRange == null) {
            return Collections.emptyList();
        }
        double[][] randomMatrix = RandomMatrix.generateRandomMatrix(1, scenarioNumber);

        List<ScenarioGeneratedRecord> result = new LinkedList<ScenarioGeneratedRecord>();
        for (String curveType : CURVE_TYPES) {
            List<ScenarioDefinition> curveDefinitions = ScenarioModelUtils.getDefinitions(task, curveType);
            List<ScenarioMarketSeries> historicalData = ScenarioModelUtils.getHistoricalMarketSeries(
                    task, curveType, dateRange.getDataSearchStartDate(), dateRange.getDataSearchEndDate());
            List<ScenarioMarketSeries> nowData = ScenarioModelUtils.getCurrentMarketSeries(task, curveType);
            logInactiveCurves(task, curveType, curveDefinitions, nowData);

            if (!nowData.isEmpty() && !historicalData.isEmpty()) {
                result.addAll(generateMcScenarios(
                        representativeControl, dateRange, nowData, historicalData, randomMatrix, scenarioNumber, user));
            }
        }

        result.sort(ScenarioModelUtils.getGeneratedRecordComparator());
        return result;
    }

    /**
     * 解析并校验蒙特卡洛任务的统一控制字段。
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

        for (int i = 1; i < controlPoints.size(); i++) {
            ScenarioDefinition current = controlPoints.get(i);
            if (!safeEquals(baseScenarioType, normalize(current.getScenarioType()))
                    || !safeEquals(baseScenarioNo, normalizePositive(current.getScenarioNo()))
                    || !safeEquals(baseJumpDayNo, normalizePositive(current.getJumpDayNo()))
                    || !safeEquals(baseIncreaseDays, normalizePositive(current.getIncreaseDays()))
                    || !safeEquals(baseStartDate, current.getStartDate())
                    || !safeEquals(baseEndDate, current.getEndDate())
                    || !safeEquals(baseCalendarCode, normalize(current.getHolidayCalendarCode()))) {
                throw new IllegalArgumentException(buildConsistencyError(first, current));
            }
        }
        return first;
    }

    /**
     * 生成蒙特卡洛情景。
     */
    private List<ScenarioGeneratedRecord> generateMcScenarios(
            ScenarioDefinition control,
            ScenarioRangeResolver.ResolvedRange dateRange,
            List<ScenarioMarketSeries> nowData,
            List<ScenarioMarketSeries> historicalData,
            double[][] randomMatrix,
            int scenarioNumber,
            String user) {
        List<ScenarioGeneratedRecord> result = new LinkedList<ScenarioGeneratedRecord>();

        Map<String, List<ScenarioMarketSeries>> groupedHistory = historicalData.stream()
                .collect(Collectors.groupingBy(this::buildUniqueKey, LinkedHashMap::new, Collectors.toList()));
        Map<String, ScenarioMarketSeries> nowDataMap = nowData.stream()
                .collect(Collectors.toMap(this::buildUniqueKey, point -> point, (left, right) -> left, LinkedHashMap::new));

        int subscenarioIndex = 1;
        for (Map.Entry<String, List<ScenarioMarketSeries>> entry : groupedHistory.entrySet()) {
            ScenarioMarketSeries nowPoint = nowDataMap.get(entry.getKey());
            if (nowPoint == null) {
                continue;
            }

            List<ScenarioMarketSeries> history = new ArrayList<ScenarioMarketSeries>(entry.getValue());
            if (history.size() < 2) {
                continue;
            }

            try {
                BigDecimal standardDeviation = calculateStandardDeviation(control, history, dateRange);
                for (int i = 0; i < scenarioNumber; i++) {
                    BigDecimal shiftValue = standardDeviation
                            .multiply(BigDecimal.valueOf(randomMatrix[0][i]))
                            .setScale(5, RoundingMode.HALF_UP);
                    BigDecimal changedValue = nowPoint.getValue() == null
                            ? null
                            : nowPoint.getValue().add(shiftValue);
                    String subScenarioId = control.getScenarioId() + "_" + subscenarioIndex;
                    result.add(ScenarioModelUtils.toGeneratedRecord(
                            nowPoint,
                            control,
                            shiftValue,
                            "ABSOLUTE",
                            changedValue,
                            subScenarioId,
                            user));
                    subscenarioIndex++;
                }
            } catch (Exception ex) {
                String curveCode = nowPoint.getCurveCode() == null ? "UNKNOWN" : nowPoint.getCurveCode();
                String termCode = nowPoint.getTermCode() == null ? "UNKNOWN" : nowPoint.getTermCode();
                throw new IllegalStateException("蒙特卡洛情景数据点处理失败: curveCode="
                        + curveCode + ", termCode=" + termCode, ex);
            }
        }

        return result;
    }

    /**
     * 计算历史标准差。
     */
    private BigDecimal calculateStandardDeviation(
            ScenarioDefinition control,
            List<ScenarioMarketSeries> history,
            ScenarioRangeResolver.ResolvedRange dateRange) {
        history.sort(Comparator.comparing(ScenarioMarketSeries::getDataDate, Comparator.nullsLast(Comparator.naturalOrder())));

        Map<LocalDate, ScenarioMarketSeries> historyIndex = history.stream()
                .collect(Collectors.toMap(
                        ScenarioMarketSeries::getDataDate,
                        point -> point,
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<BigDecimal> changes = new ArrayList<BigDecimal>();
        List<LocalDate> sampleDates = dateRange == null ? Collections.<LocalDate>emptyList() : dateRange.getSampleDates();
        List<LocalDate> comparisonDates = dateRange == null ? Collections.<LocalDate>emptyList() : dateRange.getComparisonDates();
        for (int i = 0; i < sampleDates.size(); i++) {
            LocalDate sampleDate = sampleDates.get(i);
            LocalDate prevDate = i < comparisonDates.size()
                    ? comparisonDates.get(i)
                    : shiftBusinessDate(control, sampleDate, -(dateRange == null || dateRange.getJumpDayNo() == null ? 1 : dateRange.getJumpDayNo()));
            ScenarioMarketSeries currPoint = historyIndex.get(sampleDate);
            ScenarioMarketSeries prevPoint = historyIndex.get(prevDate);
            if (currPoint == null || prevPoint == null || currPoint.getValue() == null || prevPoint.getValue() == null) {
                continue;
            }
            changes.add(currPoint.getValue().subtract(prevPoint.getValue()));
        }

        if (changes.isEmpty()) {
            return BigDecimal.ZERO.setScale(5, RoundingMode.HALF_UP);
        }

        BigDecimal mean = changes.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(changes.size()), 16, RoundingMode.HALF_UP);

        BigDecimal variance = changes.stream()
                .map(change -> change.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(changes.size()), 16, RoundingMode.HALF_UP);

        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(5, RoundingMode.HALF_UP);
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
        return "蒙特卡洛情景定义存在不一致的公共字段，无法按统一日期规则执行: scenarioId="
                + safeValue(first.getScenarioId())
                + ", diffs="
                + diffs;
    }

    private void appendDiff(List<String> diffs, String fieldName, Object left, Object right) {
        if (!safeEquals(left, right)) {
            diffs.add(fieldName + "(" + safeValue(left) + " != " + safeValue(right) + ")");
        }
    }

    private LocalDate shiftBusinessDate(ScenarioDefinition definition, LocalDate refDate, int offset) {
        if (refDate == null || offset == 0) {
            return refDate;
        }
        String calendarCode = definition == null ? null : definition.getHolidayCalendarCode();
        if (offset > 0) {
            return holidayCalendar.addBusinessDays(calendarCode, refDate, offset);
        }
        return holidayCalendar.getBusinessDay(calendarCode, refDate, "P", -offset);
    }

    private String buildUniqueKey(ScenarioMarketSeries point) {
        StringBuilder builder = new StringBuilder();
        builder.append(point.getCurveCode() == null ? "" : point.getCurveCode().trim());
        if (point.getDimension2() != null && !point.getDimension2().trim().isEmpty()) {
            builder.append("@@").append(point.getDimension2().trim());
        }
        int termDays = ScenarioModelUtils.resolveTermDays(point);
        if (termDays > 0 || point.getTermCode() != null) {
            builder.append("@@").append(termDays > 0 ? termDays : point.getTermCode().trim());
        }
        return builder.toString();
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

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer normalizePositive(Integer value) {
        return value == null || value <= 0 ? Integer.valueOf(1) : value;
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
}
