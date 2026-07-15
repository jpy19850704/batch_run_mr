package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 计量市场数据校验结果收集器。
 */
final class MarketDataValidationCollector {
    private final JSONArray validationErrors;

    MarketDataValidationCollector(JSONArray validationErrors) {
        this.validationErrors = validationErrors == null ? new JSONArray() : validationErrors;
    }

    void error(String curveType, String curveId, String message) {
        add(curveType, curveId, message, "ERROR");
    }

    void warning(String curveType, String curveId, String message) {
        add(curveType, curveId, message, "WARNING");
    }

    private void add(String curveType, String curveId, String message, String level) {
        JSONObject errLog = new JSONObject();
        errLog.put("level", level);
        errLog.put("CURVE_TYPE", curveType);
        if (curveId != null && !curveId.isEmpty()) {
            errLog.put("CURVE_ID", curveId);
        }
        errLog.put("info", "市场数据校验: " + message);
        validationErrors.add(errLog);
    }
}
