package com.zcyh.mr.marketdata;

import java.util.Locale;

public enum VolAxisType {
    DELTA,
    MONEYNESS,
    STRIKE,
    UNDERLYING_TERM,
    NONE;

    public static VolAxisType parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("AXIS2_TYPE不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的AXIS2_TYPE: " + value, ex);
        }
    }

    public String fieldName() {
        return this == NONE ? null : name();
    }
}
