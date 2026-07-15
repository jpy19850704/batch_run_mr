package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.JsonNumberUtils;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.all.GenericMc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * 通用 MC 计算器，按 payoff 定义和路径模型定义组合估值。
 */
public class GenericMcCalc implements ProductCalculator {
    private final List<HashMap<String, Object>> trades;
    private final MarketData marketData;
    private final LocalDate dataDate;
    private final String operCode;
    private final GenericMc genericMc;

    private final JSONObject result = new JSONObject();
    private final JSONArray trade = new JSONArray();
    private final JSONArray log = new JSONArray();

    public GenericMcCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData) {
        this.operCode = operCode;
        this.dataDate = dataDate;
        this.trades = trades == null ? new ArrayList<>() : trades;
        this.marketData = marketData == null ? new MarketData() : marketData;
        this.genericMc = new GenericMc(dataDate);
    }

    public String calc() {
        calculateTrades();
        result.put("data", new JSONObject());
        result.getJSONObject("data").put("trade_data", trade);
        result.getJSONObject("data").put("log_data", log);
        JsonNumberUtils.normalizeNumbersInPlace(result);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private void calculateTrades() {
        for (HashMap<String, Object> tradeData : trades) {
            OptionMeasure measure = calcOne(tradeData, marketData);
            trade.add(measure);
            addLogIfError(measure);
        }
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd) {
        return calcScenario(scenarioMd, null);
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        JSONArray scenarioResult = new JSONArray();
        MarketData md = scenarioMd == null ? new MarketData() : scenarioMd;
        for (HashMap<String, Object> tradeData : trades) {
            Object instrumentId = tradeData == null ? null : tradeData.get("INSTRUMENT_ID");
            if (affectedIds != null && (instrumentId == null || !affectedIds.contains(String.valueOf(instrumentId)))) {
                continue;
            }
            scenarioResult.add(genericMc.priceScenario(tradeData, md));
        }
        return scenarioResult;
    }

    private OptionMeasure calcOne(HashMap<String, Object> tradeData, MarketData md) {
        return genericMc.price(tradeData, md);
    }

    private void addLogIfError(OptionMeasure measure) {
        if (measure == null || "SUCCESS".equalsIgnoreCase(measure.status)) {
            return;
        }
        JSONObject logInfo = new JSONObject();
        logInfo.put("INSTRUMENT_ID", measure.instrumentId);
        logInfo.put("PRODUCT_CODE", measure.productCode);
        logInfo.put("info", measure.logs == null ? "通用 MC 估值失败" : joinLogMessages(measure.logs));
        log.add(logInfo);
    }

    private static String joinLogMessages(List<com.zcyh.mr.product.basic.common.Measure.MeasureLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return "通用 MC 估值失败";
        }
        List<String> messages = new ArrayList<>();
        for (com.zcyh.mr.product.basic.common.Measure.MeasureLog item : logs) {
            if (item != null && item.message != null) {
                messages.add(item.message);
            }
        }
        return messages.isEmpty() ? "通用 MC 估值失败" : String.join("; ", messages);
    }
}
