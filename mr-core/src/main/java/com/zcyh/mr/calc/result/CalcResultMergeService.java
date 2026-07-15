package com.zcyh.mr.calc.result;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Calc 基准结果、曲线生成结果和日志合并服务。
 */
public final class CalcResultMergeService {
    private static final Set<String> TRADE_CALENDAR_FIELDS = new LinkedHashSet<String>(Arrays.asList(
            "SETTLE_CALENDAR",
            "FIXING_CALENDAR",
            "PAY_SETTLE_CALENDAR",
            "PAY_FIXING_CALENDAR",
            "REC_SETTLE_CALENDAR",
            "REC_FIXING_CALENDAR",
            "UNDERLYING_SETTLE_CALENDAR"));

    public String buildCurveGenerationOnlyResult(
            JSONArray generatedMarketData,
            List<String> curveGenerationErrors) {
        JSONObject mergedData = new JSONObject();
        mergedData.put("trade_data", new JSONArray());
        mergedData.put("log_data", new JSONArray());
        appendCurveGenerationOutput(mergedData, generatedMarketData, curveGenerationErrors);

        JSONObject result = new JSONObject();
        result.put("data", mergedData);
        return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    public void appendCurveGenerationOutput(
            JSONObject dataObj,
            JSONArray generatedMarketData,
            List<String> curveGenerationErrors) {
        if (dataObj == null) {
            return;
        }
        dataObj.put("generated_market_data",
                generatedMarketData == null ? new JSONArray() : generatedMarketData);
        if (curveGenerationErrors == null || curveGenerationErrors.isEmpty()) {
            return;
        }
        JSONArray logData = getOrCreateArray(dataObj, "log_data");
        for (String error : curveGenerationErrors) {
            JSONObject logItem = new JSONObject();
            logItem.put("PRODUCT_CODE", "CURVE_GENERATION");
            logItem.put("info", "曲线生成失败: " + error);
            logData.add(logItem);
        }
    }

    public JSONArray getOrCreateArray(JSONObject obj, String key) {
        JSONArray array = obj.getJSONArray(key);
        if (array == null) {
            array = new JSONArray();
            obj.put(key, array);
        }
        return array;
    }

    public void addLog(JSONObject mergedData, String productCode, String instrumentId, String info) {
        JSONObject logItem = new JSONObject();
        if (instrumentId != null) {
            logItem.put("INSTRUMENT_ID", instrumentId);
        }
        if (productCode != null) {
            logItem.put("PRODUCT_CODE", productCode);
        }
        logItem.put("info", info);
        getOrCreateArray(mergedData, "log_data").add(logItem);
    }

    public void appendEmptyCalendarLogs(
            JSONObject mergedData,
            List<HashMap<String, Object>> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        for (HashMap<String, Object> tradeData : trades) {
            if (tradeData == null || tradeData.isEmpty()) {
                continue;
            }
            String productCode = Objects.toString(tradeData.get("PRODUCT_CODE"), "");
            String instrumentId = Objects.toString(tradeData.get("INSTRUMENT_ID"), "");
            for (String field : TRADE_CALENDAR_FIELDS) {
                if (!tradeData.containsKey(field)) {
                    continue;
                }
                Object value = tradeData.get(field);
                if (value == null || value.toString().trim().isEmpty()) {
                    addLog(mergedData, productCode, instrumentId,
                            "交易指定的日历为空: " + field);
                }
            }
        }
    }

    public void mergeData(JSONObject mergedData, String groupResult, String defaultProductCode) {
        JSONObject groupJson = JSON.parseObject(groupResult);
        JSONObject groupData = groupJson.getJSONObject("data");
        if (groupData == null) {
            return;
        }
        for (String key : groupData.keySet()) {
            Object value = groupData.get(key);
            if (!(value instanceof JSONArray)) {
                continue;
            }
            if ("trade_data".equals(key)) {
                JSONArray normalized = new JSONArray();
                for (Object item : (JSONArray) value) {
                    if (item instanceof JSONObject) {
                        JSONObject trade = (JSONObject) item;
                        String productCode = trade.getString("PRODUCT_CODE");
                        if (productCode == null || productCode.trim().isEmpty()) {
                            trade.put("PRODUCT_CODE", defaultProductCode);
                        }
                    }
                    normalized.add(item);
                }
                getOrCreateArray(mergedData, key).addAll(normalized);
            } else {
                getOrCreateArray(mergedData, key).addAll((JSONArray) value);
            }
        }
    }
}
