package com.zcyh.mr.marketdata;

import com.zcyh.mr.support.Convert;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.support.Series;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author xujg
 * @date 2024-12-06 09:01
 */
public class VolUtil {
    public static final String AXIS2_INTERPOLATE_TYPE_FIELD = "AXIS2_INTERPOLATE_TYPE";

    // VERTEX1,VERTEX2
    public static List<Map<String, Object>> getVolCur(Integer days, List<Map<String, Object>> vol) {
        return getVolCur(days, vol, null, null);
    }

    // VERTEX1,VERTEX2
    public static List<Map<String, Object>> getVolCur(Integer days, List<Map<String, Object>> vol,
            String axis2InterpolateType) {
        return getVolCur(days, vol, null, axis2InterpolateType);
    }

    // VERTEX1,VERTEX2
    public static List<Map<String, Object>> getVolCur(Integer days, List<Map<String, Object>> vol,
            String termInterpolateType, String axis2InterpolateType) {
        String normalizedTermInterpolateType = normalizeTermInterpolateType(termInterpolateType);
        String normalizedAxis2InterpolateType = normalizeAxis2InterpolateType(axis2InterpolateType);
        // 按期限点 VERTEX1、delta VERTEX2 排序
        List<Map<String, Object>> vol_suf = vol.stream().sorted((o1, o2) -> {
            if (Convert.toInt(o1.get("VERTEX1")).equals(Convert.toInt(o2.get("VERTEX1"))))
                return Convert.toDouble(o1.get("VERTEX2")).compareTo(Convert.toDouble(o2.get("VERTEX2")));
            else
                return Convert.toInt(o1.get("VERTEX1")).compareTo(Convert.toInt(o2.get("VERTEX1")));
        }).collect(Collectors.toList());

        return interpolateTermByAxis2Groups(days, vol_suf,
                normalizedTermInterpolateType, normalizedAxis2InterpolateType);
    }

