package com.zcyh.mr.springboot.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * market_input 单条市场曲线解析结果。
 */
final class ScenarioMarketCurveRow {
    private final String curveType;
    private final String curveId;
    private final LocalDate dataDate;
    private final String contentText;
    private final boolean curveDataPresent;
    private final List<JSONObject> curveData;
    private final RuntimeException parseException;

    private ScenarioMarketCurveRow(Map<String, Object> row) {
        this.curveType = normalize(toStringValue(row.get("MARKET_DATA_TYPE")));
        this.curveId = normalize(toStringValue(row.get("CURVE_ID")));
        this.dataDate = toLocalDate(row.get("DATA_DATE"));
        this.contentText = toStringValue(row.get("CURVE_CONTENT_TEXT"));

        boolean parsedCurveDataPresent = false;
        List<JSONObject> parsedCurveData = Collections.emptyList();
        RuntimeException failure = null;
        if (contentText != null) {
            try {
                JSONObject root = JSONObject.parseObject(contentText);
                if (root != null && root.containsKey("CURVE_DATA")) {
                    Object value = root.get("CURVE_DATA");
                    if (value != null && !(value instanceof JSONArray)) {
                        throw new IllegalArgumentException("CURVE_DATA 必须为数组");
                    }
                    if (value instanceof JSONArray) {
                        parsedCurveDataPresent = true;
                        parsedCurveData = readCurveData((JSONArray) value);
                    }
                }
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        this.curveDataPresent = parsedCurveDataPresent;
        this.curveData = parsedCurveData;
        this.parseException = failure;
    }

    static ScenarioMarketCurveRow parse(Map<String, Object> row) {
        if (row == null) {
            return new ScenarioMarketCurveRow(Collections.<String, Object>emptyMap());
        }
        return new ScenarioMarketCurveRow(row);
    }

    static List<ScenarioMarketCurveRow> parseAll(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<ScenarioMarketCurveRow> result = new ArrayList<ScenarioMarketCurveRow>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(parse(row));
        }
        return result;
    }

    String getCurveType() {
        return curveType;
    }

    String getCurveId() {
        return curveId;
    }

    LocalDate getDataDate() {
        return dataDate;
    }

    String getContentText() {
        return contentText;
    }

    boolean isCurveDataPresent() {
        return curveDataPresent;
    }

    List<JSONObject> getCurveData() {
        return curveData;
    }

    RuntimeException getParseException() {
        return parseException;
    }

    private static List<JSONObject> readCurveData(JSONArray curveData) {
        List<JSONObject> points = new ArrayList<JSONObject>(curveData.size());
        for (int i = 0; i < curveData.size(); i++) {
            points.add(curveData.getJSONObject(i));
        }
        return Collections.unmodifiableList(points);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof Date) {
            return ((Date) value).toLocalDate();
        }
        if (value instanceof java.util.Date) {
            return new Date(((java.util.Date) value).getTime()).toLocalDate();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String toStringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
