package com.zcyh.mr.frtbsa.sba.core;

import java.util.Map;

/**
 * SBA 场景列读写工具。
 */
public final class SbaScenarioValueSupport {

    private SbaScenarioValueSupport() {
    }

    public static double getDouble(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return 0.0;
        }
        Object value = map.get(key);
        return (value instanceof Number) ? ((Number) value).doubleValue() : 0.0;
    }

    public static double getByScenario(Map<String, Object> map, String prefix, String scenario, double defaultValue) {
        if (map == null || prefix == null || scenario == null) {
            return defaultValue;
        }
        Object value = map.get(prefix + "_" + scenario);
        return (value instanceof Number) ? ((Number) value).doubleValue() : defaultValue;
    }

    public static void putByScenario(Map<String, Object> map, String prefix, String scenario, double value) {
        if (map == null || prefix == null || scenario == null) {
            return;
        }
        map.put(prefix + "_" + scenario, value);
    }
}
