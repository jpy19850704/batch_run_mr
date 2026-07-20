package com.zcyh.mr.calc.result;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Calc 基准结果、曲线生成结果和日志合并服务。
 */
public final class CalcResultMergeService {
    private static final Logger log = LoggerFactory.getLogger(CalcResultMergeService.class);
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
        for (String error : curveGenerationErrors) {
            log.error("曲线生成失败: {}", error);
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

    public void appendEmptyCalendarLogs(
            JSONObject mergedData,
            List<HashMap<String, Object>> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        Map<String, JSONObject> resultIndex = new HashMap<String, JSONObject>();
        JSONArray results = getOrCreateArray(mergedData, "trade_data");
        for (int i = 0; i < results.size(); i++) {
            JSONObject result = results.getJSONObject(i);
            String instrumentId = result == null ? null : result.getString("INSTRUMENT_ID");
            if (instrumentId != null && !instrumentId.trim().isEmpty()) {
                resultIndex.put(instrumentId, result);
            }
        }
        for (HashMap<String, Object> tradeData : trades) {
            if (tradeData == null || tradeData.isEmpty()) {
                continue;
            }
            String instrumentId = Objects.toString(tradeData.get("INSTRUMENT_ID"), "");
            for (String field : TRADE_CALENDAR_FIELDS) {
                if (!tradeData.containsKey(field)) {
                    continue;
                }
                Object value = tradeData.get(field);
                if (value == null || value.toString().trim().isEmpty()) {
                    JSONObject result = resultIndex.get(instrumentId);
                    if (result == null) {
                        log.warn("交易指定的日历为空且未找到交易结果: instrumentId={}, field={}", instrumentId, field);
                    } else {
                        appendResultLog(result, "WARNING", "交易指定的日历为空: " + field);
                    }
                }
            }
        }
    }

    private static void appendResultLog(JSONObject result, String level, String message) {
        JSONArray logs = result.getJSONArray("LOGS_JSON");
        if (logs == null) {
            logs = new JSONArray();
            result.put("LOGS_JSON", logs);
        }
        JSONObject item = new JSONObject();
        item.put("level", level);
        item.put("message", message);
        logs.add(item);
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
