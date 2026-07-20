package com.zcyh.mr.marketdata;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MarketDataValidationSupport {
    private MarketDataValidationSupport() {
    }

    static void validateVolSurface(
            String curveId,
            List<Map<String, Object>> curveData,
            String axis2Type,
            String defaultAxis2Type,
            List<String> errors) {
        if (curveId == null || curveId.trim().isEmpty()) {
            errors.add("CURVE_ID 不能为空");
            return;
        }
        if (curveData == null || curveData.isEmpty()) {
            errors.add(curveId + ": CURVE_DATA 不能为空");
            return;
        }
        String effectiveAxis2Type = isBlank(axis2Type) ? defaultAxis2Type : axis2Type;
        String axis2Field;
        try {
            axis2Field = VolUtil.resolveAxis2Field(effectiveAxis2Type);
        } catch (IllegalArgumentException ex) {
            errors.add(curveId + ": " + ex.getMessage());
            return;
        }
        Set<String> dimensions = new HashSet<String>();
        for (Map<String, Object> point : curveData) {
            if (point == null) {
                errors.add(curveId + ": 曲面点为空");
                continue;
            }
            Object optionTerm = point.get("OPTION_TERM");
            Object axis2 = point.get(axis2Field);
            Object volatility = point.get("VOLATILITY_RATE");
            if (!isFinite(optionTerm) || !isFinite(axis2) || !isFinite(volatility)) {
                errors.add(curveId + ": OPTION_TERM、" + axis2Field + "和VOLATILITY_RATE必须为有效数值");
                continue;
            }
            String dimension = optionTerm.toString() + "|" + axis2.toString();
            if (!dimensions.add(dimension)) {
                errors.add(curveId + ": 曲面维度重复: " + dimension);
            }
        }
    }

    private static boolean isFinite(Object value) {
        if (value == null) {
            return false;
        }
        try {
            return Double.isFinite(Double.parseDouble(value.toString()));
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
