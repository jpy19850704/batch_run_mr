package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.EngineRunResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 构建 MR_CALC 结果落库所需的公共上下文。
 */
@Service
public class CalcPersistContextFactory {
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_10_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    public CalcPersistContext build(String requestId, String jobId, String payloadJson, EngineRunResult runResult) {
        JSONObject root = toJsonObject(runResult == null ? null : runResult.getData());
        if (root == null) {
            return null;
        }
        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return null;
        }

        CalcPersistContext context = new CalcPersistContext();
        context.requestId = trimToNull(requestId);
        context.jobId = trimToNull(jobId);
        context.createdAt = ResultPersistTime.nowText();
        context.updatedAt = context.createdAt;
        context.payload = parseObjectSafely(payloadJson);
        context.resultData = data;

        if (context.payload != null) {
            context.dataDate = normalizeDataDate(context.payload.getString("data_date"));
            JSONObject batchMeta = context.payload.getJSONObject("batch_meta");
            if (batchMeta != null) {
                context.batchId = trimToNull(batchMeta.getString("batch_id"));
                if (batchMeta.get("seq_no") != null) {
                    context.seqNo = batchMeta.getLong("seq_no");
                }
            }
            context.tradeDimension = context.payload.getJSONObject("trade_dimension");
            context.tradeRrao = context.payload.getJSONObject("trade_rrao");
            context.inputMarketData = context.payload.getJSONArray("market_data");
        }

