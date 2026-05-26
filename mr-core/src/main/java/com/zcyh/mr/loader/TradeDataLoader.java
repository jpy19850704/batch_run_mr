package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.TradeJsonUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 交易数据加载器。
 * 负责解析交易、校验底层资产数据，并将底层资产补入交易。
 */
public class TradeDataLoader {
    private final JSONArray validationErrors;

    public TradeDataLoader(JSONArray validationErrors) {
        this.validationErrors = validationErrors == null ? new JSONArray() : validationErrors;
    }

    /**
     * 加载交易数据并执行底层资产处理。
     */
    public List<HashMap<String, Object>> load(JSONArray tradeData, JSONObject otherData) {
        List<HashMap<String, Object>> trades = loadTrades(tradeData);
        processUnderlyingData(otherData, tradeData, trades);
        return trades;
    }

    private List<HashMap<String, Object>> loadTrades(JSONArray tradeData) {
        List<HashMap<String, Object>> trades = new ArrayList<>();
        if (tradeData == null) {
            return trades;
        }
        for (Object obj : tradeData) {
            JSONObject tradeJson = (JSONObject) obj;
            String productCode = Objects.toString(tradeJson.get("PRODUCT_CODE"), "");
            List<String> errors = TradeValidator.validate(tradeJson, productCode, "TRADE");
            if (!errors.isEmpty()) {
                JSONObject errLog = new JSONObject();
                errLog.put("INSTRUMENT_ID", Objects.toString(tradeJson.get("INSTRUMENT_ID"), "UNKNOWN"));
                errLog.put("info", "数据校验失败: " + String.join("; ", errors));
                validationErrors.add(errLog);
                continue;
            }
            trades.add(TradeJsonUtil.mergeTrade(tradeJson, productCode, "TRADE"));
        }
        return trades;
    }

    private void processUnderlyingData(JSONObject otherData, JSONArray tradeData, List<HashMap<String, Object>> trades) {
        if (otherData == null) {
            return;
        }
        JSONObject underlyingData = otherData.getJSONObject("UNDERLYING_DATA");
        if (underlyingData == null) {
            return;
        }
        validateUnderlyingData(underlyingData, tradeData);
        injectUnderlyingData(underlyingData, trades);
    }

    /**
     * 按产品类型校验底层资产数据。
     */
    private void validateUnderlyingData(JSONObject underlyingData, JSONArray tradeData) {
        if (tradeData == null || tradeData.isEmpty()) {
            return;
        }
        Set<String> checkedCodes = new HashSet<>();
        for (Object tradeObj : tradeData) {
            JSONObject tradeJson = (JSONObject) tradeObj;
            String productCode = Objects.toString(tradeJson.get("PRODUCT_CODE"), "");
            if (productCode.isEmpty() || !checkedCodes.add(productCode)) {
                continue;
            }

            boolean hasChildObject = false;
            for (Object value : underlyingData.values()) {
                if (value instanceof JSONObject) {
                    hasChildObject = true;
                    break;
                }
            }

            if (!hasChildObject) {
                logUnderlyingValidation(productCode, underlyingData,
                        TradeValidator.validate(underlyingData, productCode, "UNDERLYING_DATA"),
                        Objects.toString(underlyingData.get("INSTRUMENT_ID"), ""));
                continue;
            }

            for (String underlyingKey : underlyingData.keySet()) {
                Object raw = underlyingData.get(underlyingKey);
                if (!(raw instanceof JSONObject)) {
                    continue;
                }
                JSONObject underlyingItem = (JSONObject) raw;
                logUnderlyingValidation(productCode, underlyingItem,
                        TradeValidator.validate(underlyingItem, productCode, "UNDERLYING_DATA"),
                        Objects.toString(underlyingItem.get("INSTRUMENT_ID"), underlyingKey));
            }
        }
    }

    private void logUnderlyingValidation(String productCode, JSONObject underlyingItem, List<String> errors,
            String instrumentId) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        JSONObject errLog = new JSONObject();
        errLog.put("PRODUCT_CODE", productCode);
        errLog.put("DATA_NODE", "UNDERLYING_DATA");
        errLog.put("INSTRUMENT_ID", instrumentId);
        errLog.put("info", "底层资产数据校验失败: " + String.join("; ", errors));
        validationErrors.add(errLog);
    }

    /**
     * 将底层资产数据注入交易。
     */
    private void injectUnderlyingData(JSONObject underlyingData, List<HashMap<String, Object>> trades) {
        for (HashMap<String, Object> trade : trades) {
            if (trade.get("UNDERLYING_DATA") != null) {
                continue;
            }
            String productCode = Objects.toString(trade.get("PRODUCT_CODE"), "");
            JSONArray underlyingArray = new JSONArray();
            if (Constants.PRODUCT_CODE.CDS.equals(productCode)) {
                String bondId = Objects.toString(trade.get("UNDERLYING_BOND_ID"), "");
                if (!bondId.isEmpty()) {
                    JSONObject bondData = underlyingData.getJSONObject(bondId);
                    if (bondData != null) {
                        underlyingArray.add(bondData);
                    }
                }
            } else if (Constants.PRODUCT_CODE.BOND_FUTURE.equals(productCode)) {
                Object factors = trade.get("CONVERT_FACTORS");
                if (factors instanceof JSONArray) {
                    for (Object factorObj : (JSONArray) factors) {
                        JSONObject factorEntry = (JSONObject) factorObj;
                        String bondId = factorEntry.getString("UNDERLYING_BOND_ID");
                        if (bondId == null) {
                            continue;
                        }
                        JSONObject bondData = underlyingData.getJSONObject(bondId);
                        if (bondData != null) {
                            underlyingArray.add(bondData);
                        }
                    }
                }
            } else {
                continue;
            }
            trade.put("UNDERLYING_DATA", underlyingArray);
        }
    }
}
