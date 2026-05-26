package com.zcyh.mr.scenario.processor;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioShift;
import com.zcyh.mr.scenario.util.ScenarioModelUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 线性插值器。
 */
public class LinearInterpolator {

    private static final int DIV_SCALE = 12;

    /**
     * 线性插值。
     */
    public BigDecimal interpolate(
            ScenarioMarketSeries startPoint,
            ScenarioMarketSeries endPoint,
            int targetTermDays,
            Integer startPosition,
            Integer endPosition) {
        BigDecimal startRate = startPoint == null ? null : startPoint.getValue();
        BigDecimal endRate = endPoint == null ? null : endPoint.getValue();
        if (startRate == null || endRate == null) {
            return null;
        }

        if (startRate.compareTo(endRate) == 0) {
            return startRate;
        }

        if (startPosition != null && endPosition != null) {
            int span = endPosition - startPosition;
            if (span <= 0) {
                return startRate;
            }
            BigDecimal ratio = BigDecimal.ONE.divide(new BigDecimal(span), DIV_SCALE, RoundingMode.HALF_UP);
            return startRate.add(endRate.subtract(startRate).multiply(ratio));
        }

        int startDays = ScenarioModelUtils.resolveTermDays(startPoint);
        int endDays = ScenarioModelUtils.resolveTermDays(endPoint);
        if (endDays == startDays) {
            return startRate;
        }

        BigDecimal slope = endRate.subtract(startRate)
                .divide(new BigDecimal(endDays - startDays), DIV_SCALE, RoundingMode.HALF_UP);
        return startRate.add(slope.multiply(new BigDecimal(targetTermDays - startDays)));
    }

    /**
     * 获取控制点插值后的冲击值。
     */
    public ScenarioShift getShiftValueByControlPoints(
            int termDays,
            List<ScenarioDefinition> controlList) {
        if (controlList == null || controlList.isEmpty()) {
            return new ScenarioShift(BigDecimal.ZERO, "ABSOLUTE");
        }

        ScenarioDefinition exactMatch = null;
        ScenarioDefinition before = null;
        ScenarioDefinition after = null;

        for (ScenarioDefinition control : controlList) {
            int days = ScenarioModelUtils.resolveTermDays(control);
            if (days == termDays) {
                exactMatch = control;
                break;
            }
            if (days < termDays) {
                before = control;
                continue;
            }
            if (days > termDays && after == null) {
                after = control;
                break;
            }
        }

        if (exactMatch != null) {
            return new ScenarioShift(exactMatch.getShockValue(), exactMatch.getScenarioShiftRule());
        }
        if (before == null && after != null) {
            return new ScenarioShift(after.getShockValue(), after.getScenarioShiftRule());
        }
        if (before != null && after == null) {
            return new ScenarioShift(before.getShockValue(), before.getScenarioShiftRule());
        }
        if (before != null && after != null) {
            int beforeDays = ScenarioModelUtils.resolveTermDays(before);
            int afterDays = ScenarioModelUtils.resolveTermDays(after);
            BigDecimal beforeValue = before.getShockValue();
            BigDecimal afterValue = after.getShockValue();
            BigDecimal interpolatedValue = beforeValue.add(
                    afterValue.subtract(beforeValue)
                            .divide(new BigDecimal(afterDays - beforeDays), DIV_SCALE, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(termDays - beforeDays)));
            return new ScenarioShift(interpolatedValue, before.getScenarioShiftRule());
        }

        return new ScenarioShift(BigDecimal.ZERO, "ABSOLUTE");
    }
}
