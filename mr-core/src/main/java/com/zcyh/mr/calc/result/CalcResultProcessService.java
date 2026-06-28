package com.zcyh.mr.calc.result;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.loader.Loader;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Calc 结果处理服务：负责统一输出 JSON、日志、基准交易补齐和情景 PnL 组装。
 */
public final class CalcResultProcessService {
    private static final Set<String> TRADE_CALENDAR_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "SETTLE_CALENDAR",
            "FIXING_CALENDAR",
            "PAY_SETTLE_CALENDAR",
            "PAY_FIXING_CALENDAR",
            "REC_SETTLE_CALENDAR",
            "REC_FIXING_CALENDAR",
            "UNDERLYING_SETTLE_CALENDAR"));

    private CalcResultProcessService() {
    }

    public static String buildCurveGenerationOnlyResult(JSONArray generatedMarketData,
                                                        List<String> curveGenerationErrors) {
        JSONObject mergedData = new JSONObject();
        mergedData.put("trade_data", new JSONArray());
        mergedData.put("log_data", new JSONArray());
        appendCurveGenerationOutput(mergedData, generatedMarketData, curveGenerationErrors);

        JSONObject result = new JSONObject();
        result.put("data", mergedData);
        return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    public static void appendCurveGenerationOutput(JSONObject dataObj,
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

    public static JSONArray getOrCreateArray(JSONObject obj, String key) {
        JSONArray arr = obj.getJSONArray(key);
        if (arr == null) {
            arr = new JSONArray();
            obj.put(key, arr);
        }
        return arr;
    }

    public static void addLog(JSONObject mergedData, String productCode,
                              String instrumentId, String info) {
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

    public static void appendEmptyCalendarLogs(JSONObject mergedData,
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

    public static void collectTradeData(String jsonResult, JSONArray target) {
        JSONObject json = JSON.parseObject(jsonResult);
        JSONObject data = json.getJSONObject("data");
        if (data != null) {
            JSONArray trades = data.getJSONArray("trade_data");
            if (trades != null) {
                target.addAll(trades);
            }
        }
    }

    public static void mergeData(JSONObject mergedData, String groupResult, String defaultProductCode) {
        JSONObject groupJson = JSON.parseObject(groupResult);
        JSONObject groupData = groupJson.getJSONObject("data");
        if (groupData != null) {
            for (String key : groupData.keySet()) {
                Object value = groupData.get(key);
                if (value instanceof JSONArray) {
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
    }

    public static JSONArray buildPnlResults(Map<String, JSONObject> baseTradeIndex, JSONArray scenTradeResults) {
        JSONArray pnlResults = new JSONArray();
        if (baseTradeIndex == null || baseTradeIndex.isEmpty()) {
            return pnlResults;
        }

        Map<String, JSONObject> scenTradeIndex = buildTradeIndex(scenTradeResults);
        for (Map.Entry<String, JSONObject> entry : baseTradeIndex.entrySet()) {
            String id = entry.getKey();
            JSONObject baseTrade = entry.getValue();
            if (!hasText(id) || baseTrade == null) {
                continue;
            }

            if (isErrorTrade(baseTrade)) {
                pnlResults.add(buildErrorPnlRow(baseTrade, baseTrade));
                continue;
            }

            JSONObject scenTrade = scenTradeIndex.get(id);
            if (scenTrade == null) {
                pnlResults.add(buildZeroPnlRow(baseTrade));
                continue;
            }

            if (isErrorTrade(scenTrade)) {
                pnlResults.add(buildErrorPnlRow(baseTrade, scenTrade));
                continue;
            }

            double baseValCny = baseTrade.getDoubleValue("VALUATION_CNY");
            double scenValCny = scenTrade.getDoubleValue("VALUATION_CNY");

            JSONObject pnlResult = new JSONObject();
            pnlResult.put("INSTRUMENT_ID", id);
            pnlResult.put("BASE_VALUATION_CNY", baseValCny);
            pnlResult.put("SCENARIO_VALUATION_CNY", scenValCny);
            pnlResult.put("PNL", scenValCny - baseValCny);
            pnlResults.add(pnlResult);
        }
        return pnlResults;
    }

    public static JSONObject buildZeroPnlRow(JSONObject baseTrade) {
        JSONObject pnlResult = new JSONObject();
        double baseValuationCny = baseTrade == null ? 0.0 : baseTrade.getDoubleValue("VALUATION_CNY");
        pnlResult.put("INSTRUMENT_ID", baseTrade == null ? null : baseTrade.getString("INSTRUMENT_ID"));
        pnlResult.put("BASE_VALUATION_CNY", baseValuationCny);
        pnlResult.put("SCENARIO_VALUATION_CNY", baseValuationCny);
        pnlResult.put("PNL", 0.0);
        return pnlResult;
    }

    public static JSONObject buildErrorPnlRow(JSONObject baseTrade, JSONObject errorSource) {
        JSONObject pnlResult = buildZeroPnlRow(baseTrade);
        pnlResult.put("STATUS", "ERROR");
        Object logs = errorSource == null ? null : errorSource.get("LOGS");
        if (logs != null) {
            pnlResult.put("LOGS", logs);
        }
        Object detail = errorSource == null ? null : errorSource.get("DETAIL");
        if (detail != null) {
            pnlResult.put("DETAIL", detail);
        }
        return pnlResult;
    }

    public static boolean isErrorTrade(JSONObject trade) {
        return trade != null && "ERROR".equalsIgnoreCase(Objects.toString(trade.get("STATUS"), ""));
    }

    public static Map<String, JSONObject> buildTradeIndex(JSONArray trades) {
        Map<String, JSONObject> tradeIndex = new LinkedHashMap<>();
        if (trades == null) {
            return tradeIndex;
        }
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trade.getString("INSTRUMENT_ID");
            if (instrumentId != null) {
                tradeIndex.put(instrumentId, trade);
            }
        }
        return tradeIndex;
    }

    public static JSONArray buildEffectiveBaseTrades(
            JSONArray baseTrades,
            JSONArray logData,
            List<HashMap<String, Object>> inputTrades) {
        JSONArray result = new JSONArray();
        Set<String> existedInstrumentIds = new LinkedHashSet<>();
        if (baseTrades != null) {
            for (int i = 0; i < baseTrades.size(); i++) {
                JSONObject trade = baseTrades.getJSONObject(i);
                if (trade == null) {
                    continue;
                }
                result.add(trade);
                String instrumentId = trade.getString("INSTRUMENT_ID");
                if (hasText(instrumentId)) {
                    existedInstrumentIds.add(instrumentId.trim());
                }
            }
        }

        if (logData == null || logData.isEmpty()) {
            return result;
        }

        Map<String, HashMap<String, Object>> inputTradeIndex = buildInputTradeIndex(inputTrades);
        LinkedHashMap<String, JSONObject> missingErrors = new LinkedHashMap<>();
        for (int i = 0; i < logData.size(); i++) {
            JSONObject logItem = logData.getJSONObject(i);
            if (logItem == null) {
                continue;
            }
            String instrumentId = trimToNull(logItem.getString("INSTRUMENT_ID"));
            if (instrumentId == null || existedInstrumentIds.contains(instrumentId)) {
                continue;
            }
            JSONObject errorTrade = missingErrors.get(instrumentId);
            if (errorTrade == null) {
                errorTrade = buildBaseErrorTrade(instrumentId, logItem, inputTradeIndex.get(instrumentId));
                missingErrors.put(instrumentId, errorTrade);
            }
            appendErrorLog(errorTrade, resolveLogMessage(logItem));
        }
        result.addAll(missingErrors.values());
        return result;
    }

    public static Map<String, HashMap<String, Object>> buildInputTradeIndex(List<HashMap<String, Object>> inputTrades) {
        Map<String, HashMap<String, Object>> index = new LinkedHashMap<>();
        if (inputTrades == null) {
            return index;
        }
        for (HashMap<String, Object> trade : inputTrades) {
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(Objects.toString(trade.get("INSTRUMENT_ID"), null));
            if (instrumentId != null) {
                index.put(instrumentId, trade);
            }
        }
        return index;
    }

    public static JSONObject buildBaseErrorTrade(
            String instrumentId,
            JSONObject logItem,
            HashMap<String, Object> inputTrade) {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", instrumentId);
        String productCode = trimToNull(logItem == null ? null : logItem.getString("PRODUCT_CODE"));
        if (productCode == null && inputTrade != null) {
            productCode = trimToNull(Objects.toString(inputTrade.get("PRODUCT_CODE"), null));
        }
        trade.put("PRODUCT_CODE", productCode);
        trade.put("STATUS", "ERROR");
        trade.put("VALUATION_CNY", 0.0);
        trade.put("VALUATION", 0.0);
        trade.put("LOGS", new JSONArray());
        return trade;
    }

    public static void appendErrorLog(JSONObject trade, String message) {
        String safeMessage = trimToNull(message);
        if (trade == null || safeMessage == null) {
            return;
        }
        JSONArray logs = trade.getJSONArray("LOGS");
        if (logs == null) {
            logs = new JSONArray();
            trade.put("LOGS", logs);
        }
        JSONObject logItem = new JSONObject();
        logItem.put("level", "ERROR");
        logItem.put("message", safeMessage);
        logs.add(logItem);
        if (trade.get("DETAIL") == null) {
            trade.put("DETAIL", safeMessage);
        }
    }

    public static String resolveLogMessage(JSONObject logItem) {
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

    public static Set<String> collectUnsupportedScenarioProducts(JSONArray baseTrades, Set<String> scenarioProductCodes) {
        Set<String> unsupported = new LinkedHashSet<>();
        if (baseTrades == null || baseTrades.isEmpty()) {
            return unsupported;
        }

        Set<String> supported = scenarioProductCodes == null
                ? Collections.emptySet()
                : scenarioProductCodes;
        for (int i = 0; i < baseTrades.size(); i++) {
            JSONObject trade = baseTrades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String productCode = Objects.toString(trade.get("PRODUCT_CODE"), "").trim();
            if (productCode.isEmpty()) {
                continue;
            }
            if (!supported.contains(productCode)) {
                unsupported.add(productCode);
            }
        }
        return unsupported;
    }

    public static JSONArray buildZeroPnlResults(JSONArray baseTrades) {
        JSONArray pnlResults = new JSONArray();
        if (baseTrades == null) {
            return pnlResults;
        }
        for (int i = 0; i < baseTrades.size(); i++) {
            JSONObject baseTrade = baseTrades.getJSONObject(i);
            if (baseTrade == null) {
                continue;
            }
            if (isErrorTrade(baseTrade)) {
                pnlResults.add(buildErrorPnlRow(baseTrade, baseTrade));
            } else {
                pnlResults.add(buildZeroPnlRow(baseTrade));
            }
        }
        return pnlResults;
    }

    public static JSONObject buildScenarioItem(Loader.ScenarioEntry entry, JSONArray tradeData, String resultKind) {
        JSONObject item = new JSONObject();
        if (entry != null) {
            Loader.ScenarioEntry.ScenarioProcessMetadata metadata = entry.processMetadata;
            if (hasText(entry.scenarioId)) {
                item.put("SCENARIO_ID", entry.scenarioId);
            }
            if (hasText(entry.subScenarioId)) {
                item.put("SUBSCENARIO_ID", entry.subScenarioId);
            }
            item.put("SCENARIO_NAME", entry.scenarioName);
            if (hasText(entry.scenarioType)) {
                item.put("SCENARIO_TYPE", entry.scenarioType);
            }
            if (metadata != null && hasText(metadata.processType)) {
                item.put("SCENARIO_PROCESS_TYPE", metadata.processType);
            }
            item.put("SCENARIO_TAG", metadata == null || metadata.tag == null ? new JSONObject() : metadata.tag);
            if (metadata != null && hasText(metadata.entryKey)) {
                item.put("SCENARIO_ENTRY_KEY", metadata.entryKey);
            }
            if (metadata != null && hasText(metadata.nmrfRiskFactorId)) {
                item.put("RISK_FACTOR_ID", metadata.nmrfRiskFactorId);
            }
            if (metadata != null && hasText(metadata.nmrfType)) {
                item.put("NMRF_TYPE", metadata.nmrfType);
            }
        } else {
            item.put("SCENARIO_NAME", null);
            item.put("SCENARIO_TAG", new JSONObject());
        }
        if (hasText(resultKind)) {
            item.put("RESULT_KIND", resultKind);
        }
        item.put("trade_data", tradeData == null ? new JSONArray() : tradeData);
        return item;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
