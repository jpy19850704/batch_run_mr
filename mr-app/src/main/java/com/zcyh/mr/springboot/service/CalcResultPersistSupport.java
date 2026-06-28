package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * MR_CALC 结果落库公共转换工具。
 */
final class CalcResultPersistSupport {
    static final int DEFAULT_BATCH_SIZE = 20000;
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_10_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private CalcResultPersistSupport() {
    }

    static String normalizeDataDate(String dataDateText) {
        String text = trimToNull(dataDateText);
        if (text == null) {
            return null;
        }
        try {
            if (text.length() == 8) {
                return LocalDate.parse(text, DATE_8_FORMATTER).format(DATE_8_FORMATTER);
            }
            if (text.length() == 10) {
                return LocalDate.parse(text, DATE_10_FORMATTER).format(DATE_8_FORMATTER);
            }
        } catch (DateTimeParseException ex) {
            return text;
        }
        return text;
    }

    static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    static String toTextValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence) {
            return trimToNull(String.valueOf(value));
        }
        return toJsonString(value);
    }

    static String toJsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence) {
            String text = trimToNull(String.valueOf(value));
            if (text == null) {
                return null;
            }
            JSON.parse(text);
            return text;
        }
        if (value instanceof JSONObject && ((JSONObject) value).isEmpty()) {
            return null;
        }
        return JSON.toJSONString(value, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Integer) {
                return (Integer) value;
            }
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    static boolean isSyntheticErrorTrade(JSONObject trade) {
        return trade != null && Boolean.TRUE.equals(trade.getBoolean(CalcPersistContext.SYNTHETIC_ERROR_TRADE_FLAG));
    }

    static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
