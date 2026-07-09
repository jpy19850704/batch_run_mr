package com.zcyh.mr.springboot.out.db;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_ERROR = "ERROR";
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

    static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    static boolean isErrorStatus(JSONObject row) {
        return STATUS_ERROR.equals(resolveStatus(row));
    }

    static String resolveStatus(JSONObject row) {
        String status = trimToNull(row == null ? null : row.getString("STATUS"));
        return STATUS_ERROR.equalsIgnoreCase(status) ? STATUS_ERROR : STATUS_SUCCESS;
    }

    static Object resolveLogs(JSONObject row, String defaultMessage) {
        if (row == null) {
            return null;
        }
        Object logs = row.get("LOGS");
        if (!isErrorStatus(row)) {
            return logs;
        }
        if (logs instanceof JSONArray && !((JSONArray) logs).isEmpty()) {
            return logs;
        }
        String message = resolveErrorMessage(row);
        JSONArray result = new JSONArray();
        JSONObject logItem = new JSONObject();
        logItem.put("level", STATUS_ERROR);
        logItem.put("message", message == null ? defaultMessage : message);
        result.add(logItem);
        return result;
    }

    static void appendLogs(JSONObject row, Object sourceLogs) {
        if (!(sourceLogs instanceof JSONArray) || ((JSONArray) sourceLogs).isEmpty()) {
            return;
        }
        JSONArray logs = row.getJSONArray("LOGS");
        if (logs == null) {
            logs = new JSONArray();
            row.put("LOGS", logs);
        }
        logs.addAll((JSONArray) sourceLogs);
    }

    static String resolveErrorMessage(JSONObject row) {
        String message = trimToNull(row == null ? null : row.getString("ERROR"));
        if (message != null) {
            return message;
        }
        message = toTextValue(row == null ? null : row.get("DETAIL"));
        if (message != null) {
            return message;
        }
        JSONArray logs = row == null ? null : row.getJSONArray("LOGS");
        if (logs == null) {
            return null;
        }
        for (int i = 0; i < logs.size(); i++) {
            message = resolveLogMessage(logs.getJSONObject(i));
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    static String resolveLogMessage(JSONObject logItem) {
        if (logItem == null) {
            return null;
        }
        String message = trimToNull(logItem.getString("info"));
        if (message != null) {
            return message;
        }
        message = trimToNull(logItem.getString("ERROR"));
        if (message != null) {
            return message;
        }
        return trimToNull(logItem.getString("message"));
    }
}
