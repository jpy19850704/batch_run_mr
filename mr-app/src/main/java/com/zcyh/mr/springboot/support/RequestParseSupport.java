package com.zcyh.mr.springboot.support;

import com.alibaba.fastjson2.JSONObject;

/**
 * 请求参数解析工具。
 */
public final class RequestParseSupport {

    private RequestParseSupport() {
    }

    public static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    public static String readString(JSONObject request, String key) {
        if (request == null || key == null) {
            return null;
        }
        return trimToNull(request.getString(key));
    }

    public static String readRequiredString(JSONObject request, String key) {
        String value = readString(request, key);
        if (value == null) {
            throw new IllegalArgumentException("参数缺失: " + key);
        }
        return value;
    }

    public static Boolean readBoolean(JSONObject request, String key) {
        if (request == null || key == null) {
            return null;
        }
        Object raw = request.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        String text = trimToNull(String.valueOf(raw));
        if (text == null) {
            throw new IllegalArgumentException("布尔参数不能为空: " + key);
        }
        if ("true".equalsIgnoreCase(text) || "1".equals(text) || "y".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text) || "n".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalArgumentException("布尔参数非法: " + key + "=" + text);
    }

    public static boolean readBoolean(JSONObject request, boolean defaultValue, String key) {
        Boolean value = readBoolean(request, key);
        return value == null ? defaultValue : value;
    }

    public static Integer readInteger(JSONObject request, String key) {
        if (request == null || key == null || !request.containsKey(key)) {
            return null;
        }
        return request.getInteger(key);
    }

    public static int readInteger(JSONObject request, int defaultValue, String key) {
        Integer value = readInteger(request, key);
        return value == null ? defaultValue : value;
    }
}
