package com.zcyh.mr.product.basic.frtb.builder;

import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险类别 builder 公共基础方法。
 * 仅承载纯公共工具，不承载具体风险类别规则。
 */
public abstract class AbstractSensitivityBuilder {

    protected static Map<String, Double> sumByBucket(List<FrtbSenes> sensitivities, boolean cny) {
        Map<String, Double> map = new LinkedHashMap<>();
        if (sensitivities == null) {
            return map;
        }
        for (FrtbSenes sensitivity : sensitivities) {
            if (sensitivity == null || !hasText(sensitivity.riskFactorBucket)) {
                continue;
            }
            double value = cny ? sensitivity.sensitivityValInstCurrCny : sensitivity.sensitivityValInstCurr;
            map.put(sensitivity.riskFactorBucket, map.getOrDefault(sensitivity.riskFactorBucket, 0.0) + value);
        }
        return map;
    }

    protected static boolean isNoChangeByCny(MeasureValuation base, MeasureValuation shockedValuation, double zeroTolerance) {
        if (base == null || shockedValuation == null) {
            return true;
        }
        return Math.abs(shockedValuation.valuationCny - base.valuationCny) < zeroTolerance;
    }

    protected static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Vega 最终统一按配置的相对 shock 归一化。
     */
    protected static double normalizeVega(double shockedValue, double baseValue) {
        return (shockedValue - baseValue) / FrtbParamsCache.getVegaShockRatio();
    }

    /**
     * 商品 Delta 统一按相对 shock 1% 归一化。
     */
    protected static double normalizeDelta(double shockedValue, double baseValue) {
        return (shockedValue - baseValue) / FrtbParamsCache.getCmtyDeltaShockRatio();
    }

    /**
     * 将交易原始期限映射到标准 Vega 期限。
     * 若与某一标准点前后 5 天内命中，则 100% 落该点，否则在线性相邻点间拆分。
     */
    protected static Map<String, Double> splitToStandardVegaTenorsWithTolerance(
            LocalDate dataDate,
            String rawVertex,
            int toleranceDays) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (dataDate == null || !hasText(rawVertex)) {
            return result;
        }
        Integer rawDays = resolveVertexDays(dataDate, rawVertex);
        if (rawDays == null || rawDays <= 0) {
            return result;
        }
        String[] tenorCodes = FrtbParamsCache.getVegaTenorCodes();
        String[] tenorVertices = FrtbParamsCache.getVegaTenorVertices();
        int[] tenorDays = new int[tenorCodes.length];
        for (int i = 0; i < tenorCodes.length; i++) {
            tenorDays[i] = resolveVertexDays(dataDate, tenorCodes[i]);
        }
        for (int i = 0; i < tenorDays.length; i++) {
            if (Math.abs(rawDays - tenorDays[i]) <= toleranceDays) {
                result.put(tenorVertices[i], 1.0);
                return result;
            }
        }
        if (rawDays <= tenorDays[0]) {
            result.put(tenorVertices[0], 1.0);
            return result;
        }
        if (rawDays >= tenorDays[tenorDays.length - 1]) {
            result.put(tenorVertices[tenorDays.length - 1], 1.0);
            return result;
        }
        for (int i = 0; i < tenorDays.length - 1; i++) {
            int leftDays = tenorDays[i];
            int rightDays = tenorDays[i + 1];
            if (rawDays < leftDays || rawDays > rightDays) {
                continue;
            }
            double span = rightDays - leftDays;
            if (span <= 0) {
                result.put(tenorVertices[i], 1.0);
                return result;
            }
            double rightWeight = (rawDays - leftDays) / span;
            double leftWeight = 1.0 - rightWeight;
            if (leftWeight > 0) {
                result.put(tenorVertices[i], leftWeight);
            }
            if (rightWeight > 0) {
                result.put(tenorVertices[i + 1], rightWeight);
            }
            return result;
        }
        return result;
    }

    /**
     * 将期限字符串统一解析成天数。
     * 标准 tenor 使用日历月/年滚动；纯数字视作年。
     */
    protected static Integer resolveVertexDays(LocalDate dataDate, String rawVertex) {
        if (dataDate == null || !hasText(rawVertex)) {
            return null;
        }
        String normalized = rawVertex.trim().toUpperCase();
        try {
            if (normalized.endsWith("Y")) {
                int years = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
                return (int) java.time.temporal.ChronoUnit.DAYS.between(dataDate, dataDate.plusYears(years));
            }
            if (normalized.endsWith("M")) {
                int months = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
                return (int) java.time.temporal.ChronoUnit.DAYS.between(dataDate, dataDate.plusMonths(months));
            }
            if (normalized.endsWith("D")) {
                int days = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
                return days;
            }
            double years = Double.parseDouble(normalized);
            if (years < 0) {
                return null;
            }
            return (int) Math.round(365.0 * years);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
