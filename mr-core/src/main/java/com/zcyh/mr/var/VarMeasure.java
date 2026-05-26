package com.zcyh.mr.var;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VaR 计量指标。
 */
public enum VarMeasure {
    VAR("VAR"),
    ES("ES"),
    COMPONENT_VAR("COMPONENT_VAR"),
    MARGINAL_VAR("MARGINAL_VAR"),
    INCREMENTAL_VAR("INCREMENTAL_VAR");

    private final String code;

    VarMeasure(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static VarMeasure parse(String rawMeasure) {
        String safe = trimToNull(rawMeasure);
        if (safe == null) {
            throw new IllegalArgumentException("measure 不能为空");
        }
        String upper = safe.toUpperCase(Locale.ROOT);
        for (VarMeasure measure : values()) {
            if (measure.code.equals(upper)) {
                return measure;
            }
        }
        throw new IllegalArgumentException("不支持的 measure 取值: " + rawMeasure);
    }

    public static List<VarMeasure> defaultMeasures() {
        List<VarMeasure> measures = new ArrayList<VarMeasure>();
        measures.add(VAR);
        measures.add(ES);
        return measures;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
