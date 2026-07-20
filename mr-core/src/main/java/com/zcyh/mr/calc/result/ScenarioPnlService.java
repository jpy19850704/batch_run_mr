package com.zcyh.mr.calc.result;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Calc 情景 PnL 和基准交易补齐服务。
 */
public final class ScenarioPnlService {

    public JSONArray buildPnlResults(
            Map<String, JSONObject> baseTradeIndex,
            JSONArray scenarioTradeResults,
            Set<String> unsupportedScenarioProducts,
            Set<String> affectedTradeIds) {
        JSONArray pnlResults = new JSONArray();
        if (baseTradeIndex == null || baseTradeIndex.isEmpty()) {
            return pnlResults;
        }

        Map<String, JSONObject> scenarioTradeIndex = buildTradeIndex(scenarioTradeResults);
        Set<String> unsupportedProducts = unsupportedScenarioProducts == null
                ? Collections.<String>emptySet() : unsupportedScenarioProducts;
        for (Map.Entry<String, JSONObject> entry : baseTradeIndex.entrySet()) {
            String instrumentId = entry.getKey();
            JSONObject baseTrade = entry.getValue();
            if (!hasText(instrumentId) || baseTrade == null) {
                continue;
            }
            if (isErrorTrade(baseTrade)) {
                pnlResults.add(buildBaseErrorPnlRow(baseTrade));
                continue;
            }

            String productCode = trimToNull(baseTrade.getString("PRODUCT_CODE"));
            if (productCode != null && unsupportedProducts.contains(productCode)) {
                pnlResults.add(buildUnsupportedScenarioPnlRow(baseTrade, productCode));
                continue;
            }

            JSONObject scenarioTrade = scenarioTradeIndex.get(instrumentId);
            if (scenarioTrade == null) {
                pnlResults.add(isAffectedTrade(affectedTradeIds, instrumentId)
                        ? buildMissingScenarioResultPnlRow(baseTrade) : buildZeroPnlRow(baseTrade));
                continue;
            }
            if (isErrorTrade(scenarioTrade)) {
                pnlResults.add(buildScenarioErrorPnlRow(baseTrade, scenarioTrade));
                continue;
            }

            double baseValuationCny = baseTrade.getDoubleValue("VALUATION_CNY");
            double scenarioValuationCny = scenarioTrade.getDoubleValue("VALUATION_CNY");
            JSONObject pnlResult = new JSONObject();
            pnlResult.put("INSTRUMENT_ID", instrumentId);
            pnlResult.put("BASE_VALUATION_CNY", baseValuationCny);
            pnlResult.put("SCENARIO_VALUATION_CNY", scenarioValuationCny);
            pnlResult.put("PNL", scenarioValuationCny - baseValuationCny);
            pnlResult.put("STATUS", "SUCCESS");
            pnlResults.add(pnlResult);
        }
        return pnlResults;
    }

    public JSONObject buildBaseErrorPnlRow(JSONObject baseTrade) {
        JSONObject pnlResult = buildAbsoluteZeroPnlRow(baseTrade);
        pnlResult.put("STATUS", "ERROR");
        pnlResult.put("LOGS_JSON", buildSingleErrorLog("基准估值错误"));
        return pnlResult;
    }

    public JSONObject buildScenarioErrorPnlRow(JSONObject baseTrade, JSONObject errorSource) {
        JSONObject pnlResult = buildZeroPnlRow(baseTrade);
        pnlResult.put("STATUS", "ERROR");
        pnlResult.put("LOGS_JSON", resolveErrorLogs(errorSource, "情景估值错误"));
        return pnlResult;
    }

