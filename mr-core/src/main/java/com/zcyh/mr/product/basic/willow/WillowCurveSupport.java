package com.zcyh.mr.product.basic.willow;

import com.zcyh.mr.support.Series;

import java.util.Map;

public final class WillowCurveSupport {
    private WillowCurveSupport() {
    }

    public static double firstNonNegativePoint(Series<Integer, Double> curveData, String curveId) {
        if (curveData == null || curveData.isEmpty()) {
            throw new IllegalArgumentException("WILLOW_REFERENCE_CURVE数据为空: " + curveId);
        }
        for (Map.Entry<Integer, Double> entry : curveData.entrySet()) {
            Integer term = entry.getKey();
            Double value = entry.getValue();
            if (term != null && term >= 0) {
                if (value == null || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("股票spot点位非法: curveId=" + curveId + ", term=" + term);
                }
                return value;
            }
        }
        throw new IllegalArgumentException("股票spot点位不存在: curveId=" + curveId);
    }

    public static double equityForward(double spot, double discountFactor) {
        if (spot <= 0.0 || !Double.isFinite(spot)) {
            throw new IllegalArgumentException("股票spot必须大于0");
        }
        if (discountFactor <= 0.0 || !Double.isFinite(discountFactor)) {
            throw new IllegalArgumentException("折现因子必须大于0");
        }
        return spot / discountFactor;
    }
}
