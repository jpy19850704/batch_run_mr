package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.math.Interpolation;

/**
 * 计量市场数据输入到领域模型的映射支持。
 */
final class MarketDataInputMapper {
    private MarketDataInputMapper() {
    }

    static <T> T parseCurveMeta(JSONObject marketJson, Class<T> targetType) {
        JSONObject metadata = new JSONObject();
        metadata.putAll(marketJson);
        metadata.remove("CURVE_DATA");
        return JSONObject.parseObject(metadata.toString(), targetType);
    }

    static String normalizeInterpolateType(
            String value,
            Interpolation.Type defaultType,
            String fieldName) {
        if (isBlank(value)) {
            return defaultType.name();
        }
        String normalized = value.trim();
        if (!Interpolation.isSupportedType(normalized)) {
            throw new IllegalArgumentException("不支持的 " + fieldName + ": " + value);
        }
        return normalized;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