    public Map<String, JSONObject> buildTradeIndex(JSONArray trades) {
        Map<String, JSONObject> tradeIndex = new LinkedHashMap<String, JSONObject>();
        if (trades == null) {
            return tradeIndex;
        }
        for (int index = 0; index < trades.size(); index++) {
            JSONObject trade = trades.getJSONObject(index);
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

    public JSONArray buildEffectiveBaseTrades(
            JSONArray baseTrades) {
        JSONArray result = new JSONArray();
        if (baseTrades != null) {
            for (int index = 0; index < baseTrades.size(); index++) {
                JSONObject trade = baseTrades.getJSONObject(index);
                if (trade == null) {
                    continue;
                }
                result.add(trade);
            }
        }
        return result;
    }

    public Set<String> collectUnsupportedScenarioProducts(
            JSONArray baseTrades,
            Set<String> scenarioProductCodes) {
        Set<String> unsupported = new LinkedHashSet<String>();
        if (baseTrades == null || baseTrades.isEmpty()) {
            return unsupported;
        }
        Set<String> supported = scenarioProductCodes == null
                ? Collections.<String>emptySet() : scenarioProductCodes;
        for (int index = 0; index < baseTrades.size(); index++) {
            JSONObject trade = baseTrades.getJSONObject(index);
            if (trade == null) {
                continue;
            }
            String productCode = Objects.toString(trade.get("PRODUCT_CODE"), "").trim();
            if (!productCode.isEmpty() && !supported.contains(productCode)) {
                unsupported.add(productCode);
            }
        }
        return unsupported;
    }

    public JSONArray buildZeroPnlResults(
            JSONArray baseTrades,
            Set<String> unsupportedScenarioProducts) {
        JSONArray pnlResults = new JSONArray();
        if (baseTrades == null) {
            return pnlResults;
        }
        Set<String> unsupportedProducts = unsupportedScenarioProducts == null
                ? Collections.<String>emptySet() : unsupportedScenarioProducts;
        for (int index = 0; index < baseTrades.size(); index++) {
            JSONObject baseTrade = baseTrades.getJSONObject(index);
            if (baseTrade == null) {
                continue;
            }
            if (isErrorTrade(baseTrade)) {
                pnlResults.add(buildBaseErrorPnlRow(baseTrade));
            } else if (unsupportedProducts.contains(trimToNull(baseTrade.getString("PRODUCT_CODE")))) {
                pnlResults.add(buildUnsupportedScenarioPnlRow(
                        baseTrade, baseTrade.getString("PRODUCT_CODE")));
            } else {
                pnlResults.add(buildZeroPnlRow(baseTrade));
            }
        }
        return pnlResults;
    }

    private JSONObject buildZeroPnlRow(JSONObject baseTrade) {
        JSONObject pnlResult = new JSONObject();
        double baseValuationCny = baseTrade == null ? 0.0 : baseTrade.getDoubleValue("VALUATION_CNY");
        pnlResult.put("INSTRUMENT_ID", baseTrade == null ? null : baseTrade.getString("INSTRUMENT_ID"));
        pnlResult.put("BASE_VALUATION_CNY", baseValuationCny);
        pnlResult.put("SCENARIO_VALUATION_CNY", baseValuationCny);
        pnlResult.put("PNL", 0.0);
        pnlResult.put("STATUS", "SUCCESS");
        return pnlResult;
    }

    private JSONObject buildUnsupportedScenarioPnlRow(JSONObject baseTrade, String productCode) {
        JSONObject pnlResult = buildZeroPnlRow(baseTrade);
        pnlResult.put("STATUS", "ERROR");
        pnlResult.put("LOGS_JSON", buildSingleErrorLog("产品类型不支持情景: " + productCode));
        return pnlResult;
    }

    private JSONObject buildMissingScenarioResultPnlRow(JSONObject baseTrade) {
        JSONObject pnlResult = buildZeroPnlRow(baseTrade);
        pnlResult.put("STATUS", "ERROR");
        pnlResult.put("LOGS_JSON", buildSingleErrorLog("情景结果缺失"));
        return pnlResult;
    }

    private JSONObject buildAbsoluteZeroPnlRow(JSONObject baseTrade) {
        JSONObject pnlResult = new JSONObject();
        pnlResult.put("INSTRUMENT_ID", baseTrade == null ? null : baseTrade.getString("INSTRUMENT_ID"));
        pnlResult.put("BASE_VALUATION_CNY", 0.0);
        pnlResult.put("SCENARIO_VALUATION_CNY", 0.0);
        pnlResult.put("PNL", 0.0);
        pnlResult.put("STATUS", "SUCCESS");
        return pnlResult;
    }

    private boolean isErrorTrade(JSONObject trade) {
        return trade != null && "ERROR".equalsIgnoreCase(Objects.toString(trade.get("STATUS"), ""));
    }

    private JSONArray resolveErrorLogs(JSONObject errorSource, String defaultMessage) {
        if (errorSource != null) {
            JSONArray logs = errorSource.getJSONArray("LOGS_JSON");
            if (logs != null && !logs.isEmpty()) {
                return logs;
            }
            String message = trimToNull(errorSource.getString("ERROR"));
            if (message != null) {
                return buildSingleErrorLog(message);
            }
            Object detail = errorSource.get("DETAIL");
            if (detail != null) {
                message = trimToNull(Objects.toString(detail, null));
                if (message != null) {
                    return buildSingleErrorLog(message);
                }
            }
        }
        return buildSingleErrorLog(defaultMessage);
    }

    private JSONArray buildSingleErrorLog(String message) {
        JSONArray logs = new JSONArray();
        JSONObject logItem = new JSONObject();
        logItem.put("level", "ERROR");
        logItem.put("message", message);
        logs.add(logItem);
        return logs;
    }

    private boolean isAffectedTrade(Set<String> affectedTradeIds, String instrumentId) {
        return affectedTradeIds == null || affectedTradeIds.contains(instrumentId);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
