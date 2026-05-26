package com.zcyh.mr.frtbsa.sba.common;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * SBA Delta/Vega 跨桶聚合工具
 */
public final class SbaAggregationUtils {

    private SbaAggregationUtils() {
    }

    public static double cappedSb(double sb, double kb) {
        return Math.max(Math.min(sb, kb), -kb);
    }

    public static double calculateDeltaVegaCapital(List<Map<String, Object>> bcList, String scenario) {
        return calculateDeltaVegaCapital(bcList, scenario, e -> true);
    }

    public static double calculateDeltaVegaCapital(List<Map<String, Object>> bcList, String scenario,
            Predicate<Map<String, Object>> filter) {
        double rawTotal = sumByScenario(bcList, "rslt_bc", scenario, filter);
        if (rawTotal >= 0.0) {
            return Math.sqrt(rawTotal);
        }
        double cappedTotal = sumByScenario(bcList, "rslt_bcc", scenario, filter);
        return Math.sqrt(Math.max(cappedTotal, 0.0));
    }

    public static double rawTotal(List<Map<String, Object>> bcList, String scenario) {
        return sumByScenario(bcList, "rslt_bc", scenario, e -> true);
    }

    private static double sumByScenario(List<Map<String, Object>> bcList, String prefix, String scenario,
            Predicate<Map<String, Object>> filter) {
        return bcList.stream()
                .filter(filter)
                .mapToDouble(e -> getByScenario(e, prefix, scenario))
                .sum();
    }

    private static double getByScenario(Map<String, Object> map, String prefix, String scenario) {
        Object value = map.get(prefix + "_" + scenario);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }
}
