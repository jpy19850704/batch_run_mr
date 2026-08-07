package com.zcyh.mr.marketdata.input;

import java.util.Locale;

public enum MarketDataType {
    IR_SPOT,
    CREDIT_SPOT,
    FX_SPOT,
    EQ_SPOT,
    COMM_SPOT,
    FIXING,
    IR_VOL,
    FX_VOL,
    EQ_VOL,
    COMM_VOL;

    public static MarketDataType parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("CURVE_TYPE不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的CURVE_TYPE: " + value, ex);
        }
    }
}
