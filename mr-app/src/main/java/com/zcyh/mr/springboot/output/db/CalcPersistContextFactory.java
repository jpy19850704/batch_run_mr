package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.ResultPersistTime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.springboot.execution.MeasurementExecutionResult;
import com.zcyh.mr.springboot.support.ResultDbDateSupport;
import org.springframework.stereotype.Service;

import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.STATUS_ERROR;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 构建 MR_CALC 结果落库所需的公共上下文。
 */
@Service
public class CalcPersistContextFactory {
    public CalcPersistContext build(String requestId, String jobId, String payloadJson, MeasurementExecutionResult runResult) {
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
        context.createdAt = ResultPersistTime.now();
        context.updatedAt = context.createdAt;
        context.payload = parseObjectSafely(payloadJson);
        context.resultData = data;

        if (context.payload != null) {
            context.dataDate = ResultDbDateSupport.localDate(context.payload.getString("data_date"));
            JSONObject batchMeta = context.payload.getJSONObject("batch_meta");
            if (batchMeta != null) {
                context.batchId = trimToNull(batchMeta.getString("batch_id"));
                if (batchMeta.get("seq_no") != null) {
                    context.seqNo = batchMeta.getLong("seq_no");
                }
            }
            context.tradeDimension = context.payload.getJSONObject("trade_dimension");
            context.tradeRrao = context.payload.getJSONObject("trade_rrao");
        }

        JSONArray baseTrades = data.getJSONArray("trade_data");
        context.scenarioResults = data.getJSONArray("scenario_result");
        if (hasScenarioRequest(context.payload) && (context.scenarioResults == null || context.scenarioResults.isEmpty())) {
            throw new IllegalStateException("情景请求未生成 scenario_result");
        }
        context.imaModellableScenarioResults = data.getJSONArray("ima_modellable_scenario_result");
        context.inputTradeIndex = buildInputTradeIndex(context.payload);
        context.effectiveBaseTrades = appendMissingErrorTrades(
                baseTrades, context.inputTradeIndex, context);
        context.baseTradeIndex = buildTradeIndex(context.effectiveBaseTrades);
        return context;
    }

    private static boolean hasScenarioRequest(JSONObject payload) {
        return hasArray(payload, ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.VAR_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST);
    }

    private static boolean hasArray(JSONObject payload, String key) {
        JSONArray array = payload == null ? null : payload.getJSONArray(key);
        return array != null && !array.isEmpty();
    }

    private static JSONArray appendMissingErrorTrades(JSONArray baseTrades,
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
        if (inputTradeIndex == null || inputTradeIndex.isEmpty()) {
            return result;
        }

        LinkedHashMap<String, JSONObject> missingErrorTrades = new LinkedHashMap<String, JSONObject>();
        for (Map.Entry<String, JSONObject> entry : inputTradeIndex.entrySet()) {
            String instrumentId = trimToNull(entry.getKey());
            if (instrumentId == null || existedInstrumentIds.contains(instrumentId)) {
                continue;
            }
            JSONObject errorTrade = buildSyntheticErrorTrade(instrumentId, null, entry.getValue(), context);
            appendTradeLog(errorTrade, STATUS_ERROR, "输入交易未生成计量结果");
            missingErrorTrades.put(instrumentId, errorTrade);
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
        errorTrade.put("DATA_DATE", context == null ? null : ResultDbDateSupport.protocolDate(context.dataDate));
        errorTrade.put("STATUS", STATUS_ERROR);
        errorTrade.put("LOGS_JSON", new JSONArray());
        return errorTrade;
    }

    private static void appendTradeLog(JSONObject errorTrade, String level, String message) {
        if (errorTrade == null) {
            return;
        }
        String safeMessage = trimToNull(message);
        if (safeMessage == null) {
            return;
        }
        JSONArray logs = errorTrade.getJSONArray("LOGS_JSON");
        if (logs == null) {
            logs = new JSONArray();
            errorTrade.put("LOGS_JSON", logs);
        }
        String safeLevel = trimToNull(level) == null ? STATUS_ERROR : level;
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
                JSONObject persistedInput = new JSONObject(trade);
                persistedInput.remove(EngineConstants.CONTROL_FIELD.INPUT_ERROR);
                index.put(instrumentId, persistedInput);
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

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
