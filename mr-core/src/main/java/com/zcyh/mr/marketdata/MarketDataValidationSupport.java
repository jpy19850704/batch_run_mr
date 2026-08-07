package com.zcyh.mr.marketdata;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MarketDataValidationSupport {
    private MarketDataValidationSupport() {
    }

    static void validateVolSurface(
            String curveId,
            List<VolSurfacePoint> curveData,
            String axis2Type,
            VolAxisType expectedAxis2Type,
            List<String> errors) {
        if (curveId == null || curveId.trim().isEmpty()) {
            errors.add("CURVE_ID 不能为空");
            return;
        }
        try {
            VolAxisType actualAxis2Type = VolAxisType.parse(axis2Type);
            if (actualAxis2Type != expectedAxis2Type) {
                errors.add(curveId + ": AXIS2_TYPE必须为" + expectedAxis2Type.name());
            }
        } catch (IllegalArgumentException ex) {
            errors.add(curveId + ": " + ex.getMessage());
        }
        if (curveData == null || curveData.isEmpty()) {
            errors.add(curveId + ": CURVE_DATA 不能为空");
            return;
        }
        Set<String> dimensions = new HashSet<String>();
        for (VolSurfacePoint point : curveData) {
            if (point == null) {
                errors.add(curveId + ": 曲面点为空");
                continue;
            }
            if (point.getOptionTerm() < 0
                    || !Double.isFinite(point.getAxis2Value())
                    || !Double.isFinite(point.getVolatilityRate())) {
                errors.add(curveId + ": 波动率曲面坐标和波动率必须为有效数值");
                continue;
            }
            String dimension = point.getOptionTerm() + "|" + point.getAxis2Value();
            if (!dimensions.add(dimension)) {
                errors.add(curveId + ": 曲面维度重复: " + dimension);
            }
        }
    }
}
