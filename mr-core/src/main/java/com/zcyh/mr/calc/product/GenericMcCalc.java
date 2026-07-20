package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.ProductCalculator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.support.JsonNumberUtils;
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
        JsonNumberUtils.normalizeNumbersInPlace(result);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private void calculateTrades() {
        for (HashMap<String, Object> tradeData : trades) {
            OptionMeasure measure = calcOne(tradeData, marketData);
            trade.add(measure);
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

}
