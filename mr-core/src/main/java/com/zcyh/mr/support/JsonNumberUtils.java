package com.zcyh.mr.support;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class JsonNumberUtils {
    private static final int DEFAULT_MAX_SCALE = 10;

    private JsonNumberUtils() {
    }

    public static void normalizeNumbersInPlace(Object root) {
        if (root instanceof JSONObject) {
            JSONObject object = (JSONObject) root;
            List<String> keys = new ArrayList<>(object.keySet());
            for (String key : keys) {
                object.put(key, normalizeValue(object.get(key)));
            }
            return;
        }
        if (root instanceof JSONArray) {
            JSONArray array = (JSONArray) root;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, normalizeValue(array.get(i)));
            }
        }
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof JSONObject || value instanceof JSONArray) {
            normalizeNumbersInPlace(value);
            return value;
        }
        if (value instanceof BigDecimal) {
            return toPlainRoundedBigDecimal((BigDecimal) value);
        }
        if (value instanceof Float || value instanceof Double) {
            return toPlainRoundedBigDecimal(BigDecimal.valueOf(((Number) value).doubleValue()));
        }
        return value;
    }

    private static BigDecimal toPlainRoundedBigDecimal(BigDecimal value) {
        BigDecimal rounded = value.setScale(DEFAULT_MAX_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.scale() < 0) {
            rounded = rounded.setScale(0, RoundingMode.UNNECESSARY);
        }
        return new BigDecimal(rounded.toPlainString());
    }
}
