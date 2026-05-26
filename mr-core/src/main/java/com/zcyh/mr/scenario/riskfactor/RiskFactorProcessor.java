package com.zcyh.mr.scenario.riskfactor;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioShift;
import com.zcyh.mr.scenario.processor.LinearInterpolator;
import com.zcyh.mr.scenario.util.ScenarioModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 风险因子处理器（合并了接口、抽象基类和工厂）。
 *
 * <p>
 * 负责风险因子类型的唯一标识、期限口径和冲击应用等处理逻辑。
 */
public abstract class RiskFactorProcessor {
    private static final Logger log = LoggerFactory.getLogger(RiskFactorProcessor.class);

    private static final Map<String, RiskFactorProcessor> PROCESSORS = new HashMap<String, RiskFactorProcessor>();

    static {
        register(new IrSpotProcessor());
        register(new FxSpotProcessor());
        register(new CommSpotProcessor());
        register(new EqSpotProcessor());
        register(new IrVolProcessor());
        register(new FxVolProcessor());
        register(new CommVolProcessor());
        register(new EqVolProcessor());
    }

    protected static final String ABSOLUTE = "ABSOLUTE";
    protected static final String RELATIVE = "RELATIVE";
    protected static final String SPLIT_STR = "_";
    private static final LinearInterpolator INTERPOLATOR = new LinearInterpolator();

    protected final String riskFactorType;

    protected RiskFactorProcessor(String riskFactorType) {
        this.riskFactorType = riskFactorType;
    }

    protected static void register(RiskFactorProcessor processor) {
        PROCESSORS.put(processor.getRiskFactorType(), processor);
    }

    /**
     * 获取风险因子处理器。
     */
    public static RiskFactorProcessor getProcessor(String riskFactorType) {
        RiskFactorProcessor processor = PROCESSORS.get(riskFactorType);
        if (processor == null) {
            throw new IllegalArgumentException("不支持的风险因子类型: " + riskFactorType);
        }
        return processor;
    }

    public static boolean supports(String riskFactorType) {
        return PROCESSORS.containsKey(riskFactorType);
    }

    public static String[] getSupportedTypes() {
        return PROCESSORS.keySet().toArray(new String[0]);
    }

    public String getRiskFactorType() {
        return riskFactorType;
    }

    /**
     * 应用冲击值。
     */
    public BigDecimal applyShift(BigDecimal currentRate, BigDecimal shiftValue, String shiftRule) {
        if (currentRate == null || shiftValue == null || shiftRule == null) {
            return currentRate;
        }

        if (ABSOLUTE.equals(shiftRule)) {
            return currentRate.add(shiftValue);
        }
        if (RELATIVE.equals(shiftRule)) {
            return currentRate.multiply(shiftValue.add(BigDecimal.ONE));
        }
        return currentRate;
    }

    /**
     * 验证数据完整性。
     */
    public boolean validate(ScenarioMarketSeries data) {
        if (data == null) {
            return false;
        }

        boolean basicValid = data.getCurveCode() != null
                && !data.getCurveCode().trim().isEmpty()
                && data.getValue() != null;
        if (!basicValid) {
            return false;
        }

        if (requiresTermMetadata()) {
            boolean termCodeMissing = data.getTermCode() == null || data.getTermCode().trim().isEmpty();
            boolean termDaysMissing = data.getTermDays() == null || data.getTermDays() <= 0;
            return !(termCodeMissing && termDaysMissing);
        }
        return true;
    }

    public abstract String getUniqueCode(ScenarioMarketSeries data);

    public abstract String getTermCode(ScenarioMarketSeries data);

    public abstract boolean needsTermInterpolation();

    /**
     * 预处理钩子。
     */
    public void preProcess(List<ScenarioMarketSeries> data) {
        // 默认空实现
    }

    /**
     * 后处理钩子。
     */
    public void postProcess(List<ScenarioGeneratedRecord> data) {
        // 默认空实现
    }

    /**
     * 使用标准对象执行风险因子冲击。
     */
    public List<ScenarioGeneratedRecord> process(
            List<ScenarioMarketSeries> dataList,
            List<ScenarioDefinition> controlList,
            String user) {
        if (dataList == null || dataList.isEmpty()) {
            return new ArrayList<ScenarioGeneratedRecord>();
        }

        for (ScenarioMarketSeries datum : dataList) {
            if (!validate(datum)) {
                throw new IllegalArgumentException("风险因子数据缺少必要元数据: type="
                        + riskFactorType
                        + ", curveCode="
                        + datum.getCurveCode()
                        + ", termCode="
                        + datum.getTermCode()
                        + ", termDays="
                        + datum.getTermDays());
            }
        }

        preProcess(dataList);

        List<ScenarioDefinition> validControl = new ArrayList<ScenarioDefinition>();
        if (controlList != null) {
            for (ScenarioDefinition point : controlList) {
                if (riskFactorType.equals(point.getCurveType())) {
                    validControl.add(point);
                }
            }
        }
        if (validControl.isEmpty()) {
            return new ArrayList<ScenarioGeneratedRecord>();
        }

        List<ScenarioGeneratedRecord> result;
        if (needsTermInterpolation()) {
            result = processWithInterpolation(dataList, validControl, user);
        } else {
            result = processWithoutInterpolation(dataList, validControl, user);
        }

        postProcess(result);
        return result;
    }

