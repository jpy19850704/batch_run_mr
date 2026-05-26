package com.zcyh.mr.scenario.strategy;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioShift;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.scenario.processor.LinearInterpolator;
import com.zcyh.mr.scenario.riskfactor.RiskFactorProcessor;
import com.zcyh.mr.scenario.util.ScenarioModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 自定义情景策略。
 */
public class CustomScenarioStrategy implements ScenarioStrategy {
    private static final Logger log = LoggerFactory.getLogger(CustomScenarioStrategy.class);

    private static final String[] CURVE_TYPES = {
            "IR_SPOT", "FX_SPOT", "COMM_SPOT", "EQ_SPOT", "FX_VOL", "IR_VOL", "COMM_VOL", "EQ_VOL"
    };

    @Override
    public List<ScenarioGeneratedRecord> generate(
            ScenarioTaskRequest task,
            String user) {
        if ("KEY_RATE".equals(task.getScenarioType())) {
            return generateKeyRate(task, user);
        }
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
        for (String curveType : CURVE_TYPES) {
            List<ScenarioDefinition> controlPoints = ScenarioModelUtils.getDefinitions(task, curveType);
            if (controlPoints.isEmpty()) {
                continue;
            }
            List<ScenarioMarketSeries> marketPoints = ScenarioModelUtils.getCurrentMarketSeries(task, curveType);
            logInactiveCurves(task, curveType, controlPoints, marketPoints);
            if (marketPoints.isEmpty()) {
                continue;
            }
            RiskFactorProcessor processor = RiskFactorProcessor.getProcessor(curveType);
            Map<String, List<ScenarioMarketSeries>> dataByCurve = marketPoints.stream()
                    .collect(Collectors.groupingBy(ScenarioMarketSeries::getCurveCode));

            for (Map.Entry<String, List<ScenarioMarketSeries>> entry : dataByCurve.entrySet()) {
                result.addAll(processCurve(entry.getKey(), entry.getValue(), controlPoints, processor, user));
            }
        }
        result.sort(ScenarioModelUtils.getGeneratedRecordComparator());
        return result;
    }

    /**
     * 生成关键期限情景。
     */
    private List<ScenarioGeneratedRecord> generateKeyRate(
            ScenarioTaskRequest task,
            String user) {
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
        for (String curveType : CURVE_TYPES) {
            List<ScenarioMarketSeries> marketPoints = ScenarioModelUtils.getCurrentMarketSeries(task, curveType);
            List<ScenarioDefinition> controlPoints = ScenarioModelUtils.getDefinitions(task, curveType).stream()
                    .sorted(Comparator.comparingInt(ScenarioModelUtils::resolveTermDays))
                    .collect(Collectors.toList());
            if (controlPoints.isEmpty()) {
                continue;
            }
            logInactiveCurves(task, curveType, controlPoints, marketPoints);
            if (marketPoints.isEmpty()) {
                continue;
            }
            result.addAll(generateKeyRateScenarios(marketPoints, controlPoints, user));
        }
        result.sort(ScenarioModelUtils.getGeneratedRecordComparator());
        return result;
    }

    /**
     * 处理单条曲线。
     */
    private List<ScenarioGeneratedRecord> processCurve(
            String curveCode,
            List<ScenarioMarketSeries> curveData,
            List<ScenarioDefinition> controlPoints,
            RiskFactorProcessor processor,
            String user) {
        try {
            List<ScenarioDefinition> curveControl = controlPoints.stream()
                    .filter(point -> curveCode.equals(point.getCurveCode()))
                    .sorted(Comparator.comparingInt(ScenarioModelUtils::resolveTermDays))
                    .collect(Collectors.toList());
            if (curveControl.isEmpty()) {
                return Collections.emptyList();
            }
            if (processor.needsTermInterpolation()) {
                return processWithInterpolation(curveData, curveControl, processor, user);
            }
            return processWithoutInterpolation(curveData, curveControl, processor, user);
        } catch (Exception ex) {
            throw new IllegalStateException("自定义情景曲线处理失败: curveCode="
                    + curveCode + ", riskFactorType=" + processor.getRiskFactorType(), ex);
        }
    }

    /**
     * 处理需要插值的曲线。
     */
    private List<ScenarioGeneratedRecord> processWithInterpolation(
            List<ScenarioMarketSeries> curveData,
            List<ScenarioDefinition> curveControl,
            RiskFactorProcessor processor,
            String user) {
        LinearInterpolator interpolator = new LinearInterpolator();
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
        ScenarioDefinition metaControl = curveControl.get(0);
        logInvalidTermDays(metaControl, curveControl);

        for (ScenarioMarketSeries datum : curveData) {
            int termDays = ScenarioModelUtils.resolveTermDays(datum);
            if (termDays < 0) {
                continue;
            }
            ScenarioShift shift = interpolator.getShiftValueByControlPoints(termDays, curveControl);
            BigDecimal shiftValue = shift.getShiftValue() == null
                    ? BigDecimal.ZERO
                    : shift.getShiftValue().setScale(5, RoundingMode.HALF_UP);
            String shiftRule = shift.getShiftRule() == null ? "ABSOLUTE" : shift.getShiftRule();
            BigDecimal changedRate = processor.applyShift(datum.getValue(), shiftValue, shiftRule);
            result.add(ScenarioModelUtils.toGeneratedRecord(
                    datum,
                    metaControl,
                    shiftValue,
                    shiftRule,
                    changedRate,
                    resolveCustomSubScenarioId(metaControl),
                    user));
        }
        return result;
    }

