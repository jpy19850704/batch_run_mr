package com.zcyh.mr.marketdata;

import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.support.Series;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class VolUtil {
    private VolUtil() {
    }

    public static List<VolSurfacePoint> getVolCur(Integer days, List<VolSurfacePoint> vol) {
        return getVolCur(days, vol, null, null);
    }

    public static List<VolSurfacePoint> getVolCur(
            Integer days,
            List<VolSurfacePoint> vol,
            String axis2InterpolateType) {
        return getVolCur(days, vol, null, axis2InterpolateType);
    }

    public static List<VolSurfacePoint> getVolCur(
            Integer days,
            List<VolSurfacePoint> vol,
            String termInterpolateType,
            String axis2InterpolateType) {
        String normalizedTermInterpolateType = normalizeTermInterpolateType(termInterpolateType);
        String normalizedAxis2InterpolateType = normalizeAxis2InterpolateType(axis2InterpolateType);
        List<VolSurfacePoint> sorted = vol.stream()
                .sorted(Comparator.comparingInt(VolSurfacePoint::getOptionTerm)
                        .thenComparingDouble(VolSurfacePoint::getAxis2Value))
                .collect(Collectors.toList());
        return interpolateTermByAxis2Groups(
                days, sorted, normalizedTermInterpolateType, normalizedAxis2InterpolateType);
    }

    public static String normalizeAxis2Type(String axis2Type) {
        return VolAxisType.parse(axis2Type).name();
    }

    public static String normalizeAxis2InterpolateType(String axis2InterpolateType) {
        if (axis2InterpolateType == null || axis2InterpolateType.trim().isEmpty()) {
            return "linear";
        }
        String normalized = axis2InterpolateType.trim();
        if (!Interpolation.isSupportedType(normalized)) {
            throw new IllegalArgumentException("不支持的AXIS2_INTERPOLATE_TYPE: " + axis2InterpolateType);
        }
        return normalized;
    }

    public static String normalizeTermInterpolateType(String termInterpolateType) {
        if (termInterpolateType == null || termInterpolateType.trim().isEmpty()) {
            return "LINERVAR";
        }
        String normalized = termInterpolateType.trim().toUpperCase(Locale.ROOT);
        if (!"LINERVAR".equals(normalized)) {
            throw new IllegalArgumentException("当前仅支持TERM_INTERPOLATE_TYPE=LINERVAR: " + termInterpolateType);
        }
        return normalized;
    }

    public static String requireAxis2InterpolateType(List<VolSurfacePoint> volCur) {
        if (volCur == null || volCur.isEmpty()) {
            throw new IllegalArgumentException("波动率曲线切片为空，无法获取AXIS2_INTERPOLATE_TYPE");
        }
        String value = volCur.get(0).getAxis2InterpolateType();
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("波动率曲线切片缺少AXIS2_INTERPOLATE_TYPE");
        }
        return normalizeAxis2InterpolateType(value);
    }

    public static String resolveAxis2Field(String axis2Type) {
        return VolAxisType.parse(axis2Type).fieldName();
    }

    public static double underlyingTerm(
            Integer term,
            List<VolSurfacePoint> vol,
            String interpolateType) {
        List<VolSurfacePoint> terms = vol.stream()
                .sorted(Comparator.comparingDouble(VolSurfacePoint::getAxis2Value))
                .collect(Collectors.toList());
        Series<Integer, Double> volSeries = new Series<Integer, Double>(Integer.class, Double.class);
        for (VolSurfacePoint point : terms) {
            volSeries.put((int) point.getAxis2Value(), point.getVolatilityRate());
        }
        return Interpolation.interpolate(volSeries, term, interpolateType);
    }

    public static List<VolSurfacePoint> getShockVolCurByLinearOptionTerm(
            Integer days,
            List<VolSurfacePoint> shockVol) {
        return getShockVolCurByLinearOptionTerm(days, shockVol, null);
    }

    public static List<VolSurfacePoint> getShockVolCurByLinearOptionTerm(
            Integer days,
            List<VolSurfacePoint> shockVol,
            String axis2InterpolateType) {
        String normalizedAxis2InterpolateType = normalizeAxis2InterpolateType(axis2InterpolateType);
        List<VolSurfacePoint> sorted = shockVol.stream()
                .sorted(Comparator.comparingInt(VolSurfacePoint::getOptionTerm)
                        .thenComparingDouble(VolSurfacePoint::getAxis2Value))
                .collect(Collectors.toList());
        return interpolateShockTermByAxis2Groups(days, sorted, normalizedAxis2InterpolateType);
    }

    public static List<VolSurfacePoint> mergeVolCurve(
            List<VolSurfacePoint> baseVol,
            List<VolSurfacePoint> shockVol) {
        Map<String, Double> shockMap = new HashMap<String, Double>();
        for (VolSurfacePoint point : shockVol) {
            shockMap.put(buildPointKey(point), point.getVolatilityRate());
        }
        List<VolSurfacePoint> result = new ArrayList<VolSurfacePoint>(baseVol.size());
        for (VolSurfacePoint point : baseVol) {
            double shockRatio = shockMap.getOrDefault(buildPointKey(point), 0.0d);
            result.add(point.withVolatilityRate(point.getVolatilityRate() * (1.0d + shockRatio)));
        }
        return result;
    }

    public static List<VolSurfacePoint> buildSingleTenorShockCurve(
            List<VolSurfacePoint> baseVol,
            int[] tenorDays,
            int targetTenorDay,
            double shockRatio) {
        List<VolSurfacePoint> result = new ArrayList<VolSurfacePoint>();
        if (baseVol == null || baseVol.isEmpty() || tenorDays == null || tenorDays.length == 0) {
            return result;
        }
        for (int tenorDay : tenorDays) {
            List<VolSurfacePoint> tenorSlice = getVolCur(tenorDay, baseVol);
            for (VolSurfacePoint point : tenorSlice) {
                result.add(new VolSurfacePoint(
                        tenorDay,
                        point.getAxis2Value(),
                        tenorDay == targetTenorDay ? shockRatio : 0.0d,
                        point.getAxis2InterpolateType(),
                        false));
            }
        }
        return result;
    }

    private static List<VolSurfacePoint> interpolateTermByAxis2Groups(
            Integer days,
            List<VolSurfacePoint> curveData,
            String termInterpolateType,
            String axis2InterpolateType) {
        if (!"LINERVAR".equals(termInterpolateType)) {
            throw new IllegalArgumentException("当前仅支持TERM_INTERPOLATE_TYPE=LINERVAR: " + termInterpolateType);
        }
        Map<Double, List<VolSurfacePoint>> axis2Groups = groupByAxis2(curveData);
        List<VolSurfacePoint> result = new ArrayList<VolSurfacePoint>(axis2Groups.size());
        for (Map.Entry<Double, List<VolSurfacePoint>> entry : axis2Groups.entrySet()) {
            result.add(interpolateTermWithinAxis2(
                    days, entry.getKey(), entry.getValue(), axis2InterpolateType));
        }
        return result;
    }

    private static List<VolSurfacePoint> interpolateShockTermByAxis2Groups(
            Integer days,
            List<VolSurfacePoint> curveData,
            String axis2InterpolateType) {
        Map<Double, List<VolSurfacePoint>> axis2Groups = groupByAxis2(curveData);
        List<VolSurfacePoint> result = new ArrayList<VolSurfacePoint>(axis2Groups.size());
        for (Map.Entry<Double, List<VolSurfacePoint>> entry : axis2Groups.entrySet()) {
            result.add(interpolateShockTermWithinAxis2(
                    days, entry.getKey(), entry.getValue(), axis2InterpolateType));
        }
        return result;
    }

    private static Map<Double, List<VolSurfacePoint>> groupByAxis2(List<VolSurfacePoint> curveData) {
        Map<Double, List<VolSurfacePoint>> axis2Groups = new TreeMap<Double, List<VolSurfacePoint>>();
        for (VolSurfacePoint point : curveData) {
            axis2Groups.computeIfAbsent(point.getAxis2Value(), key -> new ArrayList<VolSurfacePoint>())
                    .add(point);
        }
        for (List<VolSurfacePoint> group : axis2Groups.values()) {
            group.sort(Comparator.comparingInt(VolSurfacePoint::getOptionTerm));
        }
        return axis2Groups;
    }

    private static VolSurfacePoint interpolateTermWithinAxis2(
            Integer days,
            Double axis2,
            List<VolSurfacePoint> axis2Points,
            String axis2InterpolateType) {
        TermBounds bounds = findTermBounds(days, axis2Points);
        if (bounds.exact != null) {
            return retagSinglePoint(bounds.exact, days, axis2InterpolateType);
        }
        if (bounds.lower == null) {
            return retagSinglePoint(bounds.upper, days, axis2InterpolateType);
        }
        if (bounds.upper == null) {
            return retagSinglePoint(bounds.lower, days, axis2InterpolateType);
        }
        double lowerTerm = bounds.lower.getOptionTerm();
        double upperTerm = bounds.upper.getOptionTerm();
        double lowerRate = bounds.lower.getVolatilityRate();
        double upperRate = bounds.upper.getVolatilityRate();
        double alpha = (upperTerm - days) / (upperTerm - lowerTerm);
        double volatility = Math.sqrt((lowerRate * lowerRate * lowerTerm * alpha
                + upperRate * upperRate * upperTerm * (1.0d - alpha)) / days);
        return new VolSurfacePoint(days, axis2, volatility, axis2InterpolateType, false);
    }

    private static VolSurfacePoint interpolateShockTermWithinAxis2(
            Integer days,
            Double axis2,
            List<VolSurfacePoint> axis2Points,
            String axis2InterpolateType) {
        TermBounds bounds = findTermBounds(days, axis2Points);
        if (bounds.exact != null) {
            return retagSinglePoint(bounds.exact, days, axis2InterpolateType);
        }
        if (bounds.lower == null) {
            return retagSinglePoint(bounds.upper, days, axis2InterpolateType);
        }
        if (bounds.upper == null) {
            return retagSinglePoint(bounds.lower, days, axis2InterpolateType);
        }
        double volatility = Interpolation.interpolation(
                (double) bounds.lower.getOptionTerm(),
                bounds.lower.getVolatilityRate(),
                (double) bounds.upper.getOptionTerm(),
                bounds.upper.getVolatilityRate(),
                (double) days);
        return new VolSurfacePoint(days, axis2, volatility, axis2InterpolateType, false);
    }

    private static TermBounds findTermBounds(Integer days, List<VolSurfacePoint> axis2Points) {
        TermBounds bounds = new TermBounds();
        for (VolSurfacePoint point : axis2Points) {
            int term = point.getOptionTerm();
            if (term == days) {
                bounds.exact = point;
                return bounds;
            }
            if (term < days) {
                bounds.lower = point;
            } else {
                bounds.upper = point;
                return bounds;
            }
        }
        return bounds;
    }

    private static VolSurfacePoint retagSinglePoint(
            VolSurfacePoint point,
            Integer days,
            String axis2InterpolateType) {
        return new VolSurfacePoint(
                days,
                point.getAxis2Value(),
                point.getVolatilityRate(),
                axis2InterpolateType,
                point.isShockApplied());
    }

    private static String buildPointKey(VolSurfacePoint point) {
        return point.getOptionTerm() + "|" + point.getAxis2Value();
    }

    private static final class TermBounds {
        private VolSurfacePoint lower;
        private VolSurfacePoint upper;
        private VolSurfacePoint exact;
    }
}