    protected List<ScenarioGeneratedRecord> processWithInterpolation(
            List<ScenarioMarketSeries> dataList,
            List<ScenarioDefinition> controlList,
            String user) {
        Map<String, List<ScenarioMarketSeries>> dataByCurve = dataList.stream()
                .collect(Collectors.groupingBy(ScenarioMarketSeries::getCurveCode));
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();

        dataByCurve.forEach((curveCode, curveDataList) ->
                result.addAll(processCurveWithInterpolation(curveCode, curveDataList, controlList, user)));
        return result;
    }

    private List<ScenarioGeneratedRecord> processCurveWithInterpolation(
            String curveCode,
            List<ScenarioMarketSeries> curveDataList,
            List<ScenarioDefinition> controlList,
            String user) {
        try {
            List<ScenarioDefinition> curveControl = controlList.stream()
                    .filter(point -> curveCode.equals(point.getCurveCode()))
                    .sorted((m1, m2) -> ScenarioModelUtils.resolveTermDays(m1) - ScenarioModelUtils.resolveTermDays(m2))
                    .collect(Collectors.toList());
            if (curveControl.isEmpty()) {
                return new ArrayList<ScenarioGeneratedRecord>();
            }

            ScenarioDefinition metaControl = curveControl.get(0);
            List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
            for (ScenarioMarketSeries datum : curveDataList) {
                int termDays = ScenarioModelUtils.resolveTermDays(datum);
                if (termDays <= 0) {
                    continue;
                }

                ScenarioShift shift = INTERPOLATOR.getShiftValueByControlPoints(termDays, curveControl);
                BigDecimal shiftValue = shift.getShiftValue() == null
                        ? BigDecimal.ZERO
                        : shift.getShiftValue().setScale(5, RoundingMode.HALF_UP);
                String shiftRule = shift.getShiftRule() == null ? ABSOLUTE : shift.getShiftRule();
                result.add(buildGeneratedRecord(datum, metaControl, shiftValue, shiftRule, user));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("风险因子曲线处理失败: curveCode="
                    + curveCode + ", riskFactorType=" + riskFactorType, e);
        }
    }

    protected List<ScenarioGeneratedRecord> processWithoutInterpolation(
            List<ScenarioMarketSeries> dataList,
            List<ScenarioDefinition> controlList,
            String user) {
        Map<String, List<ScenarioMarketSeries>> dataByCurve = dataList.stream()
                .collect(Collectors.groupingBy(ScenarioMarketSeries::getCurveCode));
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();

        dataByCurve.forEach((curveCode, curveData) ->
                result.addAll(processCurveWithoutInterpolation(curveCode, curveData, controlList, user)));
        return result;
    }

    private List<ScenarioGeneratedRecord> processCurveWithoutInterpolation(
            String curveCode,
            List<ScenarioMarketSeries> curveData,
            List<ScenarioDefinition> controlList,
            String user) {
        try {
            List<ScenarioDefinition> curveControl = controlList.stream()
                    .filter(point -> curveCode.equals(point.getCurveCode()))
                    .sorted((m1, m2) -> ScenarioModelUtils.resolveTermDays(m1) - ScenarioModelUtils.resolveTermDays(m2))
                    .collect(Collectors.toList());
            if (curveControl.isEmpty()) {
                return new ArrayList<ScenarioGeneratedRecord>();
            }

            ScenarioDefinition targetControl = curveControl.get(0);
            BigDecimal shiftValue = targetControl.getShockValue() == null ? BigDecimal.ZERO : targetControl.getShockValue();
            String shiftRule = targetControl.getScenarioShiftRule() == null ? ABSOLUTE : targetControl.getScenarioShiftRule();

            List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
            for (ScenarioMarketSeries datum : curveData) {
                result.add(buildGeneratedRecord(datum, targetControl, shiftValue, shiftRule, user));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("风险因子曲线处理失败: curveCode="
                    + curveCode + ", riskFactorType=" + riskFactorType, e);
        }
    }

    private ScenarioGeneratedRecord buildGeneratedRecord(
            ScenarioMarketSeries datum,
            ScenarioDefinition control,
            BigDecimal shiftValue,
            String shiftRule,
            String user) {
        BigDecimal changedRate = applyShift(datum.getValue(), shiftValue, shiftRule);
        ScenarioGeneratedRecord record = ScenarioModelUtils.toGeneratedRecord(
                datum,
                control,
                shiftValue,
                shiftRule,
                changedRate,
                control == null ? null : control.getScenarioId(),
                user);
        record.setTermCode(getTermCode(datum));
        return record;
    }

    /**
     * 需要期限元数据的风险因子类型直接在程序中严格校验。
     */
    private boolean requiresTermMetadata() {
        return !"FX_SPOT".equals(riskFactorType);
    }
}