    /**
     * 处理不需要插值的曲线。
     */
    private List<ScenarioGeneratedRecord> processWithoutInterpolation(
            List<ScenarioMarketSeries> curveData,
            List<ScenarioDefinition> curveControl,
            RiskFactorProcessor processor,
            String user) {
        ScenarioDefinition metaControl = curveControl.get(0);
        BigDecimal shiftValue = metaControl.getShockValue() == null ? BigDecimal.ZERO : metaControl.getShockValue();
        String shiftRule = metaControl.getScenarioShiftRule() == null ? "ABSOLUTE" : metaControl.getScenarioShiftRule();
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
        for (ScenarioMarketSeries datum : curveData) {
            BigDecimal changedRate = processor.applyShift(datum.getValue(), shiftValue, shiftRule);
            result.add(ScenarioModelUtils.toGeneratedRecord(
                    datum,
                    metaControl,
                    shiftValue,
                    shiftRule,
                    changedRate,
                    resolveCustomSubScenarioId(metaControl),
                    user));
        }
        return result;
    }

    /**
     * 生成关键期限情景结果。
     */
    private List<ScenarioGeneratedRecord> generateKeyRateScenarios(
            List<ScenarioMarketSeries> marketPoints,
            List<ScenarioDefinition> controlPoints,
            String user) {
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
        Map<String, List<ScenarioMarketSeries>> dataByCode = marketPoints.stream()
                .collect(Collectors.groupingBy(ScenarioMarketSeries::getCurveCode));
        Map<String, List<ScenarioDefinition>> controlByCode = controlPoints.stream()
                .collect(Collectors.groupingBy(ScenarioDefinition::getCurveCode));

        for (Map.Entry<String, List<ScenarioMarketSeries>> entry : dataByCode.entrySet()) {
            String curveCode = entry.getKey();
            List<ScenarioDefinition> curveControl = controlByCode.get(curveCode);
            if (curveControl == null || curveControl.isEmpty()) {
                continue;
            }

            for (ScenarioDefinition controlPoint : curveControl) {
                int keyTermDays = ScenarioModelUtils.resolveTermDays(controlPoint);
                if (keyTermDays < 0) {
                    logInvalidTermDays(controlPoint);
                    continue;
                }
                BigDecimal shiftValue = controlPoint.getShockValue() == null
                        ? BigDecimal.ZERO
                        : controlPoint.getShockValue().setScale(5, RoundingMode.HALF_UP);
                String shiftRule = controlPoint.getScenarioShiftRule() == null ? "ABSOLUTE" : controlPoint.getScenarioShiftRule();
                String baseScenarioId = resolveCustomSubScenarioId(controlPoint);
                String subScenarioId = (baseScenarioId == null ? "" : baseScenarioId) + "-" + keyTermDays;

                for (ScenarioMarketSeries datum : entry.getValue()) {
                    int termDays = ScenarioModelUtils.resolveTermDays(datum);
                    if (termDays < 0) {
                        continue;
                    }

                    BigDecimal actualShift;
                    String actualShiftRule;
                    BigDecimal changedValue;
                    if (termDays == keyTermDays) {
                        actualShift = shiftValue;
                        actualShiftRule = shiftRule;
                        if ("ABSOLUTE".equals(shiftRule)) {
                            changedValue = datum.getValue().add(shiftValue);
                        } else if ("RELATIVE".equals(shiftRule)) {
                            changedValue = datum.getValue().multiply(shiftValue.add(BigDecimal.ONE));
                        } else {
                            changedValue = datum.getValue();
                        }
                    } else {
                        actualShift = BigDecimal.ZERO;
                        actualShiftRule = "ABSOLUTE";
                        changedValue = datum.getValue();
                    }

                    result.add(ScenarioModelUtils.toGeneratedRecord(
                            datum,
                            controlPoint,
                            actualShift,
                            actualShiftRule,
                            changedValue,
                            subScenarioId,
                            user));
                }
            }
        }
        return result;
    }

    private String resolveCustomSubScenarioId(ScenarioDefinition definition) {
        if (definition == null) {
            return null;
        }
        String scenarioId = definition.getScenarioId();
        return scenarioId == null ? null : scenarioId.trim();
    }

    private void logInvalidTermDays(ScenarioDefinition metaControl, List<ScenarioDefinition> controlPoints) {
        if (controlPoints == null || controlPoints.isEmpty()) {
            return;
        }
        for (ScenarioDefinition controlPoint : controlPoints) {
            if (ScenarioModelUtils.resolveTermDays(controlPoint) < 0) {
                logInvalidTermDays(controlPoint == null ? metaControl : controlPoint);
            }
        }
    }

    private void logInvalidTermDays(ScenarioDefinition controlPoint) {
        if (controlPoint == null) {
            return;
        }
        log.warn("情景控制点跳过: scenarioId={}"
                + ", scenarioType={}"
                + ", curveType={}"
                + ", curveCode={}"
                + ", reason=对应curveCode期限点为负，跳过",
                safeValue(controlPoint.getScenarioId()),
                safeValue(controlPoint.getScenarioType()),
                safeValue(controlPoint.getCurveType()),
                safeValue(controlPoint.getCurveCode()));
    }

    private String safeValue(String value) {
        return value == null || value.trim().isEmpty() ? "UNKNOWN" : value.trim();
    }

    /**
     * 记录估值日缺失的曲线编码。
     */
    private void logInactiveCurves(
            ScenarioTaskRequest task,
            String curveType,
            List<ScenarioDefinition> controlPoints,
            List<ScenarioMarketSeries> marketPoints) {
        Set<String> expectedCurveCodes = controlPoints.stream()
                .map(ScenarioDefinition::getCurveCode)
                .filter(code -> code != null && !code.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (expectedCurveCodes.isEmpty()) {
            return;
        }
        Set<String> activeCurveCodes = marketPoints == null
                ? Collections.<String>emptySet()
                : marketPoints.stream()
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
}