    public static String normalizeAxis2Type(String axis2Type) {
        if (axis2Type == null || axis2Type.trim().isEmpty()) {
            return "DELTA";
        }
        String normalized = axis2Type.trim().toUpperCase(Locale.ROOT);
        if ("DELTA".equals(normalized) || "MONEYNESS".equals(normalized)
                || "STRIKE".equals(normalized) || "UNDERLYING_TERM".equals(normalized)
                || "NONE".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("不支持的 AXIS2_TYPE: " + axis2Type);
    }

    public static String normalizeAxis2InterpolateType(String axis2InterpolateType) {
        if (axis2InterpolateType == null || axis2InterpolateType.trim().isEmpty()) {
            return "linear";
        }
        String normalized = axis2InterpolateType.trim();
        if (!Interpolation.isSupportedType(normalized)) {
            throw new IllegalArgumentException("不支持的 AXIS2_INTERPOLATE_TYPE: " + axis2InterpolateType);
        }
        return normalized;
    }

    public static String normalizeTermInterpolateType(String termInterpolateType) {
        if (termInterpolateType == null || termInterpolateType.trim().isEmpty()) {
            return "LINERVAR";
        }
        String normalized = termInterpolateType.trim().toUpperCase(Locale.ROOT);
        if ("LINERVAR".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("当前仅支持 TERM_INTERPOLATE_TYPE=LINERVAR: " + termInterpolateType);
    }

    public static String requireAxis2InterpolateType(List<Map<String, Object>> volCur) {
        if (volCur == null || volCur.isEmpty()) {
            throw new IllegalArgumentException("波动率曲线切片为空，无法获取 AXIS2_INTERPOLATE_TYPE");
        }
        Object value = volCur.get(0).get(AXIS2_INTERPOLATE_TYPE_FIELD);
        if (value == null || Convert.toStr(value, "").trim().isEmpty()) {
            throw new IllegalArgumentException("波动率曲线切片缺少 AXIS2_INTERPOLATE_TYPE");
        }
        return normalizeAxis2InterpolateType(Convert.toStr(value, ""));
    }

    public static String resolveAxis2Field(String axis2Type) {
        String normalized = normalizeAxis2Type(axis2Type);
        if ("NONE".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    /**
     * 先获取波动率的切片，之后根据期限点线性插值
     * 
     * @date 2024-12-18 10:03:377
     * @author xujg
     */
    public static double underlyingTerm(Integer term, List<Map<String, Object>> vol, String interpolateType) {
        List<Map<String, Object>> terms = vol.stream()
                .sorted(Comparator.comparing(i -> Convert.toInt(i.get("VERTEX2"))))
                .collect(Collectors.toList());
        Series<Integer, Double> volSeries = new Series<>(Integer.class, Double.class);
        terms.forEach(i -> volSeries.put(Convert.toInt(i.get("VERTEX2")), Convert.toDouble(i.get("VOLATILITY_RATE"))));
        return Interpolation.interpolate(volSeries, term, interpolateType);
    }

    /**
     * FRTB Vega 专用：对 shock 比例面仅按 option tenor 做线性传播。
     * 基础波动率面仍由 getVolCur 按现有方差插值处理。
     */
    public static List<Map<String, Object>> getShockVolCurByLinearOptionTerm(Integer days, List<Map<String, Object>> shockVol) {
        return getShockVolCurByLinearOptionTerm(days, shockVol, null);
    }

    public static List<Map<String, Object>> getShockVolCurByLinearOptionTerm(Integer days, List<Map<String, Object>> shockVol,
            String axis2InterpolateType) {
        String normalizedAxis2InterpolateType = normalizeAxis2InterpolateType(axis2InterpolateType);
        List<Map<String, Object>> volSuf = shockVol.stream().sorted((o1, o2) -> {
            if (Convert.toInt(o1.get("VERTEX1")).equals(Convert.toInt(o2.get("VERTEX1")))) {
                return Convert.toDouble(o1.get("VERTEX2")).compareTo(Convert.toDouble(o2.get("VERTEX2")));
            }
            return Convert.toInt(o1.get("VERTEX1")).compareTo(Convert.toInt(o2.get("VERTEX1")));
        }).collect(Collectors.toList());

        return interpolateShockTermByAxis2Groups(days, volSuf, normalizedAxis2InterpolateType);
    }

    /**
     * 将基础面切片和 shock 比例切片按同一底层点位合并，得到最终用于重估的切片。
     * 最终波动率口径为：baseVol * (1 + shockRatio)。
     */
    public static List<Map<String, Object>> mergeVolCurve(List<Map<String, Object>> baseVol, List<Map<String, Object>> shockVol) {
        Map<String, Double> shockMap = new HashMap<>();
        for (Map<String, Object> curveDatum : shockVol) {
            String key = buildVertexKey(curveDatum);
            shockMap.put(key, Convert.toDouble(curveDatum.get("VOLATILITY_RATE")));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : baseVol) {
            Map<String, Object> newMap = new HashMap<>(curveDatum);
            String key = buildVertexKey(curveDatum);
            double shockRatio = shockMap.getOrDefault(key, 0.0);
            double baseRate = Convert.toDouble(curveDatum.get("VOLATILITY_RATE"));
            newMap.put(AXIS2_INTERPOLATE_TYPE_FIELD, curveDatum.get(AXIS2_INTERPOLATE_TYPE_FIELD));
            newMap.put("VOLATILITY_RATE", baseRate * (1.0 + shockRatio));
            result.add(newMap);
        }
        return result;
    }

    /**
     * FRTB Vega 专用：基于标准期限点生成单 tenor shock 比例面。
     * shock 比例只在目标 tenor 生效，其余标准期限点为 0，
     * 后续由 getShockVolCurByLinearOptionTerm 按 option tenor 线性传播。
     */
    public static List<Map<String, Object>> buildSingleTenorShockCurve(
            List<Map<String, Object>> baseVol,
            int[] tenorDays,
            int targetTenorDay,
            double shockRatio) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (baseVol == null || baseVol.isEmpty() || tenorDays == null || tenorDays.length == 0) {
            return result;
        }

        for (int tenorDay : tenorDays) {
            List<Map<String, Object>> tenorSlice = getVolCur(tenorDay, baseVol);
            for (Map<String, Object> curveDatum : tenorSlice) {
                Map<String, Object> newMap = new HashMap<>(curveDatum);
                newMap.put("VERTEX1", tenorDay);
                newMap.put("VOLATILITY_RATE", tenorDay == targetTenorDay ? shockRatio : 0.0);
                result.add(newMap);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> interpolateTermByAxis2Groups(
            Integer days,
            List<Map<String, Object>> curveData,
            String termInterpolateType,
            String axis2InterpolateType) {
        if (!"LINERVAR".equals(termInterpolateType)) {
            throw new IllegalArgumentException("当前仅支持 TERM_INTERPOLATE_TYPE=LINERVAR: " + termInterpolateType);
        }
        Map<Double, List<Map<String, Object>>> axis2Groups = groupByAxis2(curveData);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Double, List<Map<String, Object>>> entry : axis2Groups.entrySet()) {
            result.add(interpolateTermWithinAxis2(days, entry.getKey(), entry.getValue(), axis2InterpolateType));
        }
        return result;
    }

    private static List<Map<String, Object>> interpolateShockTermByAxis2Groups(
            Integer days,
            List<Map<String, Object>> curveData,
            String axis2InterpolateType) {
        Map<Double, List<Map<String, Object>>> axis2Groups = groupByAxis2(curveData);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Double, List<Map<String, Object>>> entry : axis2Groups.entrySet()) {
            result.add(interpolateShockTermWithinAxis2(days, entry.getKey(), entry.getValue(), axis2InterpolateType));
        }
        return result;
    }

    private static Map<Double, List<Map<String, Object>>> groupByAxis2(List<Map<String, Object>> curveData) {
        Map<Double, List<Map<String, Object>>> axis2Groups = new TreeMap<>();
        for (Map<String, Object> curveDatum : curveData) {
            axis2Groups.computeIfAbsent(Convert.toDouble(curveDatum.get("VERTEX2")), k -> new ArrayList<>())
                    .add(curveDatum);
        }
        for (List<Map<String, Object>> group : axis2Groups.values()) {
            group.sort(Comparator.comparing(i -> Convert.toInt(i.get("VERTEX1"))));
        }
        return axis2Groups;
    }

    private static Map<String, Object> interpolateTermWithinAxis2(
            Integer days,
            Double axis2,
            List<Map<String, Object>> axis2Points,
            String axis2InterpolateType) {
        TermBounds bounds = findTermBounds(days, axis2Points);
        if (bounds.exact != null) {
            return retagSingleVertex(bounds.exact, days, axis2, axis2InterpolateType);
        }
        if (bounds.lower == null) {
            return retagSingleVertex(bounds.upper, days, axis2, axis2InterpolateType);
        }
        if (bounds.upper == null) {
            return retagSingleVertex(bounds.lower, days, axis2, axis2InterpolateType);
        }

        Double lowerTerm = Convert.toDouble(bounds.lower.get("VERTEX1"));
        Double upperTerm = Convert.toDouble(bounds.upper.get("VERTEX1"));
        double lowerRate = Convert.toDouble(bounds.lower.get("VOLATILITY_RATE"));
        double upperRate = Convert.toDouble(bounds.upper.get("VOLATILITY_RATE"));
        double alpha = (upperTerm - days) / (upperTerm - lowerTerm);

        Map<String, Object> newMap = new HashMap<>(bounds.lower);
        newMap.put("VERTEX1", days);
        newMap.put("VERTEX2", axis2);
        newMap.put(AXIS2_INTERPOLATE_TYPE_FIELD, axis2InterpolateType);
        newMap.put("VOLATILITY_RATE",
                Math.sqrt((lowerRate * lowerRate * lowerTerm * alpha
                        + upperRate * upperRate * upperTerm * (1 - alpha)) / days));
        return newMap;
    }

    private static Map<String, Object> interpolateShockTermWithinAxis2(
            Integer days,
            Double axis2,
            List<Map<String, Object>> axis2Points,
            String axis2InterpolateType) {
        TermBounds bounds = findTermBounds(days, axis2Points);
        if (bounds.exact != null) {
            return retagSingleVertex(bounds.exact, days, axis2, axis2InterpolateType);
        }
        if (bounds.lower == null) {
            return retagSingleVertex(bounds.upper, days, axis2, axis2InterpolateType);
        }
        if (bounds.upper == null) {
            return retagSingleVertex(bounds.lower, days, axis2, axis2InterpolateType);
        }

        Double lowerTerm = Convert.toDouble(bounds.lower.get("VERTEX1"));
        Double upperTerm = Convert.toDouble(bounds.upper.get("VERTEX1"));
        double lowerRate = Convert.toDouble(bounds.lower.get("VOLATILITY_RATE"));
        double upperRate = Convert.toDouble(bounds.upper.get("VOLATILITY_RATE"));

        Map<String, Object> newMap = new HashMap<>(bounds.lower);
        newMap.put("VERTEX1", days);
        newMap.put("VERTEX2", axis2);
        newMap.put(AXIS2_INTERPOLATE_TYPE_FIELD, axis2InterpolateType);
        newMap.put("VOLATILITY_RATE", Interpolation.interpolation(
                lowerTerm,
                lowerRate,
                upperTerm,
                upperRate,
                (double) days));
        return newMap;
    }

    private static TermBounds findTermBounds(Integer days, List<Map<String, Object>> axis2Points) {
        TermBounds bounds = new TermBounds();
        for (Map<String, Object> curveDatum : axis2Points) {
            int term = Convert.toInt(curveDatum.get("VERTEX1"));
            if (term == days) {
                bounds.exact = curveDatum;
                return bounds;
            }
            if (term < days) {
                bounds.lower = curveDatum;
            } else {
                bounds.upper = curveDatum;
                return bounds;
            }
        }
        return bounds;
    }

    private static Map<String, Object> retagSingleVertex(
            Map<String, Object> curveDatum,
            Integer days,
            Double axis2,
            String axis2InterpolateType) {
        Map<String, Object> newMap = new HashMap<>(curveDatum);
        newMap.put("VERTEX1", days);
        newMap.put("VERTEX2", axis2);
        newMap.put(AXIS2_INTERPOLATE_TYPE_FIELD, axis2InterpolateType);
        return newMap;
    }

    private static class TermBounds {
        private Map<String, Object> lower;
        private Map<String, Object> upper;
        private Map<String, Object> exact;
    }

    private static List<Map<String, Object>> markAxis2InterpolateType(
            List<Map<String, Object>> curveData,
            String axis2InterpolateType) {
        String normalizedAxis2InterpolateType = normalizeAxis2InterpolateType(axis2InterpolateType);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            Map<String, Object> newMap = new HashMap<>(curveDatum);
            newMap.put(AXIS2_INTERPOLATE_TYPE_FIELD, normalizedAxis2InterpolateType);
            result.add(newMap);
        }
        return result;
    }

    private static String buildVertexKey(Map<String, Object> curveDatum) {
        // VERTEX1/VERTEX2 表示曲面上的实际数值，不能按整数序号截断，否则会把不同 DELTA 点错误合并。
        return normalizeVertexValue(curveDatum.get("VERTEX1")) + "_" + normalizeVertexValue(curveDatum.get("VERTEX2"));
    }

    private static String normalizeVertexValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).stripTrailingZeros().toPlainString();
        }
        String text = Convert.toStr(value, "");
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        try {
            return new BigDecimal(text.trim()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return text.trim();
        }
    }

}
