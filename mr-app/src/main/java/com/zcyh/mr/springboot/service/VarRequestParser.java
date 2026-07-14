package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.var.VarMeasure;
import com.zcyh.mr.var.VarPnlColumns;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * VaR 请求字段解析与校验器。
 */
final class VarRequestParser {
    private VarRequestParser() {
    }

    static List<BigDecimal> parseQuantiles(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("quantiles 必填, 例如: 0.95,0.99");
        }
        List<BigDecimal> values = new ArrayList<>();
        if (value instanceof JSONArray) {
            for (Object item : (JSONArray) value) {
                values.add(parseSingleQuantile(item));
            }
        } else {
            String text = requireText(value, "quantiles 必填, 例如: 0.95,0.99");
            for (String item : text.split(",")) {
                values.add(parseSingleQuantile(item));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("quantiles 必填, 例如: 0.95,0.99");
        }
        List<BigDecimal> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BigDecimal quantile : values) {
            if (seen.add(quantile.stripTrailingZeros().toPlainString())) {
                result.add(quantile);
            }
        }
        return result;
    }

    static List<VarMeasure> parseMeasures(Object value) {
        List<VarMeasure> result = new ArrayList<>();
        if (value instanceof JSONArray) {
            for (Object item : (JSONArray) value) {
                addMeasure(result, item == null ? null : String.valueOf(item));
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                addMeasure(result, item == null ? null : String.valueOf(item));
            }
        } else {
            String text = trimToNull(value == null ? null : String.valueOf(value));
            if (text != null) {
                for (String item : text.split(",")) {
                    addMeasure(result, item);
                }
            }
        }
        if (result.isEmpty()) {
            result.addAll(VarMeasure.defaultMeasures());
        }
        return result;
    }

    static String parseVarPick(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return "average";
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if ("in".equals(normalized) || "out".equals(normalized) || "average".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("var_pick 仅支持 in/out/average，实际: " + value);
    }

    static boolean isRiskClassDecomp(String decompType, String riskClass) {
        return "risk_class".equalsIgnoreCase(trimToNull(decompType)) && trimToNull(riskClass) != null;
    }

    static List<String> parseRiskClassesOptional(String value) {
        List<String> result = new ArrayList<>();
        String text = trimToNull(value);
        if (text == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String item : text.split(",")) {
            String token = trimToNull(item);
            if (token == null) {
                continue;
            }
            String normalized = VarPnlColumns.normalizeRiskClassToken(token);
            VarPnlColumns.riskClassToPnlColumn(normalized);
            if (seen.add(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    static String requireTopLevelString(JSONObject object, String key) {
        return requireText(object == null ? null : object.getString(key), key + " 必填");
    }

    static String requireString(JSONObject object, String key) {
        return requireText(readString(object, key), (key == null ? "field" : key) + " 必填");
    }

    static String readString(JSONObject object, String key) {
        return object == null || key == null ? null : trimToNull(object.getString(key));
    }

    static List<String> readStringList(JSONObject object, String key) {
        List<String> result = new ArrayList<>();
        JSONArray array = object == null || key == null ? null : object.getJSONArray(key);
        if (array == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : array) {
            String text = trimToNull(item == null ? null : String.valueOf(item));
            if (text != null && seen.add(text)) {
                result.add(text);
            }
        }
        return result;
    }

    static Integer readInteger(JSONObject object, String key) {
        return object == null || key == null ? null : object.getInteger(key);
    }

    static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    static String asTrimmedString(Object value) {
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal parseSingleQuantile(Object value) {
        String text = requireText(value, "quantiles 中包含空值");
        try {
            BigDecimal quantile = new BigDecimal(text);
            if (quantile.compareTo(BigDecimal.ZERO) <= 0 || quantile.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("quantile 必须在 (0,1) 区间: " + text);
            }
            return quantile;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("quantile 格式非法: " + text);
        }
    }

    private static String requireText(Object value, String message) {
        String text = trimToNull(value == null ? null : String.valueOf(value));
        if (text == null) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private static void addMeasure(List<VarMeasure> target, String value) {
        String text = trimToNull(value);
        if (text != null) {
            VarMeasure measure = VarMeasure.parse(text);
            if (!target.contains(measure)) {
                target.add(measure);
            }
        }
    }
}