        JSONArray baseTrades = data.getJSONArray("trade_data");
        JSONArray logData = data.getJSONArray("log_data");
        context.generatedMarketData = data.getJSONArray("generated_market_data");
        context.scenarioResults = data.getJSONArray("scenario_result");
        context.imaModellableScenarioResults = data.getJSONArray("ima_modellable_scenario_result");
        context.inputTradeIndex = buildInputTradeIndex(context.payload);
        context.effectiveBaseTrades = appendMissingErrorTradesFromLog(baseTrades, logData,
                context.inputTradeIndex, context);
        context.baseTradeIndex = buildTradeIndex(context.effectiveBaseTrades);
        return context;
    }

    private static JSONArray appendMissingErrorTradesFromLog(JSONArray baseTrades, JSONArray logData,
                                                             Map<String, JSONObject> inputTradeIndex,
                                                             CalcPersistContext context) {
        JSONArray result = new JSONArray();
        Set<String> existedInstrumentIds = new LinkedHashSet<String>();
        if (baseTrades != null) {
            for (int i = 0; i < baseTrades.size(); i++) {
                JSONObject trade = baseTrades.getJSONObject(i);
                if (trade == null) {
                    continue;
                }
                result.add(trade);
                String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
                if (instrumentId != null) {
                    existedInstrumentIds.add(instrumentId);
                }
            }
        }
        if ((logData == null || logData.isEmpty()) && (inputTradeIndex == null || inputTradeIndex.isEmpty())) {
            return result;
        }

        LinkedHashMap<String, JSONObject> missingErrorTrades = new LinkedHashMap<String, JSONObject>();
        if (logData != null) {
            for (int i = 0; i < logData.size(); i++) {
                JSONObject logItem = logData.getJSONObject(i);
                if (logItem == null) {
                    continue;
                }
                String instrumentId = trimToNull(logItem.getString("INSTRUMENT_ID"));
                if (instrumentId == null || existedInstrumentIds.contains(instrumentId)) {
                    continue;
                }
                String message = resolveLogMessage(logItem);
                if (message == null) {
                    message = "计算异常";
                }

                JSONObject errorTrade = missingErrorTrades.get(instrumentId);
                if (errorTrade == null) {
                    JSONObject inputTrade = inputTradeIndex == null ? null : inputTradeIndex.get(instrumentId);
                    errorTrade = buildSyntheticErrorTrade(instrumentId,
                            trimToNull(logItem.getString("PRODUCT_CODE")),
                            inputTrade,
                            context);
                    missingErrorTrades.put(instrumentId, errorTrade);
                }
                appendTradeLog(errorTrade, "ERROR", message);
            }
        }
        if (inputTradeIndex != null) {
            for (Map.Entry<String, JSONObject> entry : inputTradeIndex.entrySet()) {
                String instrumentId = trimToNull(entry.getKey());
                if (instrumentId == null || existedInstrumentIds.contains(instrumentId)
                        || missingErrorTrades.containsKey(instrumentId)) {
                    continue;
                }
                JSONObject errorTrade = buildSyntheticErrorTrade(instrumentId, null, entry.getValue(), context);
                appendTradeLog(errorTrade, "ERROR", "输入交易未生成计量结果");
                missingErrorTrades.put(instrumentId, errorTrade);
            }
        }

        for (JSONObject errorTrade : missingErrorTrades.values()) {
            result.add(errorTrade);
        }
        return result;
    }

    private static JSONObject buildSyntheticErrorTrade(String instrumentId, String productCode,
                                                       JSONObject inputTrade, CalcPersistContext context) {
        JSONObject errorTrade = new JSONObject();
        errorTrade.put("INSTRUMENT_ID", instrumentId);
        errorTrade.put("PRODUCT_CODE", productCode != null ? productCode
                : inputTrade == null ? null : trimToNull(inputTrade.getString("PRODUCT_CODE")));
        errorTrade.put("DATA_DATE", context == null ? null : context.dataDate);
        errorTrade.put("STATUS", "ERROR");
        errorTrade.put(CalcPersistContext.SYNTHETIC_ERROR_TRADE_FLAG, true);
        errorTrade.put("LOGS", new JSONArray());
        return errorTrade;
    }

    private static String resolveLogMessage(JSONObject logItem) {
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

    private static void appendTradeLog(JSONObject errorTrade, String level, String message) {
        if (errorTrade == null) {
            return;
        }
        String safeMessage = trimToNull(message);
        if (safeMessage == null) {
            return;
        }
        JSONArray logs = errorTrade.getJSONArray("LOGS");
        if (logs == null) {
            logs = new JSONArray();
            errorTrade.put("LOGS", logs);
        }
        String safeLevel = trimToNull(level) == null ? "ERROR" : level;
        for (int i = 0; i < logs.size(); i++) {
            JSONObject item = logs.getJSONObject(i);
            if (item != null
                    && safeMessage.equals(String.valueOf(item.get("message")))
                    && safeLevel.equalsIgnoreCase(String.valueOf(item.get("level")))) {
                return;
            }
        }
        JSONObject log = new JSONObject();
        log.put("level", safeLevel);
        log.put("message", safeMessage);
        logs.add(log);
    }

    private static Map<String, JSONObject> buildTradeIndex(JSONArray trades) {
        Map<String, JSONObject> index = new LinkedHashMap<String, JSONObject>();
        if (trades == null) {
            return index;
        }
        for (int i = 0; i < trades.size(); i++) {
            JSONObject trade = trades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            if (instrumentId != null) {
                index.put(instrumentId, trade);
            }
        }
        return index;
    }

    private static Map<String, JSONObject> buildInputTradeIndex(JSONObject payload) {
        Map<String, JSONObject> index = new LinkedHashMap<String, JSONObject>();
        if (payload == null) {
            return index;
        }
        JSONArray inputTrades = payload.getJSONArray("trade_data");
        if (inputTrades == null) {
            return index;
        }
        for (int i = 0; i < inputTrades.size(); i++) {
            JSONObject trade = inputTrades.getJSONObject(i);
            if (trade == null) {
                continue;
            }
            String instrumentId = trimToNull(trade.getString("INSTRUMENT_ID"));
            if (instrumentId != null) {
                index.put(instrumentId, trade);
            }
        }
        return index;
    }

    private static JSONObject toJsonObject(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof JSONObject) {
            return (JSONObject) obj;
        }
        return parseObjectSafely(JSON.toJSONString(obj, JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    private static JSONObject parseObjectSafely(String text) {
        String safe = trimToNull(text);
        if (safe == null) {
            return null;
        }
        try {
            return JSON.parseObject(safe);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String normalizeDataDate(String dataDateText) {
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

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
